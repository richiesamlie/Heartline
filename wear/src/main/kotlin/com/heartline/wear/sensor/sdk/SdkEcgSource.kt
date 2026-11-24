// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor.sdk

import com.heartline.logging.HLog
import android.os.SystemClock
import com.heartline.shared.dsp.PpgRepair
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.EcgChunk
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.sensor.batchContact
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** ECG_ON_DEMAND via the SDK. */
class SdkEcgSource(private val gateway: SdkSensorGateway) : EcgSource {
    override fun stream(): Flow<EcgChunk> = callbackFlow {
        val state = gateway.awaitConnected(CONNECT_TIMEOUT_MS) ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        if (state is GatewayState.Failed) throw SensorException(state.problem)
        if (TrackerKind.ECG_ON_DEMAND !in (state as GatewayState.Connected).trackers) throw SensorException(SensorProblem.NOT_SUPPORTED)
        gateway.acquireLease()
        val tracker = try {
            gateway.tracker(TrackerKind.ECG_ON_DEMAND) ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        } catch (e: Throwable) {
            gateway.releaseLease()
            throw e
        }
        val disconnectWatcher = launch {
            val dropped = gateway.state.first { it !is GatewayState.Connected }
            HLog.w(SdkSensorGateway.TAG, "ECG gateway disconnected ($dropped)")
            val problem = (dropped as? GatewayState.Failed)?.problem ?: SensorProblem.NOT_SUPPORTED
            close(SensorException(problem))
        }

        HLog.i(EcgRawLog.TAG, "ECG tracker started")
        val missing = AtomicInteger(0)
        val raw = EcgRawLog()
        try {
            tracker.setEventListener(
                object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(points: List<DataPoint>) {
                        if (points.isEmpty()) return
                        SdkRawCapture.sdk(TrackerKind.ECG_ON_DEMAND.name, points)
                        // A point without a value is skipped: a substituted 0 mV would be a spike after filtering.
                        val kept = points.mapNotNull { p ->
                            p.getOrNull(ValueKey.EcgSet.ECG_MV)?.takeIf { v -> v.isFinite() }?.let { p to it }
                        }
                        val values = kept.map { it.second }
                        if (values.size < points.size) missing.addAndGet(points.size - values.size)
                        val flags = points.map { it.getOrNull(ValueKey.EcgSet.LEAD_OFF) }
                        val leadOff = !batchContact(flags)
                        val max = points.firstNotNullOfOrNull { it.getOrNull(ValueKey.EcgSet.MAX_THRESHOLD_MV) }
                        val min = points.firstNotNullOfOrNull { it.getOrNull(ValueKey.EcgSet.MIN_THRESHOLD_MV) }
                        val saturated = values.any { (max != null && it >= max) || (min != null && it <= min) }
                        // The PPG channel reported with each ECG sample (pulse arrival time), paired per valid ECG point;
                        // missing PPG entries use PpgRepair.PLACEHOLDER (-1f) so the two stay sample-aligned.
                        var hasPpg = false
                        val ppgValues = FloatArray(kept.size) { i ->
                            val v = kept[i].first.getOrNull(ValueKey.EcgSet.PPG_GREEN)?.toFloat()?.takeIf { it.isFinite() }
                            if (v != null) {
                                hasPpg = true
                                v
                            } else {
                                PpgRepair.PLACEHOLDER
                            }
                        }
                        val ppg = if (hasPpg) ppgValues else null
                        raw.batch(points, flags, values, min, max, leadOff, saturated, ppg != null)
                        if (values.isNotEmpty()) {
                            trySend(EcgChunk(values.toFloatArray(), leadOff, points.last().timestamp, ppg, saturated))
                        } else if (leadOff && points.isNotEmpty()) {
                            trySend(EcgChunk(FloatArray(points.size), leadOff = true, points.last().timestamp, null, saturated))
                        }
                    }

                    override fun onFlushCompleted() = Unit

                    override fun onError(error: HealthTracker.TrackerError) {
                        HLog.w(SdkSensorGateway.TAG, "ECG tracker error: $error")
                        close(SensorException(SdkSensorGateway.mapError(error)))
                    }
                },
            )
        } catch (e: Throwable) {
            disconnectWatcher.cancel()
            raw.end()
            gateway.releaseLease()
            throw e
        }
        awaitClose {
            disconnectWatcher.cancel()
            runCatching { tracker.unsetEventListener() }
            val missingCount = missing.get()
            if (missingCount > 0) HLog.w(SdkSensorGateway.TAG, "ECG: $missingCount points had no ECG_MV value")
            raw.end()
            gateway.releaseLease()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

/**
 * Raw ECG values for device debugging (tag [TAG]): the first [FULL_BATCHES] batches and every batch
 * where contact changes in full (LEAD_OFF of every point, mV range, SDK thresholds, sequence), a
 * summary every second, and totals at the end.
 */
private class EcgRawLog {
    private var batches = 0
    private var lastLeadOff: Boolean? = null
    private var secondStart = 0L
    private val secondFlags = mutableMapOf<Int?, Int>()
    private var secondPoints = 0
    private var secondMissing = 0
    private var secondMin = Float.MAX_VALUE
    private var secondMax = -Float.MAX_VALUE
    private var secondLeadOffBatches = 0
    private var secondBatches = 0
    private val firstFlags = mutableMapOf<Int?, Int>()
    private val otherFlags = mutableMapOf<Int?, Int>()
    private var contactBatches = 0
    private var saturatedBatches = 0

    @Synchronized
    fun batch(
        points: List<DataPoint>,
        flags: List<Int?>,
        values: List<Float>,
        min: Float?,
        max: Float?,
        leadOff: Boolean,
        saturated: Boolean,
        ppg: Boolean,
    ) {
        batches++
        firstFlags.merge(flags.first(), 1, Int::plus)
        flags.drop(1).forEach { otherFlags.merge(it, 1, Int::plus) }
        if (!leadOff) contactBatches++
        if (saturated) saturatedBatches++
        if (batches <= FULL_BATCHES || leadOff != lastLeadOff) {
            HLog.i(
                TAG,
                "batch#$batches n=${points.size} LEAD_OFF=$flags leadOff=$leadOff saturated=$saturated " +
                    "mV=[${values.minOrNull()}..${values.maxOrNull()}] thresholds=[$min..$max] ppg=$ppg " +
                    "seq=${points.first().getOrNull(ValueKey.EcgSet.SEQUENCE)}..${points.last().getOrNull(ValueKey.EcgSet.SEQUENCE)} " +
                    "t=${points.first().timestamp}..${points.last().timestamp}",
            )
        }
        lastLeadOff = leadOff
        val now = SystemClock.elapsedRealtime()
        if (secondStart == 0L) secondStart = now
        flags.forEach { secondFlags.merge(it, 1, Int::plus) }
        secondPoints += points.size
        secondMissing += points.size - values.size
        values.minOrNull()?.let { secondMin = minOf(secondMin, it) }
        values.maxOrNull()?.let { secondMax = maxOf(secondMax, it) }
        secondBatches++
        if (leadOff) secondLeadOffBatches++
        if (now - secondStart >= 1_000) {
            HLog.i(
                TAG,
                "1s: points=$secondPoints missingMv=$secondMissing LEAD_OFF=$secondFlags " +
                    "leadOffBatches=$secondLeadOffBatches/$secondBatches mV=[$secondMin..$secondMax]",
            )
            secondStart = now
            secondFlags.clear()
            secondPoints = 0
            secondMissing = 0
            secondMin = Float.MAX_VALUE
            secondMax = -Float.MAX_VALUE
            secondLeadOffBatches = 0
            secondBatches = 0
        }
    }

    @Synchronized
    fun end() {
        HLog.i(
            TAG,
            "end: batches=$batches contact=$contactBatches saturated=$saturatedBatches " +
                "LEAD_OFF first point=$firstFlags other points=$otherFlags",
        )
    }

    companion object {
        const val TAG = "Heartline/EcgRaw"
        const val FULL_BATCHES = 20
    }
}
