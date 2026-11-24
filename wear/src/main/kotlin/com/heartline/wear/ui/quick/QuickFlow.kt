// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.quick

import com.heartline.wear.ui.components.Buzz
import com.heartline.wear.ui.components.rememberBuzz
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.shared.body.BodyComposition
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.SensorErrorScreen
import com.heartline.wear.ui.body.BodyResultScreen
import com.heartline.wear.ui.body.WeightConfirmScreen
import com.heartline.wear.ui.bp.ProfileNeededScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import com.heartline.shared.sync.MeasureOutcome
import com.heartline.shared.sync.MeasureProblem
import com.heartline.shared.sync.MeasureStage
import com.heartline.wear.remote.RemoteMeasureReporter

/**
 * One SpO2 / skin temperature / body composition / stress measurement. With [remote] the phone
 * started it: it begins at once and every step goes to the phone, which shows the same.
 */
@Composable
fun QuickFlow(
    metric: Metric,
    onExit: () -> Unit,
    remote: RemoteMeasureReporter? = null,
    vm: QuickMeasureViewModel = koinViewModel(key = remote?.link?.sessionId ?: metric.name) { parametersOf(metric) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = LocalHapticFeedback.current
    val permissions = PermissionPolicy.permissionsFor(metric, Build.VERSION.SDK_INT).toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { ok -> ok }) vm.start() }
    fun start() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.start() else launcher.launch(missing.toTypedArray())
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Started from the phone, it goes on while the watch screen is off (the wrist lowered to
        // look at the phone); leaving the screen (back) still cancels it, below.
        if (remote != null) {
            vm.logRemoteHidden(remote.link)
        } else if (state is QuickState.Measuring) {
            vm.cancel()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.cancel()
            remote?.finish(MeasureOutcome.CANCELLED, MeasureProblem.WATCH_LEFT)
        }
    }
    if (remote != null) {
        LaunchedEffect(Unit) {
            if (vm.isRemoteCancelled(remote.link.sessionId)) {
                remote.finish(MeasureOutcome.CANCELLED)
                onExit()
                return@LaunchedEffect
            }
            remote.report(MeasureStage.ACCEPTED)
            start()
        }
        LaunchedEffect(remote) {
            vm.remoteCancels.collect { id ->
                if (id == remote.link.sessionId) {
                    vm.cancel()
                    remote.finish(MeasureOutcome.CANCELLED)
                    onExit()
                }
            }
        }
        // From the view model, not the screen's state: the phone hears every step even while the
        // watch screen is off and the screen doesn't update.
        LaunchedEffect(remote) {
            vm.state.collect { s -> when (s) {
                QuickState.Idle, is QuickState.ConfirmWeight -> Unit
                QuickState.NeedsProfile -> {
                    remote.reject(MeasureProblem.NEEDS_PROFILE)
                    kotlinx.coroutines.delay(com.heartline.wear.remote.REMOTE_RESULT_MS / 2)
                    vm.reset()
                    onExit()
                }
                QuickState.Contraindicated -> {
                    remote.reject(MeasureProblem.UNSUPPORTED)
                    kotlinx.coroutines.delay(com.heartline.wear.remote.REMOTE_RESULT_MS / 2)
                    vm.reset()
                    onExit()
                }
                is QuickState.Measuring -> remote.report(
                    MeasureStage.MEASURING,
                    s.progress,
                    s.secondsLeft,
                    RemoteMeasureReporter.hint(s.hint),
                    live = s.bpm,
                )
                is QuickState.Done -> {
                    remote.finish(MeasureOutcome.OK, recordId = s.recordId, summary = s.summary)
                    // The phone has the result too: leave the watch screen by itself after a while.
                    kotlinx.coroutines.delay(com.heartline.wear.remote.REMOTE_RESULT_MS)
                    vm.reset()
                    onExit()
                }
                is QuickState.Failed -> {
                    remote.finish(MeasureOutcome.FAILED, RemoteMeasureReporter.problem(s.problem, s.hint))
                    kotlinx.coroutines.delay(com.heartline.wear.remote.REMOTE_RESULT_MS / 2)
                    vm.reset()
                    onExit()
                }
            } }
        }
    }
    val view = LocalView.current
    DisposableEffect(state is QuickState.Measuring) {
        view.keepScreenOn = state is QuickState.Measuring
        onDispose { view.keepScreenOn = false }
    }
    val prefs by vm.settings.collectAsStateWithLifecycle()
    val vibrate = rememberBuzz(prefs.haptics)
    LaunchedEffect(state is QuickState.Measuring) { if (state is QuickState.Measuring) vibrate(Buzz.STARTED) }
    LaunchedEffect(state is QuickState.Done) { if (state is QuickState.Done) vibrate(Buzz.DONE) }
    val done = {
        vm.reset()
        onExit()
    }
    when (val s = state) {
        QuickState.Idle -> if (remote != null) QuickMeasuringScreen(metric, 0f, vm.seconds, null, fromPhone = true) else QuickInstructionScreen(metric, onStart = ::start)
        QuickState.NeedsProfile -> ProfileNeededScreen(onDone = done)
        QuickState.Contraindicated -> BiaContraindicatedScreen(onDone = done)
        is QuickState.Measuring -> QuickMeasuringScreen(metric, s.progress, s.secondsLeft, s.hint, s.bpm, s.hrvMs, fromPhone = remote != null)
        is QuickState.ConfirmWeight -> WeightConfirmScreen(s.weightKg, onConfirm = vm::confirmWeight)
        is QuickState.Done -> {
            val body = s.summary as? RecordSummary.BodyComposition
            if (body != null) {
                val sex = s.profile?.calcSex
                val age = s.profile?.age()
                val report = BodyComposition.report(body, sex, age, s.profile?.weightKg, s.profile?.heightCm)
                val previous = (s.previous as? RecordSummary.BodyComposition)?.let { BodyComposition.report(it, sex, age, s.profile?.weightKg, s.profile?.heightCm) }
                BodyResultScreen(report, previous, onDone = done)
            } else {
                QuickResultScreen(metric, s.summary, onDone = done)
            }
        }
        is QuickState.Failed -> when (val problem = s.problem) {
            null -> QuickFailedScreen(metric, s.hint ?: QuickHint.LOW_SIGNAL, onRetry = { if (remote != null) done() else vm.reset() })
            else -> SensorErrorScreen(
                problem,
                onAction = {
                    when {
                        problem == SensorProblem.SERVICE_MISSING || problem == SensorProblem.SERVICE_OUTDATED ->
                            activity?.let(vm::resolveSensors)
                        problem == SensorProblem.PERMISSION -> {
                            vm.reset()
                            start()
                        }
                        remote != null -> done()
                        else -> vm.reset()
                    }
                },
            )
        }
    }
}
