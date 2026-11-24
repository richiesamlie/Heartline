// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor.sdk

import com.heartline.logging.HLog
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.HrSamples
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.HrSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal interface HrTrackerHandle {
    fun setEventListener(listener: HealthTracker.TrackerEventListener)
    fun unsetEventListener()
    fun flush(): Boolean
}

private class AndroidHrTrackerHandle(private val delegate: HealthTracker) : HrTrackerHandle {
    override fun setEventListener(listener: HealthTracker.TrackerEventListener) = delegate.setEventListener(listener)
    override fun unsetEventListener() = delegate.unsetEventListener()
    override fun flush(): Boolean = delegate.flush()
}

/**
 * HEART_RATE_CONTINUOUS (sample: track-heart-rate-with-off-body-sensor.html).
 *
 * The app has one heart-rate tracker, and each listener replaces the previous one. The live screen,
 * stress and the background irregular-rhythm window could otherwise steal it from each other (and
 * the first to finish would stop it for everyone), so all collectors share one listener.
 */
class SdkHrSource internal constructor(
    private val gateway: SdkSensorGateway,
    private val openTracker: () -> HrTrackerHandle?,
) : HrSource {
    constructor(gateway: SdkSensorGateway) : this(
        gateway,
        openTracker = { gateway.tracker(TrackerKind.HEART_RATE_CONTINUOUS)?.let(::AndroidHrTrackerHandle) },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flushExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "heartline-hr-flush").apply { isDaemon = true }
    }
    private val flushing = AtomicBoolean(false)

    /** The tracker while someone listens, and the pending flush (see [flush]). */
    @Volatile private var active: HrTrackerHandle? = null

    @Volatile private var flushed: CompletableDeferred<Unit>? = null

    private val shared: SharedFlow<Result<HrSample>> = tracked()
        .buffer(FLOW_BUFFER_CAPACITY)
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    override fun stream(): Flow<HrSample> = shared.buffer(FLOW_BUFFER_CAPACITY).map { it.getOrThrow() }

    override val isActive: Boolean get() = synchronized(gateway.hrMonitor) { active != null }

    /**
     * The tracker's flush() can block until it sends its next batch (minutes, with the screen off),
     * which no coroutine timeout can stop: it runs on a bounded single-thread executor and is waited
     * for at most [FLUSH_TIMEOUT_MS]. Readings that come later reach the next window.
     */
    override suspend fun flush(): Boolean {
        val tracker = synchronized(gateway.hrMonitor) { active } ?: return false
        if (!flushing.compareAndSet(false, true)) return false
        val done = CompletableDeferred<Unit>().also { flushed = it }
        val called = CompletableDeferred<Boolean>()
        flushExecutor.execute {
            try {
                called.complete(runCatching { tracker.flush() }.getOrDefault(false))
            } finally {
                flushing.set(false)
            }
        }
        return try {
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) { called.await() && run { done.await(); true } } == true
        } finally {
            if (flushed === done) flushed = null
        }
    }

    private fun tracked(): Flow<HrSample> = callbackFlow {
        val state = gateway.awaitConnected(CONNECT_TIMEOUT_MS) ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        if (state is GatewayState.Failed) throw SensorException(state.problem)
        if (TrackerKind.HEART_RATE_CONTINUOUS !in (state as GatewayState.Connected).trackers) {
            throw SensorException(SensorProblem.NOT_SUPPORTED)
        }
        gateway.acquireLease()
        val tracker = try {
            openTracker() ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        } catch (e: Throwable) {
            gateway.releaseLease()
            throw e
        }
        val disconnectWatcher = launch {
            val dropped = gateway.state.first { it !is GatewayState.Connected }
            HLog.w(SdkSensorGateway.TAG, "HR gateway disconnected ($dropped)")
            val problem = (dropped as? GatewayState.Failed)?.problem ?: SensorProblem.NOT_SUPPORTED
            close(SensorException(problem))
        }
        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(points: List<DataPoint>) {
                SdkRawCapture.sdk(TrackerKind.HEART_RATE_CONTINUOUS.name, points)
                var dropped = 0
                points.forEach { if (!trySend(it.toSample()).isSuccess) dropped++ }
                if (dropped > 0) HLog.w(SdkSensorGateway.TAG, "HR tracker dropped $dropped of ${points.size} buffered sample(s)")
            }

            override fun onFlushCompleted() {
                flushed?.complete(Unit)
            }

            override fun onError(error: HealthTracker.TrackerError) {
                HLog.w(SdkSensorGateway.TAG, "HR tracker error: $error")
                close(SensorException(SdkSensorGateway.mapError(error)))
            }
        }
        try {
            synchronized(gateway.hrMonitor) {
                tracker.setEventListener(listener)
                active = tracker
            }
        } catch (e: Throwable) {
            disconnectWatcher.cancel()
            gateway.releaseLease()
            throw e
        }
        awaitClose {
            disconnectWatcher.cancel()
            synchronized(gateway.hrMonitor) {
                if (active === tracker) active = null
                runCatching { tracker.unsetEventListener() }
            }
            gateway.releaseLease()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val FLUSH_TIMEOUT_MS = 10_000L
        const val FLOW_BUFFER_CAPACITY = 2048
    }

    private fun DataPoint.toSample(): HrSample {
        val status = getOrNull(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: 0
        val ibis = getOrNull(ValueKey.HeartRateSet.IBI_LIST).orEmpty()
        val ibiStatus = getOrNull(ValueKey.HeartRateSet.IBI_STATUS_LIST).orEmpty()
        if (ibis.isNotEmpty()) HLog.v(SdkSensorGateway.TAG, "HR status=$status ibis=$ibis ibiStatus=$ibiStatus")
        return HrSamples.fromTracker(timestamp, getOrNull(ValueKey.HeartRateSet.HEART_RATE) ?: 0, status, ibis, ibiStatus)
    }

}
