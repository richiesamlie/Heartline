// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.bp

import com.heartline.logging.HLog
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpConfirmation
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.BpSessionHeader
import com.heartline.shared.bp.BpSessionInput
import com.heartline.shared.bp.BpSessionRecorder
import com.heartline.shared.bp.BpSessionStreams
import com.heartline.shared.bp.BpWindowSelector
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.HemodynamicState
import com.heartline.shared.bp.HemodynamicStateClassifier
import com.heartline.shared.bp.PpgFeatureVector
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.PpgWave
import com.heartline.shared.bp.PpgWaveChoice
import com.heartline.shared.bp.PreciseInput
import com.heartline.shared.bp.PulseRate
import com.heartline.shared.bp.RecentRhythm
import com.heartline.shared.dsp.PpgRepair
import com.heartline.shared.dsp.StreamingPpgFilter
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.Protocol
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.diag.RawCapture
import com.heartline.wear.sensor.BpAuxSensors
import com.heartline.wear.sensor.EcgChunk
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.ImuRecorder
import com.heartline.wear.sensor.MotionMeter
import com.heartline.wear.sensor.PpgSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.sensor.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Quick: PPG + motion, 20–60 s, no touch. Precise: finger on the key (ECG), with the arm-raise maneuver. */
enum class BpRunMode { QUICK, PRECISE }

/** What the user is asked to do during a precise recording (the arm-raise maneuver), by elapsed seconds. */
enum class BpRunPhase(val untilSeconds: Int) {
    REST(12),
    RAISE(18),
    HOLD_UP(20),
    LOWER(26),
    REST_AGAIN(34),
    ;

    companion object {
        const val TOTAL_SECONDS = 34

        fun at(seconds: Double): BpRunPhase = entries.firstOrNull { seconds < it.untilSeconds } ?: REST_AGAIN
    }
}

sealed interface BpRunState {
    data object Idle : BpRunState

    data object NeedsCalibration : BpRunState

    data object Preparing : BpRunState

    data class Measuring(
        val progress: Float,
        val secondsLeft: Int,
        val trace: FloatArray,
        val contact: Boolean,
        val bpm: Int? = null,
        val endIndex: Long = trace.size.toLong(),
        val settling: Boolean = false,
        val phase: BpRunPhase? = null,
    ) : BpRunState

    data class Done(
        val systolic: Int,
        val diastolic: Int,
        val pulse: Int,
        val category: BpCategory,
        val uncertainty: Int = 0,
        val beyondCalibration: Boolean = false,
        val confirmed: Boolean = false,
        val safety: BpSafety = BpSafety.NONE,
        val uncertaintyDia: Int = 0,
        val notValidated: Boolean = false,
        val ectopicBeats: Int = 0,
        val bodyState: HemodynamicState = HemodynamicState.STEADY,
        val channels: List<BpChannel> = emptyList(),
        val postureDiffers: Boolean = false,
        val wideRange: Boolean = false,
    ) : BpRunState

    data object OutOfRange : BpRunState

    data object Moving : BpRunState

    data class CalibrationRecorded(val round: Int) : BpRunState

    data class CalibrationRetry(val round: Int) : BpRunState

    data object PoorSignal : BpRunState

    data class Failed(val problem: SensorProblem) : BpRunState
}

/** Handle created before launching a session so early cancels or double-starts stay serialized. */
class PreparedBpSession internal constructor(
    internal val id: String,
    internal val log: BpSessionRecorder,
    internal val header: BpSessionHeader.() -> BpSessionHeader,
    internal val effective: BpRunMode,
    internal val capture: CaptureRequest?,
    internal val startedAt: Long,
) {
    @Volatile internal var finished = false
}

/**
 * Executes one blood-pressure measurement or calibration capture (algorithm 6): auxiliary
 * skin temperature/EDA, quick PPG or precise ECG+PPG stream collection, IMU movement check,
 * channel fusion, storage, and session log persistence.
 */
class BpSessionRunner(
    private val source: PpgSource,
    private val bpStore: WatchBpStore,
    private val records: WatchRecordStore,
    private val sync: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val minSeconds: Int = BpWindowSelector.WINDOW_SECONDS,
    private val uiIntervalMs: Long = 40,
    private val imu: ImuRecorder = ImuRecorder.NONE,
    private val ecg: EcgSource? = null,
    private val aux: BpAuxSensors = BpAuxSensors.NONE,
    private val heightCm: () -> Double? = { null },
    private val device: String = "",
    private val appVersion: String = "",
    private val maxSeconds: Int = BpWindowSelector.MAX_SECONDS,
    private val noContactTimeoutMs: Long = NO_CONTACT_TIMEOUT_MS,
    private val maxPreciseLeadOffSeconds: Int = MAX_PRECISE_LEAD_OFF_SECONDS,
) {
    /** The last reading stored (for the phone, when it started the measurement). */
    @Volatile
    var lastRecord: RecordMeta? = null
        private set

    val pendingCapture: StateFlow<CaptureRequest?> get() = bpStore.pendingCapture

    val preciseAvailable: Boolean
        get() = ecg != null && (bpStore.calibration.value?.timedPoints()?.count { it.first.patMs != null && it.first.featureFs == CalibrationPoint.PRECISE_FS } ?: 0) >= 2

    private var rawId = ""
    private var active: PreparedBpSession? = null

    fun isCalibrated(): Boolean = bpStore.calibration.value?.isValid(now()) == true

    fun prepare(mode: BpRunMode = BpRunMode.QUICK, calibrationSession: Boolean = false): PreparedBpSession? {
        val capture = if (calibrationSession) bpStore.currentCapture(now()) else null
        HLog.i(TAG, "BP start: ${capture?.let { "calibration round ${it.round}" } ?: "measure"} (calibrationSession=$calibrationSession)")
        if (capture == null && !isCalibrated()) {
            bpStore.calibration.value?.let { HLog.i(TAG, "BP needs calibration: ${BpPipeline.needsCalibrationReason(it, now())}") }
            return null
        }
        val effective = if (capture == null && mode == BpRunMode.PRECISE && preciseAvailable) BpRunMode.PRECISE else BpRunMode.QUICK
        val startedAt = now()
        val id = UUID.randomUUID().toString()
        val log = BpSessionRecorder(id, if (capture != null) "calibration" else "measure", startedAt, now)
        rawId = RawCapture.begin("bp_raw", mapOf("bpSession" to id))
        val header: BpSessionHeader.() -> BpSessionHeader = {
            copy(
                mode = if (effective == BpRunMode.PRECISE) BpSessionHeader.MODE_PRECISE else BpSessionHeader.MODE_QUICK,
                device = device,
                appVersion = appVersion,
                algorithm = ALGORITHM,
                calibrationRound = capture?.round,
            )
        }
        return PreparedBpSession(id, log, header, effective, capture, startedAt).also { active = it }
    }

    suspend fun run(session: PreparedBpSession, emit: (BpRunState) -> Unit) {
        try {
            record(session, emit)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finishUnfinished(session, "cancelled") }
            throw e
        } catch (e: Exception) {
            HLog.e(TAG, "BP session failed", e)
            finishUnfinished(session, "error: ${e.javaClass.simpleName}: ${e.message}")
            emit(BpRunState.Failed(SensorProblem.NOT_SUPPORTED))
        }
    }

    fun stopImu() {
        imu.stop()
    }

    private suspend fun finishUnfinished(session: PreparedBpSession, reason: String) {
        if (session.finished) return
        HLog.i(TAG, "BP session ${session.id} ended early: $reason")
        runCatching {
            imu.stop()
            imu.streams().all().forEach { session.log.attach(it) }
        }
        session.log.note("result", reason)
        finish(session.log, session.header)
    }

    private suspend fun record(session: PreparedBpSession, emit: (BpRunState) -> Unit) {
        val log = session.log
        val header = session.header
        val id = session.id
        log.event("aux.start")
        val temp = aux.skinTemp()
        temp?.let {
            log.stream(BpSessionStreams.SKIN_TEMP, *BpSessionStreams.SKIN_TEMP_COLUMNS)
                .append(now() * 1_000_000L, it.objectC.toFloat(), (it.ambientC ?: Double.NaN).toFloat(), 0f)
        }
        val eda = aux.skinConductance(EDA_SECONDS)
        eda?.let { log.stream(BpSessionStreams.EDA, *BpSessionStreams.EDA_COLUMNS).append(now() * 1_000_000L, it.toFloat()) }
        log.capability("skinTemp", (temp != null).toString())
        log.capability("eda", (eda != null).toString())
        log.value(BpSessionStreams.VALUE_HEIGHT, heightCm())
        imu.start()
        log.event("recording.start", session.effective.name)
        val recorded = if (session.effective == BpRunMode.PRECISE) recordPrecise(log, emit) else recordQuick(log, session.capture, emit)
        val movement = imu.stop()
        val streams = imu.streams()
        streams.all().forEach { log.attach(it) }
        log.event("recording.end")
        log.value("motion.sdAll", movement)
        val input = when (recorded) {
            is Recorded.Failure -> {
                finish(log, header)
                emit(recorded.state)
                return
            }
            is Recorded.Session -> recorded.input.copy(
                imu = streams,
                skinTempC = temp?.objectC,
                edaMicroSiemens = eda,
                heightCm = heightCm(),
                recentEcg = recentRhythm(),
            )
        }
        // Quick mode must be still; the precise maneuver moves the arm on purpose.
        if (session.effective == BpRunMode.QUICK) {
            val t = input.greenTimesNs
            val still = if (t != null && t.size > minSeconds * input.fs) streams.motionSd(t[t.size - minSeconds * input.fs], t.last()) else movement
            log.value("motion.sdWindow", still)
            if (still != null && still > MotionMeter.MAX_STILL) {
                HLog.i(TAG, "BP: moved during recording (${"%.2f".format(still)} m/s²)")
                log.note("result", "moving")
                finish(log, header)
                emit(BpRunState.Moving)
                return
            }
        }
        val endState = if (session.capture != null) {
            finishCalibration(session.capture, input, log, id).also { finish(log, header) }
        } else {
            finishMeasurement(input, session.startedAt, log, id, header)
        }
        emit(endState)
    }

    private sealed interface Recorded {
        data class Session(val input: BpSessionInput) : Recorded

        data class Failure(val state: BpRunState) : Recorded
    }

    private suspend fun recordQuick(log: BpSessionRecorder, capture: CaptureRequest?, emit: (BpRunState) -> Unit): Recorded {
        val fs = source.sampleRateHz
        val profile = bpStore.calibration.value?.profile
        val minimum = if (capture == null && profile?.atrialFibrillation == true) maxOf(minSeconds, profile.recordingSeconds) else minSeconds
        val maximum = maxOf(maxSeconds, minimum)
        val green = FloatList()
        val ir = FloatList()
        val times = LongList()
        var greenPoints = 0
        var greenOffPoints = 0
        var wave = PpgWave.GREEN
        var liveIr: Boolean? = null
        val ppgLog = log.stream(BpSessionStreams.PPG, *BpSessionStreams.PPG_COLUMNS)
        val live = LivePpg(fs)
        var lastUi = 0L
        var lastCheck = 0
        var stop = false
        var failure: SensorProblem? = null
        var lastContactAtMs = now()
        var timedOut = false
        val startNs = lastContactAtMs * 1_000_000L
        emit(BpRunState.Measuring(0f, minimum, FloatArray(0), contact = true))
        coroutineScope {
            val collectJob = launch {
                source.stream()
                    .catch { e -> failure = (e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED }
                    .takeWhile { !stop && !timedOut }
                    .collect { chunk ->
                        if (liveIr == null && chunk.points.isNotEmpty()) {
                            liveIr = chunk.ir != null && chunk.points.filter { !it.green.isNaN() || !it.ir.isNaN() }
                                .all { it.greenStatus == PpgWaveChoice.STATUS_OFF || it.green.isNaN() }
                        }
                        live.add(if (liveIr == true) chunk.ir ?: chunk.samples else chunk.samples)
                        if (chunk.points.isNotEmpty()) {
                            chunk.points.forEach { p ->
                                ppgLog.append(p.timestampMs * 1_000_000L, p.green, p.ir, p.red, p.greenStatus.toFloat(), p.irStatus.toFloat(), p.redStatus.toFloat())
                            }
                        } else {
                            chunk.samples.forEachIndexed { i, v ->
                                ppgLog.append(startNs + (ppgLog.size + i).toLong() * 1_000_000_000L / fs, v, Float.NaN, Float.NaN, if (chunk.contact) 0f else 1f, -1f, -1f)
                            }
                        }
                        val t = now()
                        if (chunk.contact) {
                            lastContactAtMs = t
                            val base = times.size
                            val sampled = chunk.points.filter {
                                !it.green.isNaN() || (chunk.ir != null && it.greenStatus == PpgWaveChoice.STATUS_OFF && !it.ir.isNaN())
                            }
                            greenPoints += sampled.size
                            greenOffPoints += sampled.count { it.greenStatus == PpgWaveChoice.STATUS_OFF || it.green.isNaN() }
                            chunk.samples.forEachIndexed { i, v ->
                                green.add(v)
                                ir.add(chunk.ir?.getOrNull(i) ?: Float.NaN)
                                times.add(sampled.getOrNull(i)?.timestampMs?.times(1_000_000L) ?: (startNs + (base + i).toLong() * 1_000_000_000L / fs))
                            }
                        } else if (noContactTimeoutMs > 0 && t - lastContactAtMs >= noContactTimeoutMs) {
                            timedOut = true
                            this@launch.cancel()
                            return@collect
                        }
                        val seconds = green.size / fs
                        if (seconds >= minimum && seconds >= lastCheck + CHECK_EVERY_SECONDS) {
                            lastCheck = seconds
                            val offShare = if (greenPoints == 0) 0.0 else greenOffPoints.toDouble() / greenPoints
                            if (wave == PpgWave.GREEN && offShare >= PpgWaveChoice.GREEN_OFF_SHARE) {
                                val g = green.toArray()
                                val i = ir.toArray()
                                wave = withContext(Dispatchers.Default) { PpgWaveChoice.choose(g, i, fs, offShare) }
                                if (wave == PpgWave.IR) {
                                    HLog.i(TAG, "BP pulse wave: infrared (green status -1 on ${"%.0f".format(offShare * 100)} % of points)")
                                    log.event("wave", "IR")
                                }
                            }
                            val snapshot = if (wave == PpgWave.IR) ir.toArray() else green.toArray()
                            stop = seconds >= maximum || withContext(Dispatchers.Default) { BpWindowSelector.shouldStop(snapshot, fs, minimum) }
                            if (stop) log.event("stop", "after ${seconds}s")
                        }
                        if (t - lastUi >= uiIntervalMs) {
                            lastUi = t
                            val settling = seconds >= minimum
                            emit(
                                BpRunState.Measuring(
                                    bpProgress(green.size.toFloat() / fs, minimum, maximum),
                                    if (settling) 0 else (minimum * fs - green.size + fs - 1) / fs,
                                    live.recent(3.0),
                                    chunk.contact,
                                    live.bpm(t),
                                    live.total,
                                    settling = settling,
                                ),
                            )
                        }
                    }
            }
            val watchdog = if (noContactTimeoutMs > 0) {
                launch {
                    val stepMs = minOf(noContactTimeoutMs, 250L).coerceAtLeast(10L)
                    while (collectJob.isActive) {
                        delay(stepMs)
                        if (collectJob.isActive && now() - lastContactAtMs >= noContactTimeoutMs) {
                            timedOut = true
                            collectJob.cancel()
                            break
                        }
                    }
                }
            } else {
                null
            }
            collectJob.join()
            watchdog?.cancel()
        }
        log.capability("ppg.ir", (ir.any { it.isFinite() }).toString())
        failure?.let {
            log.note("result", "failed $it")
            return Recorded.Failure(BpRunState.Failed(it))
        }
        if (timedOut) {
            HLog.i(TAG, "BP quick: no contact after ${noContactTimeoutMs}ms")
            log.event("timeout", "no contact after ${noContactTimeoutMs}ms")
            log.note("result", "no contact")
            return Recorded.Failure(BpRunState.PoorSignal)
        }
        val irArray = ir.toArray().takeIf { a -> a.count { it.isFinite() } > a.size * 0.9 }
        val offShare = if (greenPoints == 0) 0.0 else greenOffPoints.toDouble() / greenPoints
        val input = PpgWaveChoice.apply(BpSessionInput(green.toArray(), fs, times.toArray(), irArray), offShare)
        log.capability("ppg.wave", input.wave.name)
        log.value("ppg.greenOffShare", offShare)
        return Recorded.Session(input)
    }

    private suspend fun recordPrecise(log: BpSessionRecorder, emit: (BpRunState) -> Unit): Recorded {
        val source = ecg ?: return Recorded.Failure(BpRunState.Failed(SensorProblem.NOT_SUPPORTED))
        val fs = source.sampleRateHz
        val target = BpRunPhase.TOTAL_SECONDS * fs
        val liftDebounceSamples = LIFT_DEBOUNCE_MS * fs / 1_000
        val maxLeadOffSamples = maxPreciseLeadOffSeconds * fs
        val ecgValues = FloatList()
        val ppgValues = FloatList()
        val pendingBad = mutableListOf<EcgChunk>()
        var pendingBadSamples = 0
        var hasStarted = false
        var postStartBadSamples = 0
        var abandoned = false
        val ecgLog = log.stream(BpSessionStreams.ECG, *BpSessionStreams.ECG_COLUMNS)
        val live = LivePpg(fs)
        var firstNs: Long? = null
        var missingPpg = false
        var lastUi = 0L
        var phase: BpRunPhase? = null
        var failure: SensorProblem? = null
        fun resetAttempt() {
            ecgValues.clear()
            ppgValues.clear()
            pendingBad.clear()
            pendingBadSamples = 0
            firstNs = null
            phase = null
            missingPpg = false
        }
        emit(BpRunState.Measuring(0f, BpRunPhase.TOTAL_SECONDS, FloatArray(0), contact = false, phase = BpRunPhase.REST))
        source.stream()
            .catch { e -> failure = (e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED }
            .takeWhile { ecgValues.size < target && !abandoned }
            .collect { chunk ->
                val n = chunk.samples.size
                val lastNs = (chunk.timestampMs ?: now()) * 1_000_000L
                val bad = chunk.leadOff || chunk.saturated
                for (i in 0 until n) {
                    val t = lastNs - (n - 1 - i).toLong() * 1_000_000_000L / fs
                    ecgLog.append(t, chunk.samples[i], chunk.ppg?.getOrNull(i) ?: Float.NaN, if (bad) 1f else 0f)
                }
                if (bad) {
                    if (!hasStarted) {
                        emit(BpRunState.Measuring(0f, BpRunPhase.TOTAL_SECONDS, live.recent(3.0), contact = false, phase = BpRunPhase.REST))
                        return@collect
                    }
                    postStartBadSamples += n
                    if (maxLeadOffSamples > 0 && postStartBadSamples >= maxLeadOffSamples) {
                        log.event("contact.abandoned", "after $postStartBadSamples bad samples")
                        resetAttempt()
                        abandoned = true
                        return@collect
                    }
                    if (ecgValues.size > 0) {
                        pendingBad += chunk
                        pendingBadSamples += n
                        if (pendingBadSamples > liftDebounceSamples) {
                            log.event("contact.lost", "after ${ecgValues.size} samples; restarting")
                            resetAttempt()
                            emit(BpRunState.Measuring(0f, BpRunPhase.TOTAL_SECONDS, live.recent(3.0), contact = false, phase = BpRunPhase.REST))
                        }
                    } else {
                        emit(BpRunState.Measuring(0f, BpRunPhase.TOTAL_SECONDS, live.recent(3.0), contact = false, phase = BpRunPhase.REST))
                    }
                    return@collect
                }
                hasStarted = true
                postStartBadSamples = 0
                if (pendingBad.isNotEmpty()) {
                    val holdEcg = if (ecgValues.size > 0) ecgValues.last() else (chunk.samples.firstOrNull() ?: 0f)
                    for (b in pendingBad) {
                        val takeBad = minOf(b.samples.size, target - ecgValues.size)
                        for (i in 0 until takeBad) {
                            ecgValues.add(holdEcg)
                            ppgValues.add(PpgRepair.PLACEHOLDER)
                        }
                    }
                    pendingBad.clear()
                    pendingBadSamples = 0
                }
                if (chunk.ppg == null) missingPpg = true
                if (firstNs == null) firstNs = lastNs - (n - 1).toLong() * 1_000_000_000L / fs
                val take = minOf(n, target - ecgValues.size)
                for (i in 0 until take) {
                    ecgValues.add(chunk.samples[i])
                    ppgValues.add(chunk.ppg?.getOrNull(i) ?: Float.NaN)
                }
                chunk.ppg?.let { live.add(it.copyOf(take)) }
                val seconds = ecgValues.size.toDouble() / fs
                val current = BpRunPhase.at(seconds)
                if (current != phase) {
                    phase = current
                    log.event("phase", current.name)
                }
                val t = now()
                if (t - lastUi >= uiIntervalMs) {
                    lastUi = t
                    emit(
                        BpRunState.Measuring(
                            ecgValues.size.toFloat() / target,
                            (target - ecgValues.size + fs - 1) / fs,
                            live.recent(3.0),
                            !chunk.leadOff,
                            live.bpm(t),
                            live.total,
                            phase = current,
                        ),
                    )
                }
            }
        failure?.let {
            log.note("result", "failed $it")
            return Recorded.Failure(BpRunState.Failed(it))
        }
        if (abandoned) {
            log.note("result", "precise: abandoned after lead-off")
            return Recorded.Failure(BpRunState.PoorSignal)
        }
        log.capability("ecg.ppg", (!missingPpg).toString())
        val start = firstNs
        if (ecgValues.size < BpWindowSelector.WINDOW_SECONDS * fs / 2 || start == null || missingPpg) {
            log.note("result", "precise: ${ecgValues.size} samples, ppg ${!missingPpg}")
            return Recorded.Failure(BpRunState.PoorSignal)
        }
        log.note(BpSessionStreams.NOTE_PRECISE_START_NS, start.toString())
        val ppg = ppgValues.toArray()
        val raised = imu.streams().raisedWindow()
        raised?.let {
            log.note(BpSessionStreams.NOTE_RAISED_FROM, it.first.toString())
            log.note(BpSessionStreams.NOTE_RAISED_TO, it.last.toString())
        }
        val times = LongArray(ppg.size) { start + it.toLong() * 1_000_000_000L / fs }
        return Recorded.Session(BpSessionInput(ppg, fs, times, precise = PreciseInput(ecgValues.toArray(), ppg, fs, start, raised)))
    }

    private suspend fun finishCalibration(capture: CaptureRequest, input: BpSessionInput, log: BpSessionRecorder, sessionId: String): BpRunState {
        val channels = withContext(Dispatchers.Default) { BpPipeline.capture(input) }
        log.features("green", channels?.features)
        log.features("ir", channels?.irFeatures)
        log.value("bcg.pttMs", channels?.bcgPttMs)
        log.value("precise.patMs", channels?.patMs)
        log.value("precise.pepMs", channels?.pepMs)
        log.value("precise.pttMs", channels?.pttMs)
        HLog.i(
            TAG,
            "BP calibration round ${capture.round}: wave=${input.wave} green=${channels?.features?.let { summary(it) }} ir=${channels?.irFeatures?.let { summary(it) }} " +
                "bcgPttMs=${channels?.bcgPttMs} patMs=${channels?.patMs} fs=${channels?.fs}",
        )
        val features = channels?.features
        if (channels == null || features == null || features.quality < BpEstimator.MIN_QUALITY || features.beats < BpEstimator.MIN_BEATS) {
            log.note("result", "poor signal")
            return BpRunState.PoorSignal
        }
        if (!calibrationGrade(features)) {
            HLog.i(TAG, "BP calibration round ${capture.round} taken again: not steady (${summary(features)})")
            log.note("result", "calibration round not steady: quality=${features.quality} beats=${features.beats} hrSlope=${features.hrSlopeBpmPerS} amplitudeTrend=${features.amplitudeTrend}")
            return BpRunState.CalibrationRetry(capture.round)
        }
        val result = CaptureResult(
            UUID.randomUUID().toString(),
            capture.captureId,
            capture.round,
            features,
            channels.ppg.takeIf { channels.fs == BpCalibration.PPG_FS },
            channels.gravity,
            channels,
            sessionId,
        )
        records.enqueueMessage(result.id, Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(result).encodeToByteArray())
        sync.schedule()
        bpStore.setPendingCapture(null)
        log.note("result", "calibration round ${capture.round}")
        return BpRunState.CalibrationRecorded(capture.round)
    }

    private suspend fun finishMeasurement(
        input: BpSessionInput,
        startedAt: Long,
        log: BpSessionRecorder,
        sessionId: String,
        header: BpSessionHeader.() -> BpSessionHeader,
    ): BpRunState {
        val result = withContext(Dispatchers.Default) { BpPipeline.run(bpStore.calibration.value, input, now(), bpStore.history, log) }
        val outcome = result.outcome
        HLog.i(TAG, "BP measurement: samples=${input.green.size} outcome=$outcome")
        return when (outcome) {
            BpOutcome.NeedsCalibration -> BpRunState.NeedsCalibration.also { finish(log, header) }
            BpOutcome.PoorSignal -> BpRunState.PoorSignal.also { finish(log, header) }
            is BpOutcome.OutOfRange -> BpRunState.OutOfRange.also { finish(log, header) }
            is BpOutcome.Ok -> {
                val e = outcome.estimate
                val t = now()
                val previous = bpStore.lastReading
                val confirmed = previous != null && BpConfirmation.confirms(previous.toEstimate(), previous.atMs, e, t)
                bpStore.lastReading = LastBpReading.of(e, t)
                val features = result.features
                if (!e.beyondCalibration && e.state.steady && features != null) bpStore.addHistory(features)
                val wave = BpPipeline.extractWave(input, result.window)
                val recordId = UUID.randomUUID().toString()
                val meta = RecordMeta(
                    recordId,
                    RecordKind.BLOOD_PRESSURE,
                    startedAt,
                    (t - startedAt).coerceAtLeast(0),
                    input.fs,
                    wave.size,
                    RecordSummary.BloodPressure(
                        e.systolic,
                        e.diastolic,
                        e.pulse,
                        e.uncertaintySys,
                        algorithm = ALGORITHM,
                        beyondCalibration = e.beyondCalibration,
                        confirmed = confirmed,
                        channels = e.channels.joinToString(",") { it.channel.name },
                        bodyState = e.state.state.name,
                        rangeOnly = e.wideRange,
                        mode = if (input.precise != null) BpSessionHeader.MODE_PRECISE else BpSessionHeader.MODE_QUICK,
                        sessionId = sessionId,
                        heartRateDominated = e.heartRateDominated,
                    ),
                )
                records.add(meta, wave)
                lastRecord = meta
                finish(log) { header().copy(recordId = recordId) }
                BpRunState.Done(
                    e.systolic,
                    e.diastolic,
                    e.pulse,
                    BpCategory.of(e.systolic, e.diastolic),
                    e.uncertaintySys,
                    e.beyondCalibration,
                    confirmed,
                    e.safety,
                    uncertaintyDia = e.uncertaintyDia,
                    notValidated = e.notValidated,
                    ectopicBeats = e.ectopicBeats,
                    bodyState = e.state.state,
                    channels = e.channels.map { it.channel },
                    postureDiffers = e.postureDiffers,
                    wideRange = e.wideRange,
                )
            }
        }
    }

    private suspend fun recentRhythm(): RecentRhythm? = runCatching {
        val since = now() - RECENT_ECG_MS
        records.history.first().firstOrNull { it.kind == RecordKind.ECG && it.startedAtMs >= since }
            ?.let { (it.summary as? RecordSummary.Ecg)?.result }
            ?.let {
                when (it) {
                    EcgResult.AFIB_SIGNS -> RecentRhythm.AF
                    EcgResult.SINUS_RHYTHM -> RecentRhythm.SINUS
                    else -> null
                }
            }
    }.getOrNull()

    private suspend fun finish(log: BpSessionRecorder, header: BpSessionHeader.() -> BpSessionHeader) {
        val session = log.build(header)
        HLog.i(RAW_TAG, "session ${session.header.id}: ${session.streams.joinToString { "${it.name}=${it.size}@${"%.1f".format(it.rateHz())}Hz" }} values=${session.header.values.size}")
        session.header.values.entries.sortedBy { it.key }.chunked(12).forEach { chunk -> HLog.d(RAW_TAG, chunk.joinToString { "${it.key}=${"%.4g".format(it.value)}" }) }
        session.header.notes.entries.sortedBy { it.key }.forEach { (k, v) -> HLog.d(RAW_TAG, "note $k=$v") }
        session.header.events.forEach { e -> HLog.d(RAW_TAG, "event +${e.tMs}ms ${e.type}${if (e.detail.isEmpty()) "" else " ${e.detail}"}") }
        runCatching { records.addSession(session) }.onFailure { HLog.w(RAW_TAG, "session not stored", it) }
        active?.takeIf { it.id == session.header.id }?.finished = true
        RawCapture.end(rawId, notes = mapOf("bpSession" to session.header.id, "result" to (session.header.notes["result"] ?: "")))
        RawCapture.saveBpSession(session)
        sync.schedule()
    }

    private fun summary(f: PpgFeatureVector) =
        "hr=${"%.1f".format(f.heartRateBpm)} q=${"%.2f".format(f.quality)} beats=${f.beats} inverted=${f.inverted} " +
            "upstroke=${f.upstrokeMs} width50=${f.width50Ms} area=${"%.2f".format(f.areaRatio)} " +
            "hrSlope=${"%.2f".format(f.hrSlopeBpmPerS)} ampTrend=${"%.2f".format(f.amplitudeTrend)}"

    private class FloatList {
        private var a = FloatArray(4096)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        fun any(p: (Float) -> Boolean) = (0 until size).any { p(a[it]) }

        fun clear() {
            size = 0
        }

        fun last(): Float = a[size - 1]

        fun toArray() = a.copyOf(size)
    }

    private class LongList {
        private var a = LongArray(4096)
        var size = 0
            private set

        fun add(v: Long) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        fun toArray() = a.copyOf(size)
    }

    private class LivePpg(private val fs: Int) {
        private val filter = StreamingPpgFilter(fs)
        private val ring = FloatArray(fs * 8)
        var total = 0L
            private set
        private var inverted: Boolean? = null
        private var lastBpmAt = 0L
        private var bpm: Int? = null

        fun add(samples: FloatArray) {
            filter.process(samples).forEach {
                ring[(total % ring.size).toInt()] = it
                total++
            }
            if (inverted == null && total >= fs * 4) inverted = PpgFeatures.isInverted(raw(4.0))
        }

        private fun raw(seconds: Double): FloatArray {
            val n = (seconds * fs).toInt().coerceAtMost(minOf(total, ring.size.toLong()).toInt())
            return FloatArray(n) { k -> ring[((total - n + k) % ring.size).toInt()] }
        }

        fun recent(seconds: Double): FloatArray = raw(seconds).let { x -> if (inverted == true) FloatArray(x.size) { -x[it] } else x }

        fun bpm(nowMs: Long): Int? {
            if (nowMs - lastBpmAt >= 1_000) {
                lastBpmAt = nowMs
                bpm = PulseRate.bpm(recent(6.0), fs) ?: bpm
            }
            return bpm
        }
    }

    internal companion object {
        private const val TAG = "Heartline/BP"
        private const val RAW_TAG = "Heartline/BpRaw"
        private const val ALGORITHM = 6
        private const val CHECK_EVERY_SECONDS = 2
        private const val EDA_SECONDS = 5
        private const val RECENT_ECG_MS = 30 * 24 * 3_600_000L
        const val NO_CONTACT_TIMEOUT_MS = 15_000L
        const val LIFT_DEBOUNCE_MS = 300
        const val MAX_PRECISE_LEAD_OFF_SECONDS = 10

        private fun calibrationGrade(f: PpgFeatureVector) =
            f.quality >= 0.7 &&
                f.beats >= 15 &&
                kotlin.math.abs(f.hrSlopeBpmPerS) <= HemodynamicStateClassifier.TRANSIENT_HR_SLOPE &&
                kotlin.math.abs(f.amplitudeTrend) <= HemodynamicStateClassifier.TRANSIENT_AMPLITUDE
    }
}

internal fun bpProgress(seconds: Float, minimum: Int, maximum: Int): Float {
    if (seconds < minimum) return 0.8f * seconds / minimum
    val span = (maximum - minimum).coerceAtLeast(1)
    return (0.8f + 0.18f * (1f - kotlin.math.exp(-2.5f * (seconds - minimum) / span))).coerceAtMost(0.98f)
}
