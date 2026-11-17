// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.bp

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.BpWindowSelector
import com.heartline.shared.bp.HemodynamicState
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.measure.RemoteMeasureLink
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.SetupTarget
import com.heartline.wear.bp.BpRunMode
import com.heartline.wear.bp.BpRunPhase
import com.heartline.wear.bp.BpRunState
import com.heartline.wear.bp.BpSessionRunner
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.link.PhoneOpener
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.sensor.BpAuxSensors
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.ImuRecorder
import com.heartline.wear.sensor.PpgSource
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.sensor.SyncScheduler
import com.heartline.wear.ui.remote.cancelsFlow
import com.heartline.wear.ui.remote.settingsFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Quick: PPG + motion, 20–60 s, no touch. Precise: finger on the key (ECG), with the arm-raise maneuver. */
enum class BpMode { QUICK, PRECISE }

/** What the user is asked to do during a precise recording (the arm-raise maneuver), by elapsed seconds. */
enum class BpPhase(val untilSeconds: Int) {
    REST(12),
    RAISE(18),
    HOLD_UP(20),
    LOWER(26),
    REST_AGAIN(34),
    ;

    companion object {
        const val TOTAL_SECONDS = 34

        fun at(seconds: Double): BpPhase = entries.firstOrNull { seconds < it.untilSeconds } ?: REST_AGAIN
    }
}

sealed interface BpState {
    data object Idle : BpState

    data object NeedsCalibration : BpState

    /** Reading skin temperature / conductance before the main recording. */
    data object Preparing : BpState

    /**
     * [trace]: filtered, upright PPG for the sweep; [bpm]: live pulse. [settling]: the minimum
     * time is over and the recording continues until the pulse is steady. [phase]: the maneuver
     * step in precise mode; [contact]: in precise mode, the finger on the key.
     */
    data class Measuring(
        val progress: Float,
        val secondsLeft: Int,
        val trace: FloatArray,
        val contact: Boolean,
        val bpm: Int? = null,
        val endIndex: Long = trace.size.toLong(),
        val settling: Boolean = false,
        val phase: BpPhase? = null,
    ) : BpState

    /**
     * [beyondCalibration]: an extrapolation (shown, with its wider ±). [confirmed]: a second
     * reading within 10 minutes agreed. [safety]: very high or low, check with a cuff.
     * Algorithm 6: always one number with its ±; [bodyState] and [channels] say what it rests
     * on; [notValidated]: a condition (pregnancy) cuffless readings aren't validated for;
     * [ectopicBeats]: premature beats that were left out.
     */
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
        /** The ± is too wide for a category: the number and ± are shown without one. */
        val wideRange: Boolean = false,
    ) : BpState {
        val needsConfirming get() = (beyondCalibration || safety != BpSafety.NONE) && !confirmed
    }

    /** The recording isn't a trustworthy pulse wave (never used for a real change in pressure). */
    data object OutOfRange : BpState

    /** The arm moved during the recording. */
    data object Moving : BpState

    data class CalibrationRecorded(val round: Int) : BpState

    /** The calibration round wasn't steady or clean enough to calibrate on: take it again. */
    data class CalibrationRetry(val round: Int) : BpState

    data object PoorSignal : BpState

    data class Failed(val problem: SensorProblem) : BpState
}

/** Exposes [BpSessionRunner] state, lifecycle, and actions to the Wear BP measurement UI. */
class BpMeasureViewModel(
    private val runner: BpSessionRunner,
    private val gateway: SensorGateway? = null,
    private val phone: PhoneOpener? = null,
    private val settingsStore: WatchSettingsStore? = null,
    private val coordinator: RemoteMeasureCoordinator? = null,
) : ViewModel() {
    constructor(
        source: PpgSource,
        bpStore: WatchBpStore,
        records: WatchRecordStore,
        sync: SyncScheduler,
        now: () -> Long = System::currentTimeMillis,
        minSeconds: Int = BpWindowSelector.WINDOW_SECONDS,
        uiIntervalMs: Long = 40,
        imu: ImuRecorder = ImuRecorder.NONE,
        ecg: EcgSource? = null,
        aux: BpAuxSensors = BpAuxSensors.NONE,
        heightCm: () -> Double? = { null },
        device: String = "",
        appVersion: String = "",
        maxSeconds: Int = BpWindowSelector.MAX_SECONDS,
    ) : this(
        BpSessionRunner(
            source = source,
            bpStore = bpStore,
            records = records,
            sync = sync,
            now = now,
            minSeconds = minSeconds,
            uiIntervalMs = uiIntervalMs,
            imu = imu,
            ecg = ecg,
            aux = aux,
            heightCm = heightCm,
            device = device,
            appVersion = appVersion,
            maxSeconds = maxSeconds,
        ),
    )

    private val mutable = MutableStateFlow<BpState>(BpState.Idle)
    val state: StateFlow<BpState> = mutable.asStateFlow()
    private var job: Job? = null

    val lastRecord: RecordMeta? get() = runner.lastRecord

    val pendingCapture: StateFlow<CaptureRequest?> get() = runner.pendingCapture

    val preciseAvailable: Boolean get() = runner.preciseAvailable

    fun checkReady() {
        if (!runner.isCalibrated()) mutable.value = BpState.NeedsCalibration
    }

    fun start(mode: BpMode = BpMode.QUICK, calibrationSession: Boolean = false) {
        val recording = mutable.value is BpState.Preparing || mutable.value is BpState.Measuring
        if (job?.isActive == true && recording) return
        val session = runner.prepare(mode.toRunMode(), calibrationSession)
        if (session == null) {
            mutable.value = BpState.NeedsCalibration
            return
        }
        // Set before the job starts, so a second start() arriving at once is refused by the check above.
        mutable.value = BpState.Preparing
        job = viewModelScope.launch {
            runner.run(session) { mutable.value = it.toUi() }
        }
    }

    fun cancel() {
        if (job?.isActive == true) runner.stopImu()
        job?.cancel()
        mutable.value = BpState.Idle
    }

    /** The screen is gone for good: nothing may keep listening to the sensors. */
    override fun onCleared() {
        if (job?.isActive == true) cancel()
        runner.stopImu()
        super.onCleared()
    }

    fun reset() {
        mutable.value = BpState.Idle
    }

    private fun BpMode.toRunMode(): BpRunMode = when (this) {
        BpMode.QUICK -> BpRunMode.QUICK
        BpMode.PRECISE -> BpRunMode.PRECISE
    }

    private fun BpRunState.toUi(): BpState = when (this) {
        BpRunState.Idle -> BpState.Idle
        BpRunState.NeedsCalibration -> BpState.NeedsCalibration
        BpRunState.Preparing -> BpState.Preparing
        is BpRunState.Measuring -> BpState.Measuring(
            progress = progress,
            secondsLeft = secondsLeft,
            trace = trace,
            contact = contact,
            bpm = bpm,
            endIndex = endIndex,
            settling = settling,
            phase = phase?.let { p -> BpPhase.entries.first { it.name == p.name } },
        )
        is BpRunState.Done -> BpState.Done(
            systolic = systolic,
            diastolic = diastolic,
            pulse = pulse,
            category = category,
            uncertainty = uncertainty,
            beyondCalibration = beyondCalibration,
            confirmed = confirmed,
            safety = safety,
            uncertaintyDia = uncertaintyDia,
            notValidated = notValidated,
            ectopicBeats = ectopicBeats,
            bodyState = bodyState,
            channels = channels,
            postureDiffers = postureDiffers,
            wideRange = wideRange,
        )
        BpRunState.OutOfRange -> BpState.OutOfRange
        BpRunState.Moving -> BpState.Moving
        is BpRunState.CalibrationRecorded -> BpState.CalibrationRecorded(round)
        is BpRunState.CalibrationRetry -> BpState.CalibrationRetry(round)
        BpRunState.PoorSignal -> BpState.PoorSignal
        is BpRunState.Failed -> BpState.Failed(problem)
    }

    val settings: StateFlow<MonitorSettings> get() = settingsStore.settingsFlow()
    val remoteCancels: SharedFlow<String> get() = coordinator.cancelsFlow()
    private val phoneOpenedState = MutableStateFlow<Boolean?>(null)
    val phoneOpened: StateFlow<Boolean?> = phoneOpenedState.asStateFlow()

    fun isRemoteCancelled(sessionId: String): Boolean = coordinator?.isCancelled(sessionId) == true

    fun setRecording(on: Boolean) {
        coordinator?.recording(on)
    }

    fun logRemoteHidden(link: RemoteMeasureLink) {
        RemoteMeasureCoordinator.logHidden(link)
    }

    fun resolveSensors(activity: Activity) {
        gateway?.resolve(activity)
    }

    fun openCalibrationOnPhone(onOpened: () -> Unit) {
        val opener = phone ?: return
        viewModelScope.launch {
            val ok = opener.open(SetupTarget.BP_CALIBRATION)
            phoneOpenedState.value = ok
            if (ok) onOpened()
        }
    }
}

/**
 * The shown progress of a blood-pressure recording: up to 80 % over the minimum time, then on
 * towards 98 % while it waits for a steady pulse (it may stop at any point after the minimum), so
 * it never reads 100 % while still recording.
 */
internal fun bpProgress(seconds: Float, minimum: Int, maximum: Int): Float =
    com.heartline.wear.bp.bpProgress(seconds, minimum, maximum)
