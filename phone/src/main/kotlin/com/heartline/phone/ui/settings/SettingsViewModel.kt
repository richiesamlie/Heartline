// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.export.DataExporter
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.sync.PhoneSyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

class SettingsViewModel(
    private val repository: RecordRepository,
    private val heart: HeartRepository,
    private val bp: BpRepository,
    private val settings: SettingsRepository,
    private val sync: PhoneSyncEngine,
    private val onApplied: (MonitorSettings) -> Unit = {},
) : ViewModel() {
    val monitor: StateFlow<MonitorSettings> = settings.monitor.stateIn(viewModelScope, WHILE_SUBSCRIBED, MonitorSettings())

    val heartLimits: StateFlow<com.heartline.shared.hr.HeartLimits?> = settings.heartLimits.stateIn(viewModelScope, WHILE_SUBSCRIBED, null)

    val vitalsLimits: StateFlow<com.heartline.shared.vitals.VitalsLimits?> = settings.vitalsLimits.stateIn(viewModelScope, WHILE_SUBSCRIBED, null)

    val stressLimits: StateFlow<com.heartline.shared.stress.StressLimits?> = settings.stressLimits.stateIn(viewModelScope, WHILE_SUBSCRIBED, null)

    val sharing: StateFlow<SettingsRepository.SharingPrefs> = settings.sharing.stateIn(viewModelScope, WHILE_SUBSCRIBED, SettingsRepository.SharingPrefs())

    fun setAiPrompt(prompt: String?) = viewModelScope.launch { settings.setAiPrompt(prompt) }

    fun setAiAttachPdf(on: Boolean) = viewModelScope.launch { settings.setAiAttachPdf(on) }

    fun setReportName(choice: com.heartline.shared.profile.ReportName) = viewModelScope.launch { settings.setReportName(choice) }

    /** Saves the change here, pushes it to the watch and re-arms the phone reminders. */
    fun change(change: SettingChange) = viewModelScope.launch {
        val next = settings.update { change.applyTo(it) }
        sync.sendSettings(next)
        onApplied(next)
    }

    fun export(exporter: DataExporter, onReady: (Intent) -> Unit) = viewModelScope.launch {
        val file = withContext(Dispatchers.IO) { exporter.export(repository.all(), background = heart.backgroundAll()) }
        onReady(exporter.shareIntent(file))
    }

    /** The CSV under a chosen name, for the share sheet. */
    suspend fun exportFile(exporter: DataExporter, fileName: String) = withContext(Dispatchers.IO) {
        exporter.export(repository.all(), fileName, heart.backgroundAll())
    }

    fun deleteAll() = viewModelScope.launch {
        repository.deleteAll()
        heart.deleteAll()
        bp.deleteAll()
    }
}
