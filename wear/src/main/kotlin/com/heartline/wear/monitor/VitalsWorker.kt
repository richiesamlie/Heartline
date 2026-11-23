// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.heartline.logging.HLog
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.shared.vitals.Spo2Attempt
import com.heartline.shared.vitals.Spo2Outcome
import com.heartline.shared.vitals.Spo2Sample
import com.heartline.shared.vitals.Spo2Schedule
import com.heartline.shared.vitals.TempSample
import com.heartline.shared.vitals.VitalsBaseline
import com.heartline.shared.vitals.VitalsMonitor
import com.heartline.shared.vitals.VitalsQuality
import com.heartline.wear.link.AppForeground
import com.heartline.wear.quick.QuickSources
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.sensor.QuickEvent
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.QuickSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BackgroundPreemptedException : CancellationException("preempted by foreground measurement")

/** One background measurement at a time: the sensors are shared by the rhythm and vitals workers. */
object BackgroundSensors {
    val lock = Mutex()
    private val activeLock = Any()
    private var activeJob: Job? = null
    private var preemptedFlag: AtomicBoolean? = null

    suspend fun <T> runPreemptible(block: suspend () -> T): T? {
        val flag = AtomicBoolean(false)
        return try {
            coroutineScope {
                val child = async { block() }
                synchronized(activeLock) {
                    if (!child.isCompleted) {
                        activeJob = child
                        preemptedFlag = flag
                    }
                }
                try {
                    child.await()
                } finally {
                    synchronized(activeLock) {
                        if (activeJob === child) {
                            activeJob = null
                            preemptedFlag = null
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            if (flag.get() && currentCoroutineContext().isActive) null else throw e
        }
    }

    fun preempt(reason: String = "foreground measurement") {
        val (job, flag) = synchronized(activeLock) { activeJob to preemptedFlag }
        if (job != null && job.isActive) {
            HLog.i("Heartline/Monitor", "preempting background sensor work ($reason)")
            flag?.set(true)
            job.cancel(BackgroundPreemptedException())
        }
    }
}

/** Runs one on-demand measurement to its end: the result, and whether the wearer moved meanwhile. */
object VitalsMeasurer {
    /** [failure]: why there is no result ("hold still", "low signal", "timed out"), for the log. */
    data class Reading(val summary: RecordSummary?, val moved: Boolean, val failure: String? = null, val tookMs: Long = 0)

    /** [stopOnMove]: end at the first "hold still" (background tries: the light needn't stay on for nothing). */
    suspend fun measure(source: QuickSource, timeoutMs: Long = (source.seconds + 25) * 1_000L, stopOnMove: Boolean = false): Reading {
        var moved = false
        var lastHint: QuickHint? = null
        val start = System.currentTimeMillis()
        val end = withTimeoutOrNull(timeoutMs) {
            source.measure(null)
                .onEach {
                    if (it is QuickEvent.Progress) it.hint?.let { h -> lastHint = h }
                    if (it is QuickEvent.Progress && it.hint == QuickHint.HOLD_STILL) moved = true
                }
                .first { it is QuickEvent.Result || it is QuickEvent.Failed || (stopOnMove && it is QuickEvent.Progress && it.hint == QuickHint.HOLD_STILL) }
        }
        val failure = when (end) {
            is QuickEvent.Result -> null
            is QuickEvent.Failed -> "failed: ${end.problem ?: end.hint ?: "unknown"}"
            is QuickEvent.Progress -> STOPPED_MOVED
            else -> "timed out" + (lastHint?.let { " ($it)" } ?: "")
        }
        return Reading((end as? QuickEvent.Result)?.summary, moved, failure, System.currentTimeMillis() - start)
    }

    const val STOPPED_MOVED = "moved"
}

/**
 * Background blood oxygen and skin temperature (docs/algorithms/VITALS_MONITORING.md). Every 30
 * minutes: skin temperature in sleep (hourly by day), SpO2 every hour awake and asleep, only while
 * the watch is worn and the wearer still ([Spo2Schedule]: a moving wearer is tried again 10
 * minutes later, up to four times an hour). A low SpO2 reading is checked again 2 minutes later,
 * twice at most; a normal re-check overrides it, no re-check result leaves it as it is, and three
 * low in a row notify (not when all three are low confidence).
 */
class VitalsWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val sources: QuickSources by inject()
    private val store: WatchSettingsStore by inject()
    private val output: WatchMonitorOutput by inject()
    private val profile: WatchProfileStore by inject()
    private val notifier: WatchNotifier by inject()
    private val measurements: com.heartline.wear.remote.RemoteMeasureCoordinator by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ForegroundInfo(WatchNotifier.MONITOR_ID, notifier.monitoring(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
    } else {
        ForegroundInfo(WatchNotifier.MONITOR_ID, notifier.monitoring())
    }

    private class Spo2AttemptBox(val sample: Spo2Sample?)

    private fun blockedForSensors(): Boolean =
        AppForeground.resumed || measurements.busy || Charging.isCharging(applicationContext) || PowerGuard.isPowerSaveMode(applicationContext)

    private fun blockReason(): Spo2Outcome? = when {
        Charging.isCharging(applicationContext) -> Spo2Outcome.CHARGING
        PowerGuard.isPowerSaveMode(applicationContext) -> Spo2Outcome.LOW_BATTERY
        AppForeground.resumed || measurements.busy -> Spo2Outcome.APP_OPEN
        else -> null
    }

    override suspend fun doWork(): Result {
        val settings = store.settings.value.normalized()
        if (!settings.spo2Active && !settings.skinTempActive) return Result.success()
        val now = System.currentTimeMillis()
        val lastWorn = store.lastPassiveHeartRateMs
        // Unknown right after an update or install: then the off-body sensor below decides.
        if (settings.passiveHeartRate && lastWorn != null && now - lastWorn > NOT_WORN_MS) {
            HLog.i(TAG, "vitals skipped: not worn")
            return Result.success()
        }
        // Whether a SpO2 try is due now: then the phone hears why it didn't happen, if it doesn't.
        val spo2Due = settings.spo2Active && now - store.lastSpo2Ms >= (settings.spo2Interval - 5) * MINUTE
        // On the charger the watch is off the wrist: no red light, no temperature reading.
        if (Charging.isCharging(applicationContext)) {
            HLog.i(TAG, "vitals skipped: charging")
            if (spo2Due) reportAttempt(Spo2Attempt(now, Spo2Outcome.CHARGING))
            return Result.success()
        }
        // Under Power Saver active background vitals step back so essential watch functions keep running.
        if (PowerGuard.isPowerSaveMode(applicationContext)) {
            HLog.i(TAG, "vitals skipped: power saver")
            if (spo2Due) reportAttempt(Spo2Attempt(now, Spo2Outcome.LOW_BATTERY))
            return Result.success()
        }
        // A Heartline screen is open or a measurement is running: the on-screen measurement and this one would
        // take each other's tracker. Try again in 15 minutes.
        if (AppForeground.resumed || measurements.busy) {
            BackgroundMonitoring.scheduleVitalsRetry(applicationContext)
            HLog.i(TAG, "vitals put off: measurement or app active")
            if (spo2Due) reportAttempt(Spo2Attempt(now, Spo2Outcome.APP_OPEN, retryAtMs = now + 15 * MINUTE))
            return Result.success()
        }
        val minute = now / MINUTE * MINUTE
        // Without activity recognition, the usual sleep hours (from the monitoring setup) stand in.
        val context = store.activityAt(minute) ?: java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            .let { if (settings.isUsualSleep(it.hour * 60 + it.minute)) HrContext.SLEEP else HrContext.REST }
        if (context == HrContext.EXERCISE) return Result.success()
        val asleep = context == HrContext.SLEEP
        // Steps over the last minutes (awake, even a few spoil a blood-oxygen reading).
        val steps = (0 until Spo2Schedule.STEP_MINUTES).maxOf { store.activity.stepsPerMinute(minute - it * MINUTE) }
        val monitor = VitalsMonitor { UUID.randomUUID().toString() }
        val spo2Samples = mutableListOf<Spo2Sample>()
        val temps = mutableListOf<TempSample>()
        val alerts = mutableListOf<HealthAlert>()
        val attempts = mutableListOf<Spo2Attempt>()

        var firstSample: Spo2Sample? = null
        var spo2Source: QuickSource? = null
        var waitedS: Int? = null
        BackgroundSensors.lock.withLock {
            blockReason()?.let { reason ->
                if (reason == Spo2Outcome.APP_OPEN) BackgroundMonitoring.scheduleVitalsRetry(applicationContext)
                if (spo2Due) attempts += Spo2Attempt(now, reason, retryAtMs = if (reason == Spo2Outcome.APP_OPEN) now + 15 * MINUTE else null)
                return@withLock
            }
            val wrist = WristState(applicationContext, turns = false, motion = false).also { it.start() }
            try {
                delay(1_500)
                if (wrist.offBody) {
                    HLog.i(TAG, "vitals skipped: off the wrist")
                    if (spo2Due) attempts += Spo2Attempt(now, Spo2Outcome.OFF_WRIST)
                    return@withLock
                }
                // Without the background sensor permission Android needs a (silent) foreground service.
                if (!BackgroundMonitoring.hasBackgroundPermission(applicationContext)) {
                    runCatching { setForeground(foregroundInfo()) }.onFailure { HLog.w(TAG, "foreground refused", it) }
                }
                // Skin temperature: every 30 minutes asleep, hourly by day (skipped by day under low battery).
                val tempDue = now - store.lastTempMs >= if (asleep) 25 * MINUTE else 55 * MINUTE
                if (settings.skinTempActive && tempDue && allowed(Metric.SKIN_TEMPERATURE) && !blockedForSensors()) {
                    if (!asleep && battery() < PowerGuard.LOW_BATTERY) {
                        HLog.i(TAG, "temperature skipped: low battery (${battery()} %)")
                    } else {
                        sources[Metric.SKIN_TEMPERATURE]?.let { source ->
                            val reading = BackgroundSensors.runPreemptible { VitalsMeasurer.measure(source) }
                            if (reading == null) {
                                HLog.i(TAG, "temperature preempted by active measurement")
                            } else {
                                val r = reading.summary as? RecordSummary.SkinTemperature
                                if (r == null) HLog.i(TAG, "temperature no result: ${reading.failure}")
                                if (r != null && !VitalsQuality.temperature(r.skinCelsius, r.ambientCelsius, store.lastAmbientC)) {
                                    HLog.i(TAG, "temperature ${r.skinCelsius} rejected (air ${r.ambientCelsius}, before ${store.lastAmbientC})")
                                }
                                if (r != null && VitalsQuality.temperature(r.skinCelsius, r.ambientCelsius, store.lastAmbientC)) {
                                    val since = store.asleepSince(now)
                                    val counted = asleep && since != null && now - since >= HOUR
                                    val sample = TempSample(now, r.skinCelsius, r.ambientCelsius, context, counted)
                                    temps += sample
                                    store.updateVitals { current -> monitor.onTemp(current, sample) }
                                }
                                store.lastAmbientC = r?.ambientCelsius
                                store.lastTempMs = now
                            }
                        }
                    }
                }
                // Blood oxygen: once per interval, awake and asleep, by the rules of Spo2Schedule.
                val every = settings.spo2Interval
                val retries = Spo2Schedule.retriesNow(store.spo2RetrySlot, store.spo2Retries, now, every)
                fun decide(armMoved: Boolean) = Spo2Schedule.decide(
                    now, store.lastSpo2Ms, every, asleep, settings.spo2InSleep, battery(), steps, context == HrContext.ACTIVE, armMoved, retries,
                )
                var decision = decide(armMoved = false)

                // Awake: wait for a still arm (within 2 minutes in all) rather than giving up at the first movement.
                if (settings.spo2Active && decision == Spo2Schedule.Decision.Measure && !asleep && !blockedForSensors()) {
                    val still = BackgroundSensors.runPreemptible { waitForStillArm { waitedS = it } }
                    if (still == null) {
                        BackgroundMonitoring.scheduleVitalsRetry(applicationContext)
                        attempts += Spo2Attempt(now, Spo2Outcome.APP_OPEN, retryAtMs = now + 15 * MINUTE, waitedS = waitedS)
                        HLog.i(TAG, "SpO2 still-arm wait preempted by active measurement")
                        return@withLock
                    }
                    if (!still) decision = decide(armMoved = true)
                    HLog.i(TAG, if (still) "SpO2 start 1/${Spo2Schedule.MAX_STARTS} after ${waitedS}s still" else "SpO2: no still moment in ${waitedS}s")
                }
                if (settings.spo2Active) when (val d = decision) {
                    is Spo2Schedule.Decision.Skip -> if (d.reason != "not due") {
                        HLog.i(TAG, "SpO2 not measured: ${d.reason}")
                        val outcome = when {
                            d.reason.startsWith("battery") -> Spo2Outcome.LOW_BATTERY
                            d.reason == "off in sleep" -> Spo2Outcome.OFF_IN_SLEEP
                            else -> Spo2Outcome.MOVING
                        }
                        attempts += Spo2Attempt(now, outcome, waitedS = waitedS)
                    }
                    is Spo2Schedule.Decision.PutOff -> {
                        store.spo2RetrySlot = Spo2Schedule.slot(now, every)
                        store.spo2Retries = retries + 1
                        BackgroundMonitoring.scheduleVitalsRetry(applicationContext, d.retryMinutes.toLong())
                        HLog.i(TAG, "SpO2 put off: ${d.reason} (try ${retries + 1})")
                        attempts += Spo2Attempt(now, Spo2Outcome.MOVING, retryAtMs = now + d.retryMinutes * MINUTE, waitedS = waitedS)
                    }
                    Spo2Schedule.Decision.Measure -> {
                        val blocked = blockReason()
                        if (blocked != null) {
                            if (blocked == Spo2Outcome.APP_OPEN) BackgroundMonitoring.scheduleVitalsRetry(applicationContext)
                            attempts += Spo2Attempt(
                                now,
                                blocked,
                                retryAtMs = if (blocked == Spo2Outcome.APP_OPEN) now + 15 * MINUTE else null,
                                waitedS = waitedS,
                            )
                            HLog.i(TAG, "SpO2 skipped right before sensor start: $blocked")
                        } else if (allowed(Metric.SPO2)) {
                            sources[Metric.SPO2]?.let { source ->
                                // The sensor pauses on a movement and finishes by itself (21 of its results in a real
                                // log came after one, no different from the rest): no more stopping on movement.
                                val attempt = BackgroundSensors.runPreemptible {
                                    Spo2AttemptBox(spo2(source, context, now, confirmation = false))
                                }
                                if (attempt == null) {
                                    BackgroundMonitoring.scheduleVitalsRetry(applicationContext)
                                    attempts += Spo2Attempt(now, Spo2Outcome.APP_OPEN, retryAtMs = now + 15 * MINUTE, waitedS = waitedS)
                                    HLog.i(TAG, "SpO2 preempted by active measurement")
                                    return@withLock
                                }
                                val first = attempt.sample
                                if (first == null) {
                                    val again = lastFailure == Spo2Outcome.REJECTED && retries < MAX_REJECTED_RETRIES
                                    attempts += Spo2Attempt(now, lastFailure, retryAtMs = if (again) now + Spo2Schedule.RETRY_MINUTES * MINUTE else null, waitedS = waitedS)
                                    if (again) {
                                        // A completed reading that didn't match the heart rate: the arm was still, so a try
                                        // soon is likely to work; it used to wait for the next slot, an hour later.
                                        store.lastSpo2Ms = 0
                                        store.spo2RetrySlot = Spo2Schedule.slot(now, every)
                                        store.spo2Retries = retries + 1
                                        BackgroundMonitoring.scheduleVitalsRetry(applicationContext, Spo2Schedule.RETRY_MINUTES.toLong())
                                        HLog.i(TAG, "SpO2 trying again in ${Spo2Schedule.RETRY_MINUTES} min")
                                    } else {
                                        store.lastSpo2Ms = now
                                        store.spo2Retries = 0
                                    }
                                } else {
                                    store.lastSpo2Ms = now
                                    store.spo2Retries = 0
                                    firstSample = first
                                    spo2Source = source
                                }
                            }
                        }
                    }
                }
            } finally {
                wrist.stop()
            }
        }

        val first = firstSample
        val source = spo2Source
        if (first != null && source != null) {
            // Low: check again (twice at most, while it stays low), without holding BackgroundSensors.lock or motion sensors during waits.
            val rechecks = mutableListOf<Spo2Sample>()
            var last: Spo2Sample = first
            var missed = 0
            while (monitor.needsRecheck(last.percent, settings, store.vitals, last.tsMs) && rechecks.size < VitalsMonitor.RECHECKS) {
                delay(RECHECK_DELAY_MS)
                if (blockedForSensors()) {
                    HLog.i(TAG, "SpO2 re-check skipped: watch state changed")
                    break
                }
                // One re-check without a result gets one more go: low readings were dropped unconfirmed.
                val nextAttempt = measureSpo2UnderLock(source, context, System.currentTimeMillis()) ?: break
                val nextSample = nextAttempt.sample
                if (nextSample == null) {
                    if (++missed <= 1) continue else break
                }
                last = nextSample
                rechecks += last
            }
            // A low reading with a normal re-check was an artefact; without a re-check result it counts as it is.
            val currentVitals = store.vitals
            val kept = Spo2Schedule.confirmed(first, rechecks) { monitor.needsRecheck(it, settings, currentVitals, first.tsMs) }
            if (rechecks.isEmpty() && monitor.needsRecheck(first.percent, settings, currentVitals, first.tsMs)) {
                HLog.i(TAG, "SpO2 ${first.percent} low, no re-check result: kept as it is")
            }
            spo2Samples += kept
            attempts += Spo2Attempt(now, Spo2Outcome.MEASURED, percent = kept.last().percent, waitedS = waitedS)
            var spo2Alert: HealthAlert? = null
            store.updateVitals { latest ->
                val res = monitor.onSpo2(latest, kept.first(), kept.drop(1), settings)
                spo2Alert = res.second
                res.first
            }
            spo2Alert?.let { alerts += it }
        }

        var nightAlerts: List<HealthAlert> = emptyList()
        val history = store.updateVitals { latest ->
            val res = monitor.afterNight(
                latest,
                store.monitorState.history,
                settings,
                System.currentTimeMillis(),
                profile.profile.value?.age(),
            )
            nightAlerts = res.second
            res.first
        }
        alerts += nightAlerts
        val today = VitalsBaseline.dayOf(now, HrContext.REST, java.time.ZoneId.systemDefault())
        val limits = VitalsBaseline.limits(history, today, settings.alertSensitivity, profile.profile.value?.age(), settings.health.lung)
        output.enqueueBatch(HrBatch(UUID.randomUUID().toString(), emptyList(), spo2 = spo2Samples, skinTemp = temps, vitals = limits, spo2Attempts = attempts))
        alerts.forEach {
            output.enqueueAlert(it)
            output.notify(it)
        }
        HLog.i(TAG, "vitals: context=$context spo2=${spo2Samples.map { it.percent }} temp=${temps.map { it.skinC }} alerts=${alerts.map { it.vital }}")
        return Result.success()
    }

    /** Why the last [spo2] call gave nothing (for the phone's "today" card). */
    private var lastFailure = Spo2Outcome.NO_SIGNAL

    /** A SpO2 try that ended before measuring anything (charging, app open): sent on its own. */
    private suspend fun reportAttempt(attempt: Spo2Attempt) =
        output.enqueueBatch(HrBatch(UUID.randomUUID().toString(), emptyList(), spo2Attempts = listOf(attempt)))

    /**
     * One SpO2 measurement. The sensor's own judgement of movement decides (its "hold still"
     * hint): the wrist's accelerometer used to reject readings the sensor had accepted.
     */
    private suspend fun spo2(source: QuickSource, context: HrContext, at: Long, confirmation: Boolean, stopOnMove: Boolean = false): Spo2Sample? {
        val reading = VitalsMeasurer.measure(source, stopOnMove = stopOnMove)
        val r = reading.summary as? RecordSummary.Spo2 ?: run {
            HLog.i(TAG, "SpO2 no result after ${reading.tookMs / 1_000} s: ${reading.failure} (moved=${reading.moved})")
            lastFailure = if (reading.moved) Spo2Outcome.MOVING else Spo2Outcome.NO_SIGNAL
            return null
        }
        val recent = Spo2Schedule.recentBpm(store.latestHeartRate, store.lastPassiveHeartRateMs, System.currentTimeMillis())
        // The source marks a value the wrist moved just before (it hadn't settled); a movement earlier on doesn't count.
        val movedBefore = r.lowConfidence
        val quality = VitalsQuality.spo2Quality(r.percent, r.heartRate, recent, movedBefore)
        if (quality == VitalsQuality.Spo2Quality.REJECT) {
            HLog.i(TAG, "SpO2 ${r.percent} rejected (moved just before=$movedBefore hr=${r.heartRate}/${recent ?: "-"})")
            lastFailure = if (movedBefore) Spo2Outcome.MOVING else Spo2Outcome.REJECTED
            return null
        }
        val unsure = quality == VitalsQuality.Spo2Quality.LOW_CONFIDENCE
        if (unsure) HLog.i(TAG, "SpO2 ${r.percent} kept, low confidence (hr=${r.heartRate}/$recent)")
        return Spo2Sample(at, r.percent, context, confirmation, lowConfidence = unsure)
    }

    private suspend fun waitForStillArm(onDone: (Int) -> Unit): Boolean {
        val waitStart = SystemClock.elapsedRealtime()
        val stillSince = System.currentTimeMillis()
        // With the gyroscope: a turning wrist spoils a blood-oxygen reading too; stopped right after waiting.
        val arm = WristState(applicationContext, turns = true, motion = true).also { it.start() }
        try {
            while (true) {
                val waited = SystemClock.elapsedRealtime() - waitStart
                val step = Spo2Schedule.stillWait(arm.stillFor(System.currentTimeMillis(), stillSince), waited)
                if (step == Spo2Schedule.Wait.KEEP_WAITING) {
                    delay(1_000)
                    continue
                }
                onDone((waited / 1_000).toInt())
                return step == Spo2Schedule.Wait.MEASURE
            }
        } finally {
            arm.stop()
        }
    }

    private suspend fun measureSpo2UnderLock(source: QuickSource, context: HrContext, at: Long): Spo2AttemptBox? =
        BackgroundSensors.lock.withLock {
            if (blockedForSensors()) return@withLock null
            val wrist = WristState(applicationContext, turns = false, motion = false).also { it.start() }
            try {
                delay(1_500)
                if (wrist.offBody) {
                    HLog.i(TAG, "SpO2 re-check skipped: off the wrist")
                    return@withLock Spo2AttemptBox(null)
                }
                if (blockedForSensors()) return@withLock null
                BackgroundSensors.runPreemptible {
                    Spo2AttemptBox(spo2(source, context, at, confirmation = true))
                }
            } finally {
                wrist.stop()
            }
        }

    private fun allowed(metric: Metric) = PermissionPolicy.permissionsFor(metric, Build.VERSION.SDK_INT)
        .all { ContextCompat.checkSelfPermission(applicationContext, it) == PackageManager.PERMISSION_GRANTED }

    private fun battery(): Int = PowerGuard.batteryPercent(applicationContext) ?: 100

    private companion object {
        const val TAG = "Heartline/Vitals"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val NOT_WORN_MS = 60 * MINUTE
        const val RECHECK_DELAY_MS = 2 * MINUTE

        /** Rejected readings tried again within one slot (put-offs count too). */
        const val MAX_REJECTED_RETRIES = 2
    }
}
