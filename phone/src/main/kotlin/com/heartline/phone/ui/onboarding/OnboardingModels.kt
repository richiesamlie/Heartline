// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.SettingsRepository
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.sync.PhoneSyncEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnboardingViewModel(private val settings: SettingsRepository) : ViewModel() {
    val onboarded: StateFlow<Boolean?> = settings.onboarded.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null while loading; the accepted terms version otherwise (0 = never accepted). */
    val acceptedTerms: StateFlow<Int?> = settings.acceptedTermsVersion.map<Int, Int?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun finish() = viewModelScope.launch { settings.setOnboarded() }

    fun acceptTerms() = viewModelScope.launch { settings.acceptTerms() }

    /** Null while loading; the monitoring setup version gone through (0 = never). */
    val monitoringSetup: StateFlow<Int?> = settings.monitoringSetupVersion.map<Int, Int?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * The monitoring setup (first run, once after an update, and Settings → Your health answers):
 * saves the chosen settings, sends them to the watch and marks the setup done.
 */
class MonitoringSetupViewModel(
    private val settings: SettingsRepository,
    private val sync: PhoneSyncEngine,
    private val onApplied: (MonitorSettings) -> Unit = {},
) : ViewModel() {
    /** Null while loading. */
    val current: StateFlow<MonitorSettings?> = settings.monitor.map<MonitorSettings, MonitorSettings?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun finish(result: MonitorSettings, onDone: () -> Unit = {}) = viewModelScope.launch {
        val next = settings.update { result }
        settings.setMonitoringSetupDone()
        sync.sendSettings(next)
        onApplied(next)
        onDone()
    }
}
