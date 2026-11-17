// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.measure.MeasureSessionManager
import com.heartline.phone.measure.MeasureUi
import com.heartline.shared.model.Metric
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Exposes [MeasureSessionManager] and temperature unit preference to the remote-measure screen. */
class MeasureViewModel(
    private val manager: MeasureSessionManager,
    settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<MeasureUi?> = manager.state

    val fahrenheit: StateFlow<Boolean> = settings.monitor
        .map { it.temperatureFahrenheit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun start(metric: Metric) = manager.start(metric)

    fun cancel() = manager.cancel()

    fun retry() = manager.retry()

    fun dismiss() = manager.dismiss()

    fun watchRoute(metric: Metric): String = MeasureSessionManager.watchRoute(metric)
}
