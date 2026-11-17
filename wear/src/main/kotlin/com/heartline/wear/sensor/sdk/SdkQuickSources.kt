// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor.sdk

import com.heartline.logging.HLog
import com.heartline.shared.body.BodyCompositionCheck
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.vitals.VitalsQuality
import com.heartline.shared.profile.Sex
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.QuickEvent
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.QuickSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.TrackerUserProfile
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Shared plumbing for on-demand SDK trackers: connect, check capability, attach a listener,
 * run a progress ticker, and translate each batch of DataPoints with [onData].
 *
 * While [problem] is set (fingers off the keys, wrist contact…) the ticker pauses and keeps
 * showing that hint, so the screen neither counts down without a signal nor flickers between
 * "Measuring" and the hint. When the tracker reports its own progress ([sdkProgress]), that
 * drives the ring. Without a result after [timeoutSeconds], the measurement ends with the hint.
 */
abstract class SdkQuickSource(
    private val gateway: SdkSensorGateway,
    private val tracker: TrackerKind,
    override val metric: Metric,
    override val kind: RecordKind,
    override val seconds: Int,
    private val timeoutSeconds: Int = seconds * 4,
) : QuickSource {
    @Volatile protected var problem: QuickHint? = null

    @Volatile protected var sdkProgress: Float? = null

    /** Quarter seconds measured without a hint, by the ticker (the progress when the sensor gives none). */
    @Volatile private var good = 0

    /** When the hint was last confirmed by the sensor; it clears itself [HINT_CLEAR_MS] later. */
    @Volatile protected var hintAtMs = 0L

    /** The progress now, for events sent between ticks. */
    protected val currentFraction: Float get() = (sdkProgress ?: (good / (seconds * 4f))).coerceIn(0f, 0.98f)

    protected abstract fun create(profile: UserProfile?): HealthTracker?

    /** True when the sensor only reports a hint while it lasts (SpO2), not when it ends. */
    protected open val clearsItself: Boolean = false

    protected open fun onStart() = Unit

    protected open fun onStop() = Unit

    /** @return true when the measurement has finished (result or failure sent). */
    protected abstract fun ProducerScope<QuickEvent>.onData(points: List<DataPoint>): Boolean

    override fun measure(profile: UserProfile?): Flow<QuickEvent> = callbackFlow {
        problem = null
        sdkProgress = null
        onStart()
        gateway.connect()
        val state = withTimeout(10_000) { gateway.state.first { it is GatewayState.Connected || it is GatewayState.Failed } }
        if (state is GatewayState.Failed) throw SensorException(state.problem)
        if (tracker !in (state as GatewayState.Connected).trackers) throw SensorException(SensorProblem.NOT_SUPPORTED)
        val healthTracker = create(profile) ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        good = 0
        val ticker = launch {
            for (tick in 1..timeoutSeconds * 4) {
                delay(250)
                // A hint the sensor stopped repeating (the wrist is still again) doesn't hold the
                // progress until the sensor's next point, which can be seconds away.
                if (clearsItself && problem != null && System.currentTimeMillis() - hintAtMs > HINT_CLEAR_MS) problem = null
                val hint = problem
                if (hint == null) good++
                trySend(QuickEvent.Progress(currentFraction, hint))
            }
            HLog.w(SdkSensorGateway.TAG, "$tracker: no result after $timeoutSeconds s (hint=$problem)")
            trySend(QuickEvent.Failed(null, problem ?: QuickHint.LOW_SIGNAL))
            close()
        }
        healthTracker.setEventListener(
            object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    SdkRawCapture.sdk(tracker.name, points)
                    if (points.isNotEmpty() && onData(points)) {
                        ticker.cancel()
                        close()
                    }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    HLog.w(SdkSensorGateway.TAG, "$tracker error: $error")
                    close(SensorException(SdkSensorGateway.mapError(error)))
                }
            },
        )
        awaitClose {
            ticker.cancel()
            healthTracker.unsetEventListener()
            onStop()
        }
    }
}

/** A hint the sensor stopped repeating clears itself after this long. */
internal const val HINT_CLEAR_MS = 2_500L

/** Raw values of every point from the quick trackers (SpO2, skin temperature), for device debugging. */
private const val QUICK_RAW_TAG = "Heartline/QuickRaw"

/** The hint an SPO2_ON_DEMAND status gives at once: -4, the wrist moved. */
object Spo2Status {
    fun hint(status: Int?): QuickHint? = if (status == -4) QuickHint.HOLD_STILL else null
}

/** SPO2_ON_DEMAND: status 0 calculating, 2 complete, -4 moved, -5 low signal, -6 timeout (both near 25–30 s). */
class SdkSpo2Source(private val gateway: SdkSensorGateway) :
    SdkQuickSource(gateway, TrackerKind.SPO2_ON_DEMAND, Metric.SPO2, RecordKind.SPO2, 30) {
    override fun create(profile: UserProfile?) = gateway.tracker(TrackerKind.SPO2_ON_DEMAND)

    override val clearsItself = true

    /** The sensor's time of its last "hold still" in this measurement, ms, or null. */
    @Volatile private var lastHoldStillMs: Long? = null

    override fun onStart() {
        lastHoldStillMs = null
    }

    override fun ProducerScope<QuickEvent>.onData(points: List<DataPoint>): Boolean {
        points.forEach {
            if (it.getOrNull(ValueKey.SpO2Set.STATUS) == -4) lastHoldStillMs = it.timestamp
            HLog.i(
                QUICK_RAW_TAG,
                "SpO2 status=${it.getOrNull(ValueKey.SpO2Set.STATUS)} spo2=${it.getOrNull(ValueKey.SpO2Set.SPO2)} " +
                    "hr=${it.getOrNull(ValueKey.SpO2Set.HEART_RATE)} t=${it.timestamp}",
            )
        }
        val p = points.last()
        return when (p.getOrNull(ValueKey.SpO2Set.STATUS)) {
            2 -> {
                val percent = p.getOrNull(ValueKey.SpO2Set.SPO2)?.takeIf { it in 1..100 } ?: run {
                    trySendBlocking(QuickEvent.Failed(null, QuickHint.LOW_SIGNAL))
                    return true
                }
                val low = VitalsQuality.spo2MovedBeforeResult(lastHoldStillMs?.let { p.timestamp - it })
                trySendBlocking(QuickEvent.Result(RecordSummary.Spo2(percent, p.getOrNull(ValueKey.SpO2Set.HEART_RATE), low)))
                true
            }
            -4 -> {
                problem = Spo2Status.hint(-4)
                hintAtMs = System.currentTimeMillis()
                // Right away, not from the progress ticker: its timers stand still while the watch
                // sleeps, and background tries then logged moved=false for a wrist the sensor saw move.
                // With the progress so far: the measurement waits, it doesn't start over.
                trySendBlocking(QuickEvent.Progress(currentFraction, problem))
                false
            }
            0 -> {
                problem = null
                // Still calculating: the heart rate is already known, show it live.
                p.getOrNull(ValueKey.SpO2Set.HEART_RATE)?.takeIf { it > 0 }?.let { trySendBlocking(QuickEvent.Live(it)) }
                false
            }
            -5 -> {
                trySendBlocking(QuickEvent.Failed(null, QuickHint.LOW_SIGNAL))
                true
            }
            -6 -> {
                // The sensor's own time ran out (about 30 s, usually after the wrist moved early on):
                // it was shown as "wear your watch", which sent users to fix a fit that was fine.
                trySendBlocking(QuickEvent.Failed(null, QuickHint.TIMED_OUT))
                true
            }
            else -> false
        }
    }
}

/** SKIN_TEMPERATURE_ON_DEMAND: status 0 normal, -1 error. */
class SdkSkinTempSource(private val gateway: SdkSensorGateway) :
    SdkQuickSource(gateway, TrackerKind.SKIN_TEMPERATURE_ON_DEMAND, Metric.SKIN_TEMPERATURE, RecordKind.SKIN_TEMPERATURE, 10) {
    override fun create(profile: UserProfile?) = gateway.tracker(TrackerKind.SKIN_TEMPERATURE_ON_DEMAND)

    override fun ProducerScope<QuickEvent>.onData(points: List<DataPoint>): Boolean {
        points.forEach {
            HLog.i(
                QUICK_RAW_TAG,
                "SkinTemp status=${it.getOrNull(ValueKey.SkinTemperatureSet.STATUS)} " +
                    "object=${it.getOrNull(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)} " +
                    "ambient=${it.getOrNull(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)} t=${it.timestamp}",
            )
        }
        val p = points.last()
        if (p.getOrNull(ValueKey.SkinTemperatureSet.STATUS) != 0) {
            trySendBlocking(QuickEvent.Failed(null, QuickHint.WRIST_CONTACT))
            return true
        }
        val skin = p.getOrNull(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE) ?: return false
        trySendBlocking(QuickEvent.Result(RecordSummary.SkinTemperature(skin, p.getOrNull(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE))))
        return true
    }
}

/**
 * BIA_ON_DEMAND with the user profile (TrackerUserProfile: gender 1 = male, 0 = female).
 * Status (ValueKey.BiaSet): 0 success, 4 wrist electrode detached, 7/8 a finger off the 2 or
 * 4 o'clock key, 9 both fingers off, 10 wrist loose, 11 dry skin, 13 impedance out of range,
 * 14 hands touching, 15 fingers on the metal frame, 17 unstable impedance, 18 body fat out of
 * range for the profile.
 */
class SdkBiaSource(private val gateway: SdkSensorGateway) :
    SdkQuickSource(gateway, TrackerKind.BIA_ON_DEMAND, Metric.BODY_COMPOSITION, RecordKind.BODY_COMPOSITION, 15) {
    override fun create(profile: UserProfile?): HealthTracker? {
        val p = profile ?: return null
        val sex = p.calcSex ?: return null
        val age = p.age()?.takeIf { it in 0..200 } ?: return null
        if (p.heightCm !in 20f..300f || p.weightKg !in 2f..500f) {
            HLog.w(SdkSensorGateway.TAG, "BIA profile out of SDK bounds: h=${p.heightCm} w=${p.weightKg} age=$age")
            return null
        }
        HLog.i(RAW_TAG, "bia tracker prepared")
        measuredWith = p
        val sdkProfile = TrackerUserProfile.Builder()
            .setAge(age)
            .setGender(if (sex == Sex.MALE) 1 else 0)
            .setHeight(p.heightCm)
            .setWeight(p.weightKg)
            .build()
        return gateway.trackerWithProfile(TrackerKind.BIA_ON_DEMAND, sdkProfile)
    }

    /** The profile the tracker was created with: its weight and height go into the record. */
    @Volatile private var measuredWith: UserProfile? = null

    /** Every STATUS the sensor reported in this measurement and how often (logged at the end). */
    private val statuses = mutableMapOf<Int?, Int>()

    override fun onStart() = synchronized(statuses) { statuses.clear() }

    override fun onStop() {
        val snapshot = synchronized(statuses) { statuses.toMap() }
        HLog.i(SdkSensorGateway.TAG, "BIA statuses seen: $snapshot")
    }

    override fun ProducerScope<QuickEvent>.onData(points: List<DataPoint>): Boolean {
        points.forEach { point ->
            synchronized(statuses) { statuses.merge(point.getOrNull(ValueKey.BiaSet.STATUS), 1, Int::plus) }
            HLog.i(RAW_TAG, rawBia(point))
        }
        val p = points.last()
        val status = p.getOrNull(ValueKey.BiaSet.STATUS)
        val fraction = biaFraction(p.getOrNull(ValueKey.BiaSet.PROGRESS))
        return when (status) {
            0 -> {
                val fat = p.getOrNull(ValueKey.BiaSet.BODY_FAT_RATIO)
                if (fraction < 1f || fat == null || fat <= 0f) {
                    // Still measuring (or finished without values yet: wait for the next point).
                    problem = null
                    sdkProgress = fraction
                    false
                } else if (BodyCompositionCheck.atSensorFloor(p.getOrNull(ValueKey.BiaSet.BODY_FAT_MASS))) {
                    // The sensor's lowest fat mass: the profile doesn't fit the body (143 cm / 30 kg typed by
                    // mistake gave exactly 2.0 kg twice, then status 18). Not kept: the profile is checked first.
                    HLog.i(SdkSensorGateway.TAG, "BIA fat mass at the sensor's floor: check the profile")
                    trySendBlocking(QuickEvent.Failed(null, QuickHint.CHECK_PROFILE))
                    true
                } else {
                    trySendBlocking(
                        QuickEvent.Result(
                            RecordSummary.BodyComposition(
                                bodyFatPercent = fat,
                                skeletalMuscleKg = p.getOrNull(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS),
                                bodyWaterKg = p.getOrNull(ValueKey.BiaSet.TOTAL_BODY_WATER),
                                bmrKcal = p.getOrNull(ValueKey.BiaSet.BASAL_METABOLIC_RATE)?.toInt(),
                                weightKg = measuredWith?.weightKg?.takeIf { it > 0 },
                                heightCm = measuredWith?.heightCm?.takeIf { it > 0 },
                                bodyFatMassKg = p.getOrNull(ValueKey.BiaSet.BODY_FAT_MASS),
                                skeletalMusclePercent = p.getOrNull(ValueKey.BiaSet.SKELETAL_MUSCLE_RATIO),
                                fatFreeMassKg = p.getOrNull(ValueKey.BiaSet.FAT_FREE_MASS),
                                fatFreePercent = p.getOrNull(ValueKey.BiaSet.FAT_FREE_RATIO),
                                impedanceOhm = p.getOrNull(ValueKey.BiaSet.BODY_IMPEDANCE_MAGNITUDE),
                                phaseAngleDeg = p.getOrNull(ValueKey.BiaSet.BODY_IMPEDANCE_DEGREE),
                            ),
                        ),
                    )
                    true
                }
            }
            18 -> {
                // Final: the profile doesn't match the body (e.g. age or weight wrong).
                trySendBlocking(QuickEvent.Failed(null, QuickHint.CHECK_PROFILE))
                true
            }
            else -> {
                // Contact problems: the watch restarts the measurement once they're fixed.
                problem = biaHint(status)
                sdkProgress = 0f
                false
            }
        }
    }

    private fun rawBia(p: DataPoint) = "status=${p.getOrNull(ValueKey.BiaSet.STATUS)} progress=${p.getOrNull(ValueKey.BiaSet.PROGRESS)} " +
        "fat%=${p.getOrNull(ValueKey.BiaSet.BODY_FAT_RATIO)} fatKg=${p.getOrNull(ValueKey.BiaSet.BODY_FAT_MASS)} " +
        "muscleKg=${p.getOrNull(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS)} muscle%=${p.getOrNull(ValueKey.BiaSet.SKELETAL_MUSCLE_RATIO)} " +
        "waterL=${p.getOrNull(ValueKey.BiaSet.TOTAL_BODY_WATER)} bmr=${p.getOrNull(ValueKey.BiaSet.BASAL_METABOLIC_RATE)} " +
        "fatFreeKg=${p.getOrNull(ValueKey.BiaSet.FAT_FREE_MASS)} fatFree%=${p.getOrNull(ValueKey.BiaSet.FAT_FREE_RATIO)} " +
        "impedance=${p.getOrNull(ValueKey.BiaSet.BODY_IMPEDANCE_MAGNITUDE)} phase=${p.getOrNull(ValueKey.BiaSet.BODY_IMPEDANCE_DEGREE)} " +
        "t=${p.timestamp}"

    private companion object {
        const val RAW_TAG = "Heartline/BiaRaw"
    }
}

/**
 * BIA progress as a 0–1 fraction. The Galaxy Watch8 reports 0–1 (1.0 = done, seen in a
 * device logcat); a 0–100 value is accepted too.
 */
internal fun biaFraction(progress: Float?): Float = when {
    progress == null || !progress.isFinite() -> 0f
    progress > 1.5f -> (progress / 100f).coerceAtMost(1f)
    else -> progress.coerceIn(0f, 1f)
}

internal fun biaHint(status: Int?): QuickHint = when (status) {
    7 -> QuickHint.TOP_KEY
    8 -> QuickHint.BOTTOM_KEY
    9 -> QuickHint.TOUCH_KEYS
    11 -> QuickHint.DRY_SKIN
    14 -> QuickHint.HANDS_APART
    15 -> QuickHint.KEYS_ONLY
    13, 17 -> QuickHint.HOLD_STILL
    else -> QuickHint.WRIST_CONTACT // 4, 10 and anything undocumented
}

/** Average skin conductance (µS) over [seconds] from EDA_CONTINUOUS (Watch8+), or null. */
suspend fun SdkSensorGateway.readSkinConductance(seconds: Int = 10): Float? {
    val connected = state.value as? GatewayState.Connected ?: return null
    if (TrackerKind.EDA_CONTINUOUS !in connected.trackers) return null
    val tracker = tracker(TrackerKind.EDA_CONTINUOUS) ?: return null
    val values = mutableListOf<Float>()
    return runCatching {
        withTimeout(seconds * 1000L + 2_000) {
            callbackFlow<Float> {
                tracker.setEventListener(
                    object : HealthTracker.TrackerEventListener {
                        override fun onDataReceived(points: List<DataPoint>) {
                            SdkRawCapture.sdk(TrackerKind.EDA_CONTINUOUS.name, points)
                            points.filter { it.getOrNull(ValueKey.EdaSet.STATUS) == 0 }
                                .mapNotNull { it.getOrNull(ValueKey.EdaSet.SKIN_CONDUCTANCE)?.takeIf { v -> v.isFinite() } }
                                .forEach { trySendBlocking(it) }
                        }

                        override fun onFlushCompleted() = Unit

                        override fun onError(error: HealthTracker.TrackerError) {
                            close()
                        }
                    },
                )
                launch {
                    delay(seconds * 1000L)
                    runCatching { tracker.flush() }
                    close()
                }
                awaitClose { runCatching { tracker.unsetEventListener() } }
            }.collect { values += it }
        }
        values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    }.getOrNull()
}
