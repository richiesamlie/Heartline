// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.remote

import androidx.lifecycle.ViewModel
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.measure.RemoteMeasureLink
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.remote.RemoteMeasureReporter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

internal val DEFAULT_MONITOR_SETTINGS: StateFlow<MonitorSettings> = MutableStateFlow(MonitorSettings())
internal val EMPTY_REMOTE_CANCELS: SharedFlow<String> = MutableSharedFlow<String>().asSharedFlow()

internal fun WatchSettingsStore?.settingsFlow(): StateFlow<MonitorSettings> =
    this?.settings ?: DEFAULT_MONITOR_SETTINGS

internal fun RemoteMeasureCoordinator?.cancelsFlow(): SharedFlow<String> =
    this?.cancels ?: EMPTY_REMOTE_CANCELS

/** Exposes [RemoteMeasureCoordinator] and watch settings to the remote measurement flows. */
class RemoteMeasureViewModel(
    private val coordinator: RemoteMeasureCoordinator,
    settingsStore: WatchSettingsStore,
) : ViewModel() {
    val settings: StateFlow<MonitorSettings> = settingsStore.settings
    val cancels: SharedFlow<String> get() = coordinator.cancels
    val busy: Boolean get() = coordinator.busy

    fun isCancelled(sessionId: String): Boolean = coordinator.isCancelled(sessionId)

    fun reporter(link: RemoteMeasureLink): RemoteMeasureReporter = coordinator.reporter(link)

    fun logHidden(link: RemoteMeasureLink) = RemoteMeasureCoordinator.logHidden(link)
}
