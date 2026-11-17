// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.logging.HLog
import com.heartline.shared.design.Accent
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.measure.RemoteMeasureLink
import com.heartline.shared.model.Metric
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.profile.Baseline
import com.heartline.shared.profile.PersonalBaseline
import com.heartline.shared.sync.MeasureProblem
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.link.WatchCommandBus
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.nav.WatchRoutes
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.setup.GateState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Top-level appearance, identity, baseline context, and command handling for [HeartlineWearApp]. */
data class WatchAppUi(
    val accent: Accent = Accent.entries.first(),
    val userName: String? = null,
    val celebrations: Boolean = true,
    val haptics: Boolean = true,
    val baselines: Map<Metric, Baseline> = emptyMap(),
)

sealed interface PendingCommandAction {
    data object RecheckSetup : PendingCommandAction

    data class OpenRoute(val route: String) : PendingCommandAction
}

class WatchAppViewModel(
    private val bus: WatchCommandBus,
    private val coordinator: RemoteMeasureCoordinator,
    private val settingsStore: WatchSettingsStore,
    profiles: WatchProfileStore,
    records: WatchRecordStore,
    private val syncMonitoring: suspend (MonitorSettings) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val pendingRoute: StateFlow<String?> = bus.pending

    val ui: StateFlow<WatchAppUi> = combine(
        settingsStore.settings,
        profiles.profile,
        records.history,
    ) { monitor, profile, history ->
        WatchAppUi(
            accent = monitor.accent,
            userName = profile?.displayName?.takeIf { monitor.showNameOnWatch && it.isNotBlank() },
            celebrations = monitor.celebrations,
            haptics = monitor.haptics,
            // The newest record is usually the one on screen: judge it against the ones before.
            baselines = PersonalBaseline.all(history, now(), exclude = history.firstOrNull()?.id),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchAppUi())

    fun applyMonitoring() {
        viewModelScope.launch { syncMonitoring(settingsStore.settings.value) }
    }

    /** Turns down a remote measure link when setup or sensors are not ready. */
    fun handleGateBlocker(route: String?, gateState: GateState) {
        val link = route?.let { RemoteMeasureLink.parse(EntryLinks.parse(it).route) } ?: return
        val problem = when (gateState) {
            is GateState.PhoneProblem, is GateState.SetupIncomplete -> MeasureProblem.NEEDS_SETUP
            GateState.NeedsPermissions -> MeasureProblem.PERMISSION
            is GateState.SensorIssue -> when (gateState.problem) {
                SensorProblem.SDK_POLICY -> MeasureProblem.SDK_POLICY
                SensorProblem.PERMISSION -> MeasureProblem.PERMISSION
                else -> MeasureProblem.SENSOR
            }
            else -> null
        } ?: return
        HLog.i(OPEN_TAG, "turned down ${link.metric} ${link.sessionId}: $problem")
        coordinator.reject(link, problem)
        bus.take(route)
    }

    /** Consumes [route] once ready, returning the action the Compose navigator should perform. */
    fun consumeReadyRoute(route: String?, gateState: GateState): PendingCommandAction? {
        val target = route ?: return null
        if (target == WatchRoutes.SETUP) {
            bus.take(target)
            return PendingCommandAction.RecheckSetup
        }
        if (gateState !is GateState.Ready) {
            HLog.i(OPEN_TAG, "waiting for setup checks ($gateState) to open $target")
            return null
        }
        bus.take(target)
        val link = RemoteMeasureLink.parse(EntryLinks.parse(target).route)
        if (link != null && (coordinator.isOpen(link.sessionId) || coordinator.isCancelled(link.sessionId))) {
            HLog.i(
                OPEN_TAG,
                if (coordinator.isCancelled(link.sessionId)) "already cancelled ${link.sessionId}" else "already measuring ${link.sessionId}",
            )
            return null
        }
        HLog.i(OPEN_TAG, "opening $target")
        return PendingCommandAction.OpenRoute(target)
    }

    private companion object {
        const val OPEN_TAG = "Heartline/Open"
    }
}
