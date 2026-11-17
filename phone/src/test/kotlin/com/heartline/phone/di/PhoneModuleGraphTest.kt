// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.heartline.phone.bp.BpDatasetExporter
import com.heartline.phone.bp.BpRefiner
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.data.WaveStore
import com.heartline.phone.diag.DiagnosticsRepository
import com.heartline.phone.diag.PhoneLogExporter
import com.heartline.phone.ecg.EcgSecondOpinion
import com.heartline.phone.export.DataExporter
import com.heartline.phone.format.RecordFormatter
import com.heartline.phone.heart.PosturalAlertEvaluator
import com.heartline.phone.link.PhoneStatusPublisher
import com.heartline.phone.link.WatchOpener
import com.heartline.phone.measure.MeasureSessionManager
import com.heartline.phone.measure.WatchFeatures
import com.heartline.phone.notify.PhoneNotifier
import com.heartline.phone.report.EcgReportBuilder
import com.heartline.phone.report.PosturalReportBuilder
import com.heartline.phone.sync.HeartIngestionCoordinator
import com.heartline.phone.sync.RecordIngestionCoordinator
import com.heartline.phone.sync.WatchLogInbox
import com.heartline.phone.ui.OpenOnWatchViewModel
import com.heartline.phone.ui.WatchLinkViewModel
import com.heartline.phone.ui.body.BodyCompositionViewModel
import com.heartline.phone.ui.bp.BpHomeViewModel
import com.heartline.phone.ui.bp.CalibrationViewModel
import com.heartline.phone.ui.diagnostics.DiagnosticsViewModel
import com.heartline.phone.ui.ecg.EcgDetailViewModel
import com.heartline.phone.ui.ecg.EcgListViewModel
import com.heartline.phone.ui.heart.AlertsViewModel
import com.heartline.phone.ui.heart.HeartRateViewModel
import com.heartline.phone.ui.heart.PosturalViewModel
import com.heartline.phone.ui.home.HomeViewModel
import com.heartline.phone.ui.measure.MeasureViewModel
import com.heartline.phone.ui.metric.MetricDetailViewModel
import com.heartline.phone.ui.onboarding.MonitoringSetupViewModel
import com.heartline.phone.ui.onboarding.OnboardingViewModel
import com.heartline.phone.ui.profile.ProfileViewModel
import com.heartline.phone.ui.settings.SettingsViewModel
import com.heartline.phone.ui.share.ShareViewModel
import com.heartline.phone.ui.update.UpdatesViewModel
import com.heartline.phone.ui.update.WhatsNewViewModel
import com.heartline.phone.update.ReleaseSource
import com.heartline.phone.update.UpdateRepository
import com.heartline.phone.update.Updater
import com.heartline.phone.widget.WidgetDataSource
import com.heartline.phone.widget.WidgetUpdater
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.diag.WatchLogArchive
import com.heartline.shared.model.Metric
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.PhoneSyncSender
import com.heartline.shared.sync.SyncTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PhoneModuleGraphTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val app = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun allSingletonsFactoriesAndViewModelsResolveWithoutCycles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val koinApp = koinApplication {
            androidContext(app)
            modules(phoneModule)
        }
        val koin = koinApp.koin
        try {
            assertNotNull(koin.get<CoroutineScope>(APP_SCOPE))
            assertNotNull(koin.get<HeartlineDatabase>())
            assertNotNull(koin.get<WaveStore>())
            assertNotNull(koin.get<RecordRepository>())
            assertNotNull(koin.get<SyncTransport>())
            val sender = koin.get<PhoneSyncSender>()
            assertNotNull(koin.get<PhoneNotifier>())
            assertNotNull(koin.get<EcgSecondOpinion>())
            assertNotNull(koin.get<SettingsRepository>())
            assertNotNull(koin.get<UpdateRepository>())
            assertNotNull(koin.get<ReleaseSource>())
            assertNotNull(koin.get<Updater>())
            assertNotNull(koin.get<ProfileRepository>())
            assertNotNull(koin.get<HeartRepository>())
            assertNotNull(koin.get<PosturalAlertEvaluator>())
            assertNotNull(koin.get<HeartIngestionCoordinator>())
            assertNotNull(koin.get<RemoteLogs>())
            assertNotNull(koin.get<WatchLogArchive>())
            assertNotNull(koin.get<WatchLogInbox>())
            assertNotNull(koin.get<DiagnosticsRepository>())
            assertNotNull(koin.get<BpRepository>())
            assertNotNull(koin.get<BpDatasetExporter>())
            assertNotNull(koin.get<PhoneLogExporter>())
            assertNotNull(koin.get<BpRefiner>())
            assertNotNull(koin.get<RecordIngestionCoordinator>())
            assertNotNull(koin.get<PhoneStatusPublisher>())
            assertNotNull(koin.get<WatchOpener>())
            assertNotNull(koin.get<WatchFeatures>())
            assertNotNull(koin.get<MeasureSessionManager>())
            val engine = koin.get<PhoneSyncEngine>()
            assertSame(sender, engine.sender)
            assertNotNull(koin.get<RecordFormatter>())
            assertNotNull(koin.get<EcgReportBuilder>())
            assertNotNull(koin.get<PosturalReportBuilder>())
            assertNotNull(koin.get<DataExporter>())
            assertNotNull(koin.get<WidgetDataSource>())
            assertNotNull(koin.get<WidgetUpdater>())

            assertNotNull(koin.get<HomeViewModel>())
            assertNotNull(koin.get<EcgListViewModel>())
            assertNotNull(koin.get<EcgDetailViewModel> { parametersOf("test-id") })
            assertNotNull(koin.get<SettingsViewModel>())
            assertNotNull(koin.get<HeartRateViewModel>())
            assertNotNull(koin.get<PosturalViewModel>())
            assertNotNull(koin.get<UpdatesViewModel>())
            assertNotNull(koin.get<WhatsNewViewModel>())
            assertNotNull(koin.get<MeasureViewModel>())
            assertNotNull(koin.get<DiagnosticsViewModel>())
            assertNotNull(koin.get<BpHomeViewModel>())
            assertNotNull(koin.get<CalibrationViewModel>())
            assertNotNull(koin.get<MetricDetailViewModel> { parametersOf(Metric.SPO2) })
            assertNotNull(koin.get<BodyCompositionViewModel>())
            assertNotNull(koin.get<ProfileViewModel>())
            assertNotNull(koin.get<OpenOnWatchViewModel>())
            assertNotNull(koin.get<WatchLinkViewModel>())
            assertNotNull(koin.get<ShareViewModel>())
            assertNotNull(koin.get<OnboardingViewModel>())
            assertNotNull(koin.get<MonitoringSetupViewModel>())
            assertNotNull(koin.get<AlertsViewModel>())
        } finally {
            runCatching { koin.get<CoroutineScope>(APP_SCOPE).cancel() }
            runCatching { koin.get<HeartlineDatabase>().close() }
            koinApp.close()
        }
    }
}
