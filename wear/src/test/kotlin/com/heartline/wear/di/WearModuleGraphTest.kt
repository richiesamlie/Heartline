// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.heartline.shared.diag.LogOffload
import com.heartline.shared.model.Metric
import com.heartline.shared.sync.PeerBackoff
import com.heartline.shared.sync.SyncTransport
import com.heartline.shared.sync.WatchLinkChecker
import com.heartline.shared.sync.WatchSyncEngine
import com.heartline.wear.bp.BpSessionRunner
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.ecg.EcgSessionRunner
import com.heartline.wear.ui.WatchAppViewModel
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.diag.WatchLogExporter
import com.heartline.wear.link.PhoneOpener
import com.heartline.wear.link.WatchCommandBus
import com.heartline.wear.link.WatchLinkStore
import com.heartline.wear.monitor.BackgroundHeart
import com.heartline.wear.monitor.WatchMonitorOutput
import com.heartline.wear.monitor.WatchNotifier
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.quick.QuickSources
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.sensor.BpAuxSensors
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.HrSource
import com.heartline.wear.sensor.PpgSource
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.SyncScheduler
import com.heartline.wear.tile.TileDataLoader
import com.heartline.wear.tile.TileUpdates
import com.heartline.wear.ui.bp.BpMeasureViewModel
import com.heartline.wear.ui.ecg.EcgMeasureViewModel
import com.heartline.wear.ui.heart.HeartRateCheckViewModel
import com.heartline.wear.ui.heart.HeartRateViewModel
import com.heartline.wear.ui.remote.RemoteMeasureViewModel
import com.heartline.wear.ui.launcher.HistoryViewModel
import com.heartline.wear.data.LauncherPrefs
import com.heartline.wear.ui.launcher.LauncherViewModel
import com.heartline.wear.ui.quick.QuickMeasureViewModel
import com.heartline.wear.ui.settings.WatchSettingsViewModel
import com.heartline.wear.ui.settings.WatchUpdatesViewModel
import com.heartline.wear.ui.setup.SetupGateViewModel
import com.heartline.wear.update.WatchReleaseSource
import com.heartline.wear.update.WatchUpdateStore
import com.heartline.wear.update.WatchUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WearModuleGraphTest {
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
    fun allSingletonsAndViewModelsResolveCleanly() {
        verifyGraph(useFakeSensors = false)
        verifyGraph(useFakeSensors = true)
    }

    private fun verifyGraph(useFakeSensors: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val koinApp = koinApplication {
            androidContext(app)
            modules(wearModule(useFakeSensors = useFakeSensors))
        }
        val koin = koinApp.koin
        try {
            assertNotNull(koin.get<CoroutineScope>(APP_SCOPE))
            assertNotNull(koin.get<WatchDatabase>())
            assertNotNull(koin.get<WatchRecordStore>())
            assertNotNull(koin.get<WatchSettingsStore>())
            assertNotNull(koin.get<WatchNotifier>())
            assertNotNull(koin.get<WatchMonitorOutput>())
            assertNotNull(koin.get<BackgroundHeart>())
            assertNotNull(koin.get<HrSource>())
            assertNotNull(koin.get<PeerBackoff>())
            assertNotNull(koin.get<SyncTransport>())
            assertNotNull(koin.get<WatchSyncEngine>())
            assertNotNull(koin.get<WatchLogExporter>())
            assertNotNull(koin.get<LogOffload>())
            assertNotNull(koin.get<WatchLinkStore>())
            assertNotNull(koin.get<RemoteMeasureCoordinator>())
            assertNotNull(koin.get<WatchCommandBus>())
            assertNotNull(koin.get<PhoneOpener>())
            assertNotNull(koin.get<WatchLinkChecker>())
            assertNotNull(koin.get<SensorGateway>())
            assertNotNull(koin.get<BpAuxSensors>())
            assertNotNull(koin.get<EcgSource>())
            assertNotNull(koin.get<SyncScheduler>())
            assertNotNull(koin.get<WatchBpStore>())
            assertNotNull(koin.get<WatchProfileStore>())
            assertNotNull(koin.get<TileUpdates>())
            assertNotNull(koin.get<TileDataLoader>())
            assertNotNull(koin.get<QuickSources>())
            assertNotNull(koin.get<PpgSource>())
            assertNotNull(koin.get<LauncherPrefs>())
            assertNotNull(koin.get<WatchUpdateStore>())
            assertNotNull(koin.get<WatchReleaseSource>())
            assertNotNull(koin.get<WatchUpdater>())

            assertNotNull(koin.get<LauncherViewModel>())
            assertNotNull(koin.get<WatchAppViewModel>())
            assertNotNull(koin.get<SetupGateViewModel>())
            assertNotNull(koin.get<HistoryViewModel>())
            assertNotNull(koin.get<HeartRateViewModel>())
            assertNotNull(koin.get<HeartRateCheckViewModel>())
            assertNotNull(koin.get<WatchSettingsViewModel>())
            assertNotNull(koin.get<BpSessionRunner>())
            assertNotNull(koin.get<BpMeasureViewModel>())
            assertNotNull(koin.get<QuickMeasureViewModel> { parametersOf(Metric.SPO2) })
            assertNotNull(koin.get<EcgSessionRunner>())
            assertNotNull(koin.get<EcgMeasureViewModel>())
            assertNotNull(koin.get<RemoteMeasureViewModel>())
            assertNotNull(koin.get<WatchUpdatesViewModel>())
        } finally {
            runCatching { koin.get<CoroutineScope>(APP_SCOPE).cancel() }
            runCatching { koin.get<WatchDatabase>().close() }
            koinApp.close()
        }
    }
}
