// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.ecg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.model.EcgMetrics
import com.heartline.shared.model.EcgResult
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.ecg.EcgRunState
import com.heartline.wear.ecg.EcgSessionRunner
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.sensor.SyncScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EcgMeasureState {
    data object Idle : EcgMeasureState

    /**
     * [trace]: the last seconds of the filtered live signal (shown from the moment measuring starts,
     * even before the finger touches). [bpm]: live heart rate once a few seconds are recorded.
     */
    data class Measuring(
        val progress: Float,
        val secondsLeft: Int,
        val trace: FloatArray,
        val leadOff: Boolean,
        val waitingForTouch: Boolean = false,
        val bpm: Int? = null,
        /** Contact reported; the signal is settling and being checked before the countdown starts. */
        val arming: Boolean = false,
        /** Contact reported for a while but the signal still isn't a clean ECG: ask for a lighter, steadier touch. */
        val struggling: Boolean = false,
        /** Counting has started at least once (for the start haptic). */
        val started: Boolean = false,
        /** Total live samples so far: positions the sweep's write head. */
        val endIndex: Long = trace.size.toLong(),
    ) : EcgMeasureState

    data object Analyzing : EcgMeasureState

    data class Done(val id: String, val result: EcgResult, val averageBpm: Int?, val metrics: EcgMetrics? = null) : EcgMeasureState

    data class Failed(val problem: SensorProblem) : EcgMeasureState
}

/** Exposes [EcgSessionRunner] state and lifecycle controls to the ECG measurement flow. */
class EcgMeasureViewModel(
    private val runner: EcgSessionRunner,
) : ViewModel() {
    constructor(
        source: EcgSource,
        store: WatchRecordStore,
        sync: SyncScheduler,
        now: () -> Long = System::currentTimeMillis,
        uiIntervalMs: Long = 40,
        onResult: (EcgResult, Long) -> Unit = { _, _ -> },
    ) : this(EcgSessionRunner(source, store, sync, now, uiIntervalMs, onResult))

    private val mutable = MutableStateFlow<EcgMeasureState>(EcgMeasureState.Idle)
    val state: StateFlow<EcgMeasureState> = mutable.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            runner.run(
                onState = { mutable.value = it.toUi() },
                resultNote = { mutable.value.toString() },
            )
        }
    }

    fun cancel() {
        job?.cancel()
        mutable.value = EcgMeasureState.Idle
    }

    fun reset() {
        mutable.value = EcgMeasureState.Idle
    }

    private fun EcgRunState.toUi(): EcgMeasureState = when (this) {
        EcgRunState.Idle -> EcgMeasureState.Idle
        is EcgRunState.Measuring -> EcgMeasureState.Measuring(
            progress = progress,
            secondsLeft = secondsLeft,
            trace = trace,
            leadOff = leadOff,
            waitingForTouch = waitingForTouch,
            bpm = bpm,
            arming = arming,
            struggling = struggling,
            started = started,
            endIndex = endIndex,
        )
        EcgRunState.Analyzing -> EcgMeasureState.Analyzing
        is EcgRunState.Done -> EcgMeasureState.Done(id, result, averageBpm, metrics)
        is EcgRunState.Failed -> EcgMeasureState.Failed(problem)
    }
}
