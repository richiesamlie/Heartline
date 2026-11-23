// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.di

import android.content.Intent
import com.heartline.logging.HLog
import com.heartline.phone.BuildConfig
import com.heartline.phone.MainActivity
import com.heartline.phone.update.ReleaseSource
import com.heartline.phone.diag.DiagnosticsRepository
import com.heartline.phone.diag.PhoneLogExporter
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.diag.WatchLogArchive
import com.heartline.phone.sync.WatchLogInbox
import com.heartline.phone.update.UpdateRepository
import com.heartline.phone.update.Updater
import com.heartline.datalayer.DataLayerTransport
import com.heartline.datalayer.RemoteOpener
import com.heartline.phone.link.PhoneStatusPublisher
import com.heartline.phone.link.WatchOpener
import com.heartline.phone.notify.Reminders
import com.heartline.phone.ui.WatchLinkViewModel
import com.heartline.phone.R
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.bp.BpDatasetExporter
import com.heartline.phone.bp.BpRefiner
import com.heartline.phone.bp.PapageiEmbedder
import com.heartline.phone.ecg.EcgSecondOpinion
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.export.DataExporter
import com.heartline.phone.heart.PosturalAlertEvaluator
import com.heartline.phone.sync.HeartIngestionCoordinator
import com.heartline.phone.sync.RecordIngestionCoordinator
import com.heartline.phone.ui.onboarding.OnboardingViewModel
import com.heartline.phone.ui.OpenOnWatchViewModel
import com.heartline.phone.ui.body.BodyCompositionViewModel
import com.heartline.phone.ui.metric.MetricDetailViewModel
import com.heartline.phone.ui.profile.ProfileViewModel
import com.heartline.phone.ui.bp.BpHomeViewModel
import com.heartline.phone.ui.bp.CalibrationViewModel
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.format.RecordFormatter
import com.heartline.phone.notify.PhoneNotifier
import com.heartline.phone.qs.HeartlineQsTile
import com.heartline.phone.ui.heart.AlertsViewModel
import com.heartline.phone.ui.heart.HeartRateViewModel
import com.heartline.phone.data.WaveStore
import com.heartline.phone.report.EcgReportBuilder
import com.heartline.phone.ui.ecg.EcgDetailViewModel
import com.heartline.phone.ui.ecg.EcgListViewModel
import com.heartline.phone.ui.home.HomeViewModel
import com.heartline.phone.ui.diagnostics.DiagnosticsViewModel
import com.heartline.phone.ui.legal.BundledDoc
import com.heartline.phone.ui.measure.MeasureViewModel
import com.heartline.phone.ui.settings.SettingsViewModel
import com.heartline.phone.ui.update.UpdatesViewModel
import com.heartline.phone.ui.update.WhatsNewViewModel
import com.heartline.phone.ui.update.WorkManagerDownloads
import com.heartline.shared.bp.MorphologyEmbedder
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.PhoneSyncSender
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.SyncMessageQueue
import com.heartline.shared.sync.SyncTransport
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.RecordKind
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val APP_SCOPE = named("appScope")

/** The phone keeps up to this much of the watch's moved log segments and raw sessions. */
private const val WATCH_ARCHIVE_BYTES = 300L * 1024 * 1024

/** Raw blood-pressure session logs (filesDir). */
private const val BP_SESSIONS = "bp-sessions"

val phoneModule = module {
    single(APP_SCOPE) {
        val handler = CoroutineExceptionHandler { _, e -> HLog.e("Heartline/App", "uncaught coroutine failure", e) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }
    single { SyncMessageQueue(get(APP_SCOPE)) { HLog.w("Heartline/Sync", "queued message failed", it) } }
    single { HeartlineDatabase.create(androidContext()) }
    single { get<HeartlineDatabase>().records() }
    single { WaveStore(androidContext().filesDir) }
    single { RecordRepository(get(), get()) }
    single { DataLayerTransport(androidContext(), Protocol.CAPABILITY_WATCH) } bind SyncTransport::class
    single { PhoneSyncSender(get(), get<RecordRepository>()) }
    single {
        PhoneNotifier(androidContext()) { uri ->
            if (uri == null) {
                Intent(androidContext(), MainActivity::class.java)
            } else {
                Intent(Intent.ACTION_VIEW, uri, androidContext(), MainActivity::class.java)
            }
        }
    }
    single { EcgSecondOpinion.fromAssets(androidContext()) }
    single { SettingsRepository(androidContext()) }
    single { UpdateRepository(androidContext(), BuildConfig.VERSION_NAME) }
    single { ReleaseSource("Heartline/${BuildConfig.VERSION_NAME} (Android)") }
    single { Updater(androidContext(), get(), get(), BuildConfig.VERSION_NAME, enabled = BuildConfig.UPDATER) }
    single { ProfileRepository(androidContext(), get()) }
    single { get<HeartlineDatabase>().heart() }
    single { HeartRepository(get()) }
    single {
        val settings = get<SettingsRepository>()
        val profiles = get<ProfileRepository>()
        val records = get<RecordRepository>()
        PosturalAlertEvaluator(
            heart = get(),
            settings = { settings.monitor.first() },
            age = { profiles.profile.first()?.age() },
            symptomTimes = { from ->
                // Symptoms are tagged on ECGs: how the wearer felt when they took one.
                records.since(RecordKind.ECG, from)
                    .filter { r -> (r.summary as? RecordSummary.Ecg)?.symptoms?.any { it in HeartRepository.FELT } == true }
                    .map { it.startedAtMs }
            },
        )
    }
    single {
        val settings = get<SettingsRepository>()
        val updates = get<UpdateRepository>()
        val notifier = get<PhoneNotifier>()
        HeartIngestionCoordinator(
            heart = get(),
            evaluator = get(),
            onLimits = settings::saveHeartLimits,
            onVitalsLimits = settings::saveVitalsLimits,
            onStressLimits = settings::saveStressLimits,
            watchVersion = { updates.prefs.first().watchVersion },
            posturalScope = get(APP_SCOPE),
            onAlert = notifier::alert,
        )
    }
    single { RemoteLogs(send = get<PhoneSyncSender>()::requestLogs) }
    // The watch's finished log segments and raw sensor sessions, moved here as they're done.
    single { WatchLogArchive(java.io.File(androidContext().filesDir, "watch-logs"), WATCH_ARCHIVE_BYTES) }
    single { WatchLogInbox(get(), ack = get<PhoneSyncSender>()::ackArchive) }
    single {
        DiagnosticsRepository(
            androidContext(),
            get(),
            get(),
            BuildConfig.VERSION_NAME,
            get(),
            sendSettings = get<PhoneSyncSender>()::sendSettings,
            archive = get(),
        )
    }
    single { get<HeartlineDatabase>().bp() }
    single {
        BpRepository(
            get(),
            get(),
            sessionsDir = java.io.File(androidContext().filesDir, BP_SESSIONS),
            sender = get(),
        )
    }
    single { BpDatasetExporter(get(), get()) }
    single {
        PhoneLogExporter(
            androidContext(),
            get(),
            get(),
            get(),
            archive = get(),
            records = get(),
            bpSessions = java.io.File(androidContext().filesDir, BP_SESSIONS),
            bpExporter = get(),
        )
    }
    single {
        // PaPaGei is loaded on first use (only once there are enough cuff checks to train on).
        val papagei = com.heartline.phone.ml.RetryableHolder { PapageiEmbedder.fromAssets(androidContext()) }
        BpRefiner(get(), get(), embedders = { listOfNotNull(MorphologyEmbedder, papagei.get()) })
    }
    single {
        val notifier = get<PhoneNotifier>()
        val context = androidContext()
        RecordIngestionCoordinator(
            records = get(),
            bpRefiner = get(),
            secondOpinion = get(),
            profiles = get(),
            onBpSafety = notifier::bpSafety,
            scope = get(APP_SCOPE),
            enqueueEnrichment = { id, safety -> com.heartline.phone.sync.RecordEnrichmentWorker.enqueue(context, id, safety) },
        )
    }
    single { PhoneStatusPublisher(get(), get(), get(), get()) }
    single { RemoteOpener(androidContext(), get()) }
    single { WatchOpener(get(), get(), get()) }
    single { com.heartline.phone.measure.WatchFeatures(androidContext()) }
    single {
        val records = get<RecordRepository>()
        val transport = get<DataLayerTransport>()
        val features = get<com.heartline.phone.measure.WatchFeatures>()
        val bp = get<BpRepository>()
        val sender = get<PhoneSyncSender>()
        val opener = get<RemoteOpener>()
        com.heartline.phone.measure.MeasureSessionManager(
            probe = { transport.probe() },
            features = features::get,
            calibrated = { bp.calibration.first()?.isValid(System.currentTimeMillis()) == true },
            open = { route -> opener.open(com.heartline.datalayer.DeepLinks.watch(route)) },
            cancelOnWatch = sender::cancelMeasure,
            recordSince = { metric, since ->
                val kind = com.heartline.shared.model.RecordKind.entries.firstOrNull { it.metric == metric }
                kind?.let { k -> records.latestSince(k, since)?.let { it.id to it.summary } }
            },
            scope = get(APP_SCOPE),
        )
    }
    single {
        val updates = get<UpdateRepository>()
        val features = get<com.heartline.phone.measure.WatchFeatures>()
        val settings = get<SettingsRepository>()
        val statusPublisher = get<PhoneStatusPublisher>()
        val bp = get<BpRepository>()
        val profiles = get<ProfileRepository>()
        val sender = get<PhoneSyncSender>()
        val notifier = get<PhoneNotifier>()
        val remoteLogs = get<RemoteLogs>()
        val inbox = get<WatchLogInbox>()
        val measure = get<com.heartline.phone.measure.MeasureSessionManager>()
        PhoneSyncEngine(
            get(),
            get<RecordIngestionCoordinator>(),
            get<HeartIngestionCoordinator>(),
            onHello = { hello ->
                // The watch says hello on start and on every link check: reply with everything it gates on.
                HLog.i("Heartline/Link", "hello from watch: $hello")
                updates.setWatchVersion(hello.appVersion)
                features.set(hello.features)
                // Settings changed on the watch while the phone was away may be newer than ours.
                hello.settings?.let { settings.applyRemote(it) }
                sender.sendStatus(statusPublisher.current())
                sender.sendSettings(settings.current())
                bp.resendCalibration(orNull = true)
                profiles.resend()
            },
            onCaptureResult = bp::onCaptureResult,
            onSetupRequest = { notifier.setupRequest(it.target) },
            onSettings = { incoming ->
                if (settings.applyRemote(incoming)) {
                    HLog.i("Heartline/Settings", "changed on watch: $incoming")
                    Reminders.sync(androidContext(), incoming)
                }
            },
            // Raw blood-pressure session logs (every sensor), kept for the export.
            onSessionLog = bp::saveSession,
            onLogs = remoteLogs::onLogs,
            onLogSegment = remoteLogs::onSegment,
            onArchive = inbox::receive,
            onMeasureState = measure::onState,
            onMeasureResult = measure::onResult,
            onError = { path, error -> HLog.w("Heartline/Sync", "could not handle $path", error) },
            sender = sender,
        )
    }
    factory {
        val ctx = androidContext()
        RecordFormatter(
            ctx.getString(R.string.date_today),
            ctx.getString(R.string.date_yesterday),
            ctx.getString(R.string.home_bp_calibration_left).replace("%1\$d", "%d"),
            ctx.getString(R.string.bp_needs_calibration),
            ctx.getString(R.string.vitals_background_title),
        )
    }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), BuildConfig.VERSION_NAME) }
    viewModel { EcgListViewModel(get(), get()) }
    single { EcgReportBuilder(androidContext()) }
    single { com.heartline.phone.report.PosturalReportBuilder(androidContext(), BuildConfig.VERSION_NAME) }
    viewModel { params -> EcgDetailViewModel(params.get(), get(), get(), get(), get()) }
    viewModel {
        val ctx = androidContext()
        SettingsViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            exporter = get(),
            quickTileMetricFlow = com.heartline.phone.qs.QuickTilePrefs.metric(ctx),
            onQuickTileMetric = { com.heartline.phone.qs.QuickTilePrefs.setMetric(ctx, it) },
            onDeleteLocalFiles = {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    java.io.File(ctx.cacheDir, "reports").deleteRecursively()
                    java.io.File(ctx.cacheDir, "exports").deleteRecursively()
                    HLog.clear()
                    get<WatchLogArchive>().clear()
                }
            },
        ) { Reminders.sync(ctx, it) }
    }
    viewModel {
        HeartRateViewModel(
            get(),
            get(),
            profile = get<ProfileRepository>().profile.map { it?.age() to it?.calcSex },
            settings = get<SettingsRepository>().monitor,
            watchLimits = get<SettingsRepository>().heartLimits,
            checks = get<RecordRepository>().observe(com.heartline.shared.model.RecordKind.HEART_RATE).map { list ->
                list.mapNotNull { r -> (r.summary as? com.heartline.shared.model.RecordSummary.HeartRate)?.let { r.startedAtMs to it } }
            },
        )
    }
    viewModel {
        com.heartline.phone.ui.heart.PosturalViewModel(
            get(),
            get(),
            age = get<ProfileRepository>().profile.map { it?.age() },
            reports = get(),
        )
    }
    viewModel { UpdatesViewModel(get(), get(), BuildConfig.VERSION_NAME, WorkManagerDownloads(androidContext()), get<PhoneNotifier>()::cancelUpdateReady) }
    viewModel { WhatsNewViewModel(get(), BuildConfig.VERSION_NAME) { BundledDoc.CHANGELOG.read(androidContext()) } }
    viewModel { MeasureViewModel(get(), get()) }
    viewModel { DiagnosticsViewModel(get(), get(), get()) }
    viewModel { BpHomeViewModel(get(), get(), get(), exportsDir = java.io.File(androidContext().cacheDir, "exports")) }
    viewModel {
        val opener = get<WatchOpener>()
        CalibrationViewModel(get(), openOnWatch = opener::open, measure = get())
    }
    viewModel { params ->
        val settings = get<SettingsRepository>()
        MetricDetailViewModel(
            params.get(),
            get(),
            get(),
            get(),
            settings.vitalsLimits,
            settings.stressLimits,
            settings.monitor.map { it.spo2Interval },
            temperatureFahrenheit = settings.monitor.map { it.temperatureFahrenheit },
        )
    }
    viewModel { BodyCompositionViewModel(get(), get(), get()) }
    viewModel { ProfileViewModel(get()) }
    viewModel { OpenOnWatchViewModel(get()) }
    viewModel { WatchLinkViewModel(get(), get()) }
    viewModel { com.heartline.phone.ui.share.ShareViewModel(get(), get()) }
    viewModel { OnboardingViewModel(get()) }
    viewModel { com.heartline.phone.ui.onboarding.MonitoringSetupViewModel(get(), get()) { Reminders.sync(androidContext(), it) } }
    single { DataExporter(androidContext()) }
    single { com.heartline.phone.widget.WidgetDataSource(get(), get(), get(), { get() }, profiles = get(), settings = get()) }
    single { com.heartline.phone.widget.WidgetUpdater(androidContext(), get(), get(), get(), get(), get()) { HeartlineQsTile.refreshAll(androidContext()) } }
    viewModel {
        AlertsViewModel(
            get(),
            get(),
            get<RecordRepository>().observe(RecordKind.ECG).map { list ->
                list.mapNotNull { r -> (r.summary as? RecordSummary.Ecg)?.result?.let { r.startedAtMs to it } }
            },
        )
    }
}
