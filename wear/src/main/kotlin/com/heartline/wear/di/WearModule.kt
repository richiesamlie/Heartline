// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.di

import com.heartline.logging.HLog
import kotlinx.coroutines.launch
import com.heartline.wear.diag.WatchLogExporter
import com.heartline.wear.diag.RawCapture
import com.heartline.shared.diag.LogFiles
import com.heartline.shared.diag.LogOffload
import com.heartline.datalayer.DataLayerTransport
import com.heartline.shared.sync.PeerBackoff
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.SyncMessageQueue
import com.heartline.shared.sync.SyncTransport
import com.heartline.shared.sync.WatchSyncEngine
import com.heartline.wear.BuildConfig
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.bp.BpSessionRunner
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.ecg.EcgSessionRunner
import com.heartline.wear.sensor.AndroidImuRecorder
import com.heartline.wear.sensor.BpAuxSensors
import com.heartline.wear.sensor.sdk.SdkBpAuxSensors
import com.heartline.shared.model.Metric
import com.heartline.wear.quick.QuickSources
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.ui.bp.BpMeasureViewModel
import com.heartline.wear.ui.ecg.EcgMeasureViewModel
import com.heartline.wear.ui.quick.QuickMeasureViewModel
import com.heartline.wear.sensor.FakeQuickSource
import com.heartline.wear.sensor.StressSource
import com.heartline.wear.sensor.sdk.SdkBiaSource
import com.heartline.wear.sensor.sdk.SdkSkinTempSource
import com.heartline.wear.sensor.sdk.SdkSpo2Source
import com.heartline.wear.sensor.sdk.readSkinConductance
import com.heartline.wear.sensor.FakePpgSource
import com.heartline.wear.sensor.PpgSource
import com.heartline.wear.sensor.sdk.SdkPpgSource
import com.heartline.wear.monitor.BackgroundHeart
import com.heartline.wear.monitor.BackgroundMonitoring
import com.heartline.wear.monitor.WatchMonitorOutput
import com.heartline.wear.monitor.WatchNotifier
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.sensor.FakeHrSource
import com.heartline.wear.sensor.HrSource
import com.heartline.wear.sensor.sdk.SdkHrSource
import com.heartline.wear.ui.heart.HeartRateViewModel
import com.heartline.wear.ui.remote.RemoteMeasureViewModel
import com.heartline.wear.ui.settings.WatchSettingsViewModel
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.FakeEcgSource
import com.heartline.wear.sensor.FakeSensorGateway
import com.heartline.wear.sensor.SyncScheduler
import com.heartline.wear.sensor.sdk.SdkEcgSource
import com.heartline.wear.sync.SyncWorker
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.sdk.SdkSensorGateway
import com.heartline.wear.ui.launcher.HistoryViewModel
import com.heartline.wear.ui.launcher.LauncherViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.encodeToString
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.heartline.datalayer.RemoteOpener
import com.heartline.shared.sync.Hello
import com.heartline.shared.sync.WatchLinkChecker
import com.heartline.wear.MainActivity
import com.heartline.wear.link.AppForeground
import com.heartline.wear.link.PhoneOpener
import com.heartline.wear.link.WatchCommandBus
import com.heartline.wear.link.WatchLinkStore
import com.heartline.wear.sensor.SetupPermissions
import com.heartline.wear.ui.setup.SetupGateViewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val APP_SCOPE = named("appScope")

val wearModule = wearModule()

fun wearModule(useFakeSensors: Boolean = BuildConfig.USE_FAKE_SENSORS) = module {
    single(APP_SCOPE) {
        val handler = CoroutineExceptionHandler { _, e -> HLog.e("Heartline/App", "uncaught coroutine failure", e) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }
    single { SyncMessageQueue(get(APP_SCOPE)) { HLog.w("Heartline/Sync", "queued message failed", it) } }
    single { WatchDatabase.create(androidContext()) }
    single { get<WatchDatabase>().records() }
    single { get<WatchDatabase>().messages() }
    single { WatchRecordStore(get(), androidContext().filesDir, get()) }
    single { WatchSettingsStore(androidContext()) }
    single { WatchNotifier(androidContext()) }
    single {
        WatchMonitorOutput(
            get(),
            get(),
            get(),
            get(),
            backgroundSync = { SyncWorker.enqueue(androidContext(), replace = false) }
        )
    } bind com.heartline.wear.monitor.MonitorOutput::class
    single {
        val store = get<WatchSettingsStore>()
        val profile = get<WatchProfileStore>()
        BackgroundHeart(get<WatchMonitorOutput>(), store::activityAt, { profile.profile.value?.age() }, { profile.profile.value?.calcSex }, steps = store::stepsAt) { store.settings.value }
    }
    single {
        val prefs = androidContext().getSharedPreferences("sync_backoff", Context.MODE_PRIVATE)
        PeerBackoff(
            initialWaitMs = prefs.getLong("wait_ms", 0L),
            initialNextTryMs = prefs.getLong("next_try_ms", 0L),
            onChange = { waitMs, nextTryMs ->
                prefs.edit().putLong("wait_ms", waitMs).putLong("next_try_ms", nextTryMs).apply()
            }
        )
    }
    single { DataLayerTransport(androidContext(), Protocol.CAPABILITY_PHONE, get<PeerBackoff>()) } bind SyncTransport::class
    single {
        WatchSyncEngine(
            get(),
            get<WatchRecordStore>(),
            onRemoteDelete = { get<WatchRecordStore>().delete(it) },
            onSettings = { settings ->
                if (get<WatchSettingsStore>().offer(settings)) BackgroundMonitoring.sync(androidContext(), settings)
            },
            onCalibration = { get<WatchBpStore>().setCalibration(it) },
            onProfile = { get<WatchProfileStore>().update(it) },
            // The phone opens screens directly; these messages are the fallback. In the foreground
            // they navigate at once, in the background they leave one deep-linking notification.
            onOpen = { route -> if (AppForeground.resumed) get<WatchCommandBus>().post(route) else get<WatchNotifier>().openRequest(route) },
            onCaptureRequest = { request ->
                get<WatchBpStore>().setPendingCapture(request)
                if (AppForeground.resumed) {
                    get<WatchCommandBus>().post(MainActivity.ROUTE_BP_CALIBRATION)
                } else {
                    get<WatchNotifier>().calibrationRequest(request.round)
                }
            },
            onStatus = { get<WatchLinkStore>().update(it) },
            // The phone's Export logs: answer on a channel, off the listener's thread.
            onLogRequest = { request ->
                get<CoroutineScope>(APP_SCOPE).launch(Dispatchers.IO) {
                    val engine = get<WatchSyncEngine>()
                    val exporter = get<WatchLogExporter>()
                    val segment = request.segment
                    when {
                        request.delete -> {
                            HLog.clear()
                            RawCapture.clear()
                        }
                        request.manifest -> engine.sendLogs(request.requestId, Protocol.json.encodeToString(exporter.manifest()).encodeToByteArray())
                        // A segment that's gone (trimmed since the manifest) goes out empty; the phone marks it missing.
                        segment != null -> engine.sendLogSegment(request.requestId, exporter.segment(segment)?.inputStream() ?: ByteArray(0).inputStream())
                        else -> engine.sendLogs(request.requestId, exporter.wholeLog().encodeToByteArray())
                    }
                }
            },
            onArchiveAck = { get<LogOffload>().onAck(it) },
            onMeasureCancel = { get<com.heartline.wear.remote.RemoteMeasureCoordinator>().onCancel(it.sessionId) },
            onError = { path, error -> HLog.w("Heartline/Sync", "could not handle $path", error) },
        )
    }
    single { WatchLogExporter(androidContext(), get(), get()) }
    // Finished log segments and raw sessions go to the phone, oldest first, and are deleted once it confirms.
    single {
        LogOffload(
            pending = {
                (HLog.segmentFiles().map { LogFiles.LOG to it } + RawCapture.files().map { LogFiles.RAW to it })
                    .sortedBy { it.second.lastModified() }
            },
            send = { type, file -> file.exists() && get<WatchSyncEngine>().sendArchive(type, file.name, file.inputStream()) },
        )
    }
    single { WatchLinkStore(androidContext()) }
    single {
        com.heartline.wear.remote.RemoteMeasureCoordinator(
            sendState = { get<WatchSyncEngine>().sendMeasureState(it) },
            sendResult = { result ->
                // Persist in the outbox until acked so brief disconnects never lose it,
                // and send immediately on the fast path so the phone updates without waiting on WorkManager.
                get<com.heartline.wear.data.WatchRecordStore>().enqueueMessage(
                    result.id,
                    Protocol.MEASURE_RESULT,
                    Protocol.json.encodeToString(result).encodeToByteArray(),
                )
                if (!get<WatchSyncEngine>().sendMeasureResult(result)) {
                    get<SyncScheduler>().schedule()
                }
            },
            scope = get(APP_SCOPE),
            onBusy = { com.heartline.wear.monitor.BackgroundSensors.preempt("measurement") },
        )
    }
    single { WatchCommandBus() }
    single { RemoteOpener(androidContext(), get()) }
    single { PhoneOpener(get(), get()) }
    single {
        WatchLinkChecker(
            get<DataLayerTransport>(),
            get(),
            get<WatchLinkStore>().latest,
            hello = {
                Hello(
                    appVersion = BuildConfig.VERSION_NAME,
                    capabilities = (get<SensorGateway>().state.value as? GatewayState.Connected)?.trackers
                        ?.let { com.heartline.shared.sensor.MetricRequirements.supportedMetrics(it) }.orEmpty(),
                    sensorServiceVersion = (get<SensorGateway>().state.value as? GatewayState.Connected)?.serviceVersion,
                    deviceName = Build.MODEL,
                    settings = get<WatchSettingsStore>().settings.value,
                    features = listOf(com.heartline.shared.sync.Features.REMOTE_MEASURE),
                )
            },
            log = { HLog.i("Heartline/Link", it) },
        )
    }
    if (useFakeSensors) {
        // Debug builds use real sensors when the build flag is off; see BuildConfig.USE_FAKE_SENSORS.
        single<SensorGateway> { FakeSensorGateway() }
        single<HrSource> { FakeHrSource() }
        single<EcgSource> { FakeEcgSource() }
        single<PpgSource> { FakePpgSource() }
        single { BpAuxSensors.NONE }
    } else {
        single {
            SdkSensorGateway(
                androidContext(),
                heartRateInUse = { get<HrSource>().isActive },
                measurementsBusy = { get<com.heartline.wear.remote.RemoteMeasureCoordinator>().busy },
                backgroundBusy = { com.heartline.wear.monitor.BackgroundSensors.lock.isLocked },
                isForeground = { AppForeground.resumed },
            )
        } bind SensorGateway::class
        single<HrSource> { SdkHrSource(get()) }
        single<EcgSource> { SdkEcgSource(get()) }
        single<PpgSource> { SdkPpgSource(get()) }
        single<BpAuxSensors> { SdkBpAuxSensors(get()) }
    }
    single { SyncScheduler { SyncWorker.enqueue(androidContext()) } }
    single { WatchBpStore(androidContext()) }
    single { WatchProfileStore(androidContext()) }
    single { com.heartline.wear.tile.TileUpdates(androidContext(), get(), get(), get()) }
    single { com.heartline.wear.tile.TileDataLoader(androidContext(), get(), get(), get(), get()) }
    single {
        val skinConductance: suspend () -> Float? =
            if (useFakeSensors) {
                suspend { null }
            } else {
                val gateway = get<SdkSensorGateway>()
                suspend { gateway.readSkinConductance() }
            }
        val hr = StressSource(
            get(),
            skinConductance = skinConductance,
            scorer = { rmssd, bpm ->
                val store = get<WatchSettingsStore>()
                val now = System.currentTimeMillis()
                val zone = java.time.ZoneId.systemDefault()
                val settings = store.settings.value
                val context = com.heartline.wear.monitor.StressWindows.contextAt(now, settings, store::activityAt, zone)
                val today = com.heartline.shared.stress.StressBaseline.dayOf(now, com.heartline.shared.hr.HrContext.REST, zone)
                val normal = com.heartline.shared.stress.StressBaseline.normal(store.stress, today)
                val score = com.heartline.shared.stress.StressBaseline.score(rmssd, bpm, normal)
                if (context == com.heartline.shared.hr.HrContext.REST && settings.stressActive) {
                    val ill = com.heartline.wear.monitor.StressWindows.illness(store.vitals, store.monitorState.history, settings, today)
                    val monitor = com.heartline.shared.stress.StressMonitor(zone) { java.util.UUID.randomUUID().toString() }
                    store.updateStress { current ->
                        monitor.onWindow(current, now, rmssd, bpm, com.heartline.shared.hr.HrContext.REST, settings.copy(stressNotifications = false), ill).first
                    }
                }
                score
            },
        )
        if (useFakeSensors) {
            QuickSources(FakeQuickSource.all() + hr)
        } else {
            val gateway = get<SdkSensorGateway>()
            QuickSources(listOf(SdkSpo2Source(gateway), SdkSkinTempSource(gateway), SdkBiaSource(gateway), hr))
        }
    }
    single { com.heartline.wear.data.LauncherPrefs(androidContext()) }
    viewModel { LauncherViewModel(get(), get(), get(), get(), get()) }
    viewModel {
        val ctx = androidContext()
        com.heartline.wear.ui.WatchAppViewModel(
            bus = get(),
            coordinator = get(),
            settingsStore = get(),
            profiles = get(),
            records = get(),
            syncMonitoring = { changed -> BackgroundMonitoring.sync(ctx, changed) },
        )
    }
    viewModel {
        val context = androidContext()
        SetupGateViewModel(get(), get(), get(), get()) {
            SetupPermissions.required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        }
    }
    viewModel { HistoryViewModel(get()) }
    viewModel { HeartRateViewModel(get()) }
    viewModel { com.heartline.wear.ui.heart.HeartRateCheckViewModel(get(), get(), get(), coordinator = get()) }
    viewModel {
        WatchSettingsViewModel(get(), get(), remote = get()) { changed ->
            BackgroundMonitoring.sync(androidContext(), changed)
            get<WatchSyncEngine>().sendSettings(changed)
        }
    }
    factory {
        BpSessionRunner(
            get(),
            get(),
            get(),
            get(),
            imu = AndroidImuRecorder(androidContext()),
            ecg = get<EcgSource>(),
            aux = get(),
            heightCm = { get<WatchProfileStore>().profile.value?.heightCm?.toDouble()?.takeIf { it > 0 } },
            device = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
    viewModel { BpMeasureViewModel(runner = get(), gateway = get(), phone = get(), settingsStore = get(), coordinator = get()) }
    viewModel { params -> QuickMeasureViewModel(get<QuickSources>()[params.get<Metric>()]!!, get(), get(), get(), gateway = get(), settingsStore = get(), coordinator = get()) }
    factory { EcgSessionRunner(get(), get(), get(), onResult = get<WatchSettingsStore>()::noteEcg) }
    viewModel { EcgMeasureViewModel(runner = get(), gateway = get(), settingsStore = get(), coordinator = get()) }
    viewModel { RemoteMeasureViewModel(get(), get()) }
    single { com.heartline.wear.update.WatchUpdateStore(androidContext(), BuildConfig.VERSION_NAME) }
    single { com.heartline.wear.update.WatchReleaseSource("Heartline/${BuildConfig.VERSION_NAME} (Wear OS)") }
    single { com.heartline.wear.update.WatchUpdater(androidContext(), get(), get(), BuildConfig.VERSION_NAME, enabled = BuildConfig.UPDATER) }
    viewModel { com.heartline.wear.ui.settings.WatchUpdatesViewModel(get(), get(), BuildConfig.VERSION_NAME, get(APP_SCOPE)) }
}
