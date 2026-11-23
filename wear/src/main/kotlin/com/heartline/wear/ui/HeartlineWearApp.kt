// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.heartline.logging.HLog
import com.heartline.shared.AppInfo
import com.heartline.shared.model.Metric
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.sync.SetupTarget
import com.heartline.wear.R
import com.heartline.wear.monitor.BackgroundMonitoring
import com.heartline.wear.nav.WatchRoutes
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.sensor.SetupPermissions
import com.heartline.wear.ui.bp.BpFlow
import com.heartline.wear.ui.components.LocalBaselines
import com.heartline.wear.ui.components.LocalCelebrations
import com.heartline.wear.ui.components.LocalUserName
import com.heartline.wear.ui.components.rememberBuzz
import com.heartline.wear.ui.ecg.EcgFlow
import com.heartline.wear.ui.heart.BreathingScreen
import com.heartline.wear.ui.heart.HeartRateScreen
import com.heartline.wear.ui.heart.HeartRateViewModel
import com.heartline.wear.ui.launcher.HistoryScreen
import com.heartline.wear.ui.launcher.HistoryViewModel
import com.heartline.wear.ui.launcher.LauncherScreen
import com.heartline.wear.ui.launcher.LauncherState
import com.heartline.wear.ui.launcher.LauncherViewModel
import com.heartline.wear.ui.launcher.MetricOptionsScreen
import com.heartline.wear.ui.quick.QuickFlow
import com.heartline.wear.ui.remote.RemoteMeasureFlow
import com.heartline.wear.ui.settings.DiagnosticsScreen
import com.heartline.wear.ui.settings.WatchSettingsScreen
import com.heartline.wear.ui.settings.WatchSettingsViewModel
import com.heartline.wear.ui.settings.WatchToggle
import com.heartline.wear.ui.settings.WatchUpdatesScreen
import com.heartline.wear.ui.settings.WatchUpdatesViewModel
import com.heartline.wear.ui.setup.CheckingScreen
import com.heartline.wear.ui.setup.CheckingSensorsScreen
import com.heartline.wear.ui.setup.DevModeGuideScreen
import com.heartline.wear.ui.setup.GateState
import com.heartline.wear.ui.setup.PermissionsScreen
import com.heartline.wear.ui.setup.PhoneProblemScreen
import com.heartline.wear.ui.setup.ServiceProblemScreen
import com.heartline.wear.ui.setup.SetupGateViewModel
import com.heartline.wear.ui.setup.SetupIncompleteScreen
import com.heartline.wear.ui.theme.HeartlineWearTheme
import org.koin.androidx.compose.koinViewModel

private typealias Routes = WatchRoutes

/**
 * Opens [link]. From a tile, complication or notification the screen stands alone, so swiping
 * back (or finishing) returns to that tile or watch face; from the phone it opens on top of the
 * launcher.
 */
private fun NavHostController.openExternal(link: String) {
    val entry = EntryLinks.parse(link)
    val route = entry.route
    if (route == Routes.LAUNCHER || !Routes.isExternal(route)) {
        if (!popBackStack(Routes.LAUNCHER, inclusive = false)) {
            navigate(Routes.LAUNCHER) { popUpTo(graph.id) { inclusive = true } }
        }
        return
    }
    // Already open (the phone asks for its calibration screen again with every round): popUpTo
    // would take the screen down before launchSingleTop is checked, and the new one would cancel
    // the round just started. Seen in real logs: start, cancelled 28 ms later, start again.
    if (currentBackStackEntry?.destination?.route == route) {
        HLog.i(OPEN_TAG, "already open: $route")
        return
    }
    val launcherBelow = runCatching { getBackStackEntry(Routes.LAUNCHER) }.isSuccess
    navigate(route) {
        when {
            entry.external -> popUpTo(graph.id) { inclusive = true }
            launcherBelow -> popUpTo(Routes.LAUNCHER)
        }
        launchSingleTop = true
    }
}

/** Leaves the current screen: back to the one below, or out of the app when it stands alone. */
private fun NavHostController.exit(activity: android.app.Activity?) {
    if (previousBackStackEntry == null) activity?.finish() else popBackStack()
}

private const val OPEN_TAG = "Heartline/Open"

@Composable
fun HeartlineWearApp() {
    val gate: SetupGateViewModel = koinViewModel()
    val gateState by gate.state.collectAsStateWithLifecycle()
    val openedOnPhone by gate.openedOnPhone.collectAsStateWithLifecycle()
    val app: WatchAppViewModel = koinViewModel()
    val appUi by app.ui.collectAsStateWithLifecycle()
    val pendingRoute by app.pendingRoute.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val nav = rememberSwipeDismissableNavController()
    // Every start re-checks the phone link; once set up this runs quietly in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { gate.check() }

    // Screens the phone or a notification asked for wait in the bus until they are opened (or
    // turned down), so one that arrives while the activity is being recreated is not lost.
    val ready = gateState is GateState.Ready
    LaunchedEffect(pendingRoute, gateState) {
        // A measurement the phone started can't wait for setup: tell the phone why, and drop it.
        app.handleGateBlocker(pendingRoute, gateState)
    }
    LaunchedEffect(pendingRoute, ready) {
        when (val action = app.consumeReadyRoute(pendingRoute, gateState)) {
            PendingCommandAction.RecheckSetup -> gate.recheckSensors()
            is PendingCommandAction.OpenRoute -> nav.openExternal(action.route)
            null -> Unit
        }
    }

    var permissionDenials by rememberSaveable { mutableIntStateOf(0) }
    // Background sensor access must be asked for on its own, after the foreground permission.
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { app.applyMonitoring() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = SetupPermissions.required.all { p -> context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!granted) permissionDenials++
        app.applyMonitoring()
        gate.onPermissionsResult()
        if (granted && !BackgroundMonitoring.hasBackgroundPermission(context)) background.launch(BackgroundMonitoring.backgroundPermission)
    }

    HeartlineWearTheme(appUi.accent) {
        CompositionLocalProvider(
            LocalUserName provides appUi.userName,
            LocalCelebrations provides appUi.celebrations,
            LocalBaselines provides appUi.baselines,
        ) {
            AppScaffold(timeText = { TimeText() }) {
                when (val g = gateState) {
                    GateState.CheckingPhone -> CheckingScreen(
                        Icons.Rounded.PhoneAndroid,
                        stringResource(R.string.link_checking_title),
                        stringResource(R.string.link_checking_body),
                    )
                    is GateState.PhoneProblem -> PhoneProblemScreen(
                        g.stage,
                        onRetry = { gate.check() },
                        onOpenOnPhone = { gate.openOnPhone(SetupTarget.HOME) },
                        opened = openedOnPhone,
                    )
                    is GateState.SetupIncomplete -> SetupIncompleteScreen(
                        g.status.displayName.ifBlank { null },
                        // The phone app shows the terms before any other screen, so HOME is enough.
                        onOpenOnPhone = { gate.openOnPhone(if (g.status.termsAccepted) SetupTarget.PROFILE else SetupTarget.HOME) },
                        opened = openedOnPhone,
                        termsPending = !g.status.termsAccepted,
                    )
                    GateState.NeedsPermissions -> PermissionsScreen(onAllow = {
                        if (permissionDenials >= 2) {
                            // The system stops showing the dialog after repeated denials: open app settings instead.
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } else {
                            permissions.launch(SetupPermissions.requested)
                        }
                    })
                    GateState.CheckingSensors -> CheckingSensorsScreen()
                    is GateState.SensorIssue -> when (g.problem) {
                        SensorProblem.SDK_POLICY -> DevModeGuideScreen(
                            problemFound = true,
                            onCheckAgain = { gate.recheckSensors() },
                            onShowOnPhone = { gate.openOnPhone(SetupTarget.DEV_MODE_HELP) },
                        )
                        SensorProblem.SERVICE_MISSING, SensorProblem.SERVICE_OUTDATED -> ServiceProblemScreen(
                            outdated = g.problem == SensorProblem.SERVICE_OUTDATED,
                            onAction = { activity?.let(gate::resolveSensors) },
                        )
                        SensorProblem.PERMISSION -> PermissionsScreen(onAllow = { permissions.launch(SetupPermissions.requested) })
                        else -> SensorErrorScreen(g.problem, onAction = { gate.recheckSensors() })
                    }
                    is GateState.Ready -> AppNavHost(nav, gate, appUi.haptics)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, gate: SetupGateViewModel, haptics: Boolean) {
    val activity = LocalActivity.current
    val exit: () -> Unit = { nav.exit(activity) }
    val launcher: LauncherViewModel = koinViewModel()
    val launcherState by launcher.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { launcher.connect() }
    SwipeDismissableNavHost(navController = nav, startDestination = Routes.LAUNCHER) {
        composable(Routes.LAUNCHER) {
            when (val state = launcherState) {
                is LauncherState.Ready -> {
                    // Decided once per visit to the launcher (and at most once a day per reason).
                    val celebrate = androidx.compose.runtime.remember(state.header.birthday, state.header.next) { launcher.celebrate(state.header) }
                    LauncherScreen(
                        state.entries,
                        header = state.header,
                        celebrate = celebrate,
                        onOpen = { nav.navigate(Routes.measure(it)) },
                        onOptions = { nav.navigate(Routes.options(it)) },
                        onHistory = { nav.navigate(Routes.HISTORY) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                is LauncherState.Problem -> SensorErrorScreen(state.problem, onAction = {
                    if (state.problem == SensorProblem.SDK_POLICY) nav.navigate(Routes.DEV_MODE) else launcher.connect()
                })
                LauncherState.Loading -> LauncherScreen(emptyList())
            }
        }
        composable(Routes.ECG) { EcgFlow(onExit = exit) }
        composable(
            Routes.REMOTE,
            arguments = listOf(
                androidx.navigation.navArgument("session") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("round") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val metric = entry.arguments?.getString("metric")?.let { name -> Metric.entries.firstOrNull { it.name == name } }
            val session = entry.arguments?.getString("session")
            if (metric != null && session != null && metric.measuresOnPhone) {
                val link = com.heartline.shared.measure.RemoteMeasureLink(metric, session, entry.arguments?.getString("round")?.toIntOrNull())
                RemoteMeasureFlow(link, onExit = exit)
            } else {
                LaunchedEffect(Unit) { exit() }
            }
        }
        composable(Routes.BLOOD_PRESSURE) {
            BpFlow(onExit = exit, onStartCalibration = { nav.openExternal(Routes.BP_CALIBRATION) })
        }
        composable(Routes.BP_CALIBRATION) { BpFlow(onExit = exit, calibrationSession = true) }
        composable(Routes.QUICK) { entry ->
            val metric = entry.arguments?.getString("metric")?.let { name -> Metric.entries.firstOrNull { it.name == name } } ?: Metric.SPO2
            QuickFlow(metric, onExit = exit)
        }
        composable(Routes.OPTIONS) { entry ->
            val metric = entry.arguments?.getString("metric")?.let { name -> Metric.entries.firstOrNull { it.name == name } } ?: Metric.ECG
            val pinned = (launcherState as? LauncherState.Ready)?.entries?.firstOrNull { it.metric == metric }?.pinned == true
            MetricOptionsScreen(
                metric,
                pinned,
                onPin = {
                    launcher.togglePin(metric)
                    nav.popBackStack()
                },
                onMeasure = {
                    nav.popBackStack()
                    nav.navigate(Routes.measure(metric))
                },
                onHistory = {
                    nav.popBackStack()
                    nav.navigate(Routes.HISTORY)
                },
            )
        }
        composable(Routes.SETTINGS) {
            val vm: WatchSettingsViewModel = koinViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            WatchSettingsScreen(
                state,
                onToggle = { toggle, on ->
                    vm.change { s ->
                        when (toggle) {
                            WatchToggle.ALL_DAY_HEART_RATE -> s.withAllDayHeartRate(on)
                            WatchToggle.HEART_MONITORING -> s.withMonitoring(on)
                            WatchToggle.HEART_PART -> s.withHeartAlerts(on)
                            WatchToggle.SPO2_PART -> s.copy(spo2Monitoring = on)
                            WatchToggle.TEMP_PART -> s.copy(skinTempMonitoring = on)
                            WatchToggle.STRESS_PART -> s.copy(stressMonitoring = on)
                            WatchToggle.HAPTICS -> s.copy(haptics = on)
                            WatchToggle.LIVE_WAVE -> s.copy(liveWave = on)
                        }
                    }
                },
                onSensitivity = { level -> vm.change { it.copy(alertSensitivity = level) } },
                onDevMode = { nav.navigate(Routes.DEV_MODE) },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                onSourceCode = { vm.openUrl(AppInfo.REPO_URL) },
                onCommunity = { vm.openUrl(AppInfo.COMMUNITY_URL) },
                onUpdates = { nav.navigate(Routes.UPDATES) },
            )
        }
        composable(Routes.UPDATES) {
            val vm: WatchUpdatesViewModel = koinViewModel()
            val ui by vm.ui.collectAsStateWithLifecycle()
            val context = LocalContext.current
            var checked by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (!checked) {
                    checked = true
                    vm.check()
                }
            }
            LaunchedEffect(vm) {
                vm.startActivity.collect { intent ->
                    runCatching { context.startActivity(intent) }.onFailure {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
            WatchUpdatesScreen(
                ui,
                onCheck = vm::check,
                onInstall = vm::install,
                onCancelDownload = vm::cancelDownload,
                onCycleTrack = vm::cycleTrack
            )
        }
        composable(Routes.DIAGNOSTICS) {
            val vm: WatchSettingsViewModel = koinViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            DiagnosticsScreen(state)
        }
        composable(Routes.DEV_MODE) {
            DevModeGuideScreen(
                problemFound = false,
                onCheckAgain = {
                    nav.popBackStack(Routes.LAUNCHER, inclusive = false)
                    gate.recheckSensors()
                },
                onShowOnPhone = { gate.openOnPhone(SetupTarget.DEV_MODE_HELP) },
            )
        }
        composable(Routes.HEART_RATE) {
            val vm: HeartRateViewModel = koinViewModel()
            val hr by vm.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
            HeartRateScreen(hr.bpm, hr.onBody)
        }
        composable(Routes.BREATHE) {
            BreathingScreen(
                buzz = rememberBuzz(haptics),
                onMeasure = { nav.navigate(Routes.quick(Metric.STRESS)) { popUpTo(Routes.BREATHE) { inclusive = true } } },
                onDone = exit,
            )
        }
        composable(Routes.HISTORY) {
            val vm: HistoryViewModel = koinViewModel()
            val items by vm.items.collectAsStateWithLifecycle()
            HistoryScreen(items)
        }
    }
}
