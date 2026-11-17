// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.model.Metric
import com.heartline.shared.profile.Sex
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.monitor.BackgroundHeart
import com.heartline.wear.monitor.BackgroundMonitoring
import com.heartline.wear.monitor.IrnWindowWorker
import com.heartline.wear.monitor.VitalsWorker
import com.heartline.wear.monitor.WatchMonitorOutput
import com.heartline.wear.monitor.WatchNotifier
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.quick.QuickSources
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.sensor.FakeQuickSource
import com.heartline.wear.sensor.HrSource
import com.heartline.wear.sensor.QuickEvent
import com.heartline.wear.sensor.QuickSource
import com.heartline.wear.sensor.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BackgroundWorkerPowerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WatchDatabase
    private lateinit var store: WatchSettingsStore
    private lateinit var recordStore: WatchRecordStore
    private var hrStreams = 0
    private var quickStreams = 0

    private val countingHrSource = object : HrSource {
        override fun stream(): Flow<HrSample> = flow {
            hrStreams += 1
        }
    }

    private fun countingQuickSource(metric: Metric): QuickSource {
        val delegate = FakeQuickSource.all().first { it.metric == metric }
        return object : QuickSource by delegate {
            override fun measure(profile: UserProfile?): Flow<QuickEvent> = flow {
                quickStreams += 1
                delegate.measure(profile).collect { emit(it) }
            }
        }
    }

    @Before
    fun setUp() {
        hrStreams = 0
        quickStreams = 0
        val perms = (
            PermissionPolicy.permissionsFor(Metric.HEART_RATE, Build.VERSION.SDK_INT) +
                PermissionPolicy.permissionsFor(Metric.SKIN_TEMPERATURE, Build.VERSION.SDK_INT) +
                PermissionPolicy.permissionsFor(Metric.SPO2, Build.VERSION.SDK_INT) +
                BackgroundMonitoring.backgroundPermission
            ).distinct().toTypedArray()
        shadowOf(context as Application).grantPermissions(*perms)

        context.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("profile", Context.MODE_PRIVATE).edit().clear().commit()
        db = WatchDatabase.inMemory(context)
        recordStore = WatchRecordStore(db.records(), File(context.cacheDir, "worker-power-test").apply { deleteRecursively() }, db.messages())
        store = WatchSettingsStore(context)
        val notifier = WatchNotifier(context)
        val output = WatchMonitorOutput(recordStore, store, notifier, SyncScheduler { })
        val bgHeart = BackgroundHeart(output, store::activityAt, { 35 }, { Sex.MALE }, steps = store::stepsAt) { store.settings.value }
        val profiles = WatchProfileStore(context)
        val coordinator = RemoteMeasureCoordinator({ true }, {}, CoroutineScope(Dispatchers.Unconfined))

        startKoin {
            modules(
                module {
                    single<HrSource> { countingHrSource }
                    single { store }
                    single { output }
                    single { notifier }
                    single { bgHeart }
                    single { QuickSources(listOf(countingQuickSource(Metric.SKIN_TEMPERATURE), countingQuickSource(Metric.SPO2))) }
                    single { profiles }
                    single { coordinator }
                }
            )
        }
    }

    @After
    @Suppress("DEPRECATION")
    fun tearDown() {
        shadowOf(context.getSystemService(PowerManager::class.java)!!).setIsPowerSaveMode(false)
        context.removeStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
        stopKoin()
        db.close()
    }

    @Test
    fun irnWorkerSkipsUnderPowerSaverAndLowBattery() = runBlocking {
        store.update(MonitorSettings().withMonitoring(true).copy(irregularRhythmEnabled = true, updatedAtMs = 1L))
        val irnWorker = { TestListenableWorkerBuilder<IrnWindowWorker>(context).build() }

        shadowOf(context.getSystemService(PowerManager::class.java)!!).setIsPowerSaveMode(true)
        assertEquals(ListenableWorker.Result.success(), irnWorker().doWork())
        shadowOf(context.getSystemService(PowerManager::class.java)!!).setIsPowerSaveMode(false)

        sendBattery(10)
        assertEquals(ListenableWorker.Result.success(), irnWorker().doWork())

        assertEquals(0, hrStreams)
    }

    @Test
    fun vitalsWorkerSkipsUnderPowerSaverAndDaytimeSkinTempSkipsUnderLowBattery() = runBlocking {
        // Equal sleep bounds mean isUsualSleep is always false so this always tests daytime behavior.
        store.update(
            MonitorSettings(
                heartMonitoring = true,
                spo2Monitoring = true,
                skinTempMonitoring = true,
                sleepStartMinute = 0,
                sleepEndMinute = 0,
                updatedAtMs = 2L
            )
        )

        val vitalsWorker = { TestListenableWorkerBuilder<VitalsWorker>(context).build() }
        shadowOf(context.getSystemService(PowerManager::class.java)!!).setIsPowerSaveMode(true)
        assertEquals(ListenableWorker.Result.success(), vitalsWorker().doWork())
        assertEquals(0, quickStreams)
        shadowOf(context.getSystemService(PowerManager::class.java)!!).setIsPowerSaveMode(false)

        // Low battery (10%) skips both daytime skin temperature and SpO2 without opening quick sensors.
        sendBattery(10)
        assertEquals(ListenableWorker.Result.success(), vitalsWorker().doWork())
        assertEquals(0, quickStreams)
        assertEquals(0L, store.lastTempMs)
    }

    @Test
    fun heartTilePushTimestampPersistsAcrossStoreInstances() {
        store.lastHeartTilePushMs = 123_456L
        val reopened = WatchSettingsStore(context)
        assertEquals(123_456L, reopened.lastHeartTilePushMs)
        assertTrue(reopened.lastHeartTilePushMs > 0L)
    }

    @Suppress("DEPRECATION")
    private fun sendBattery(percent: Int) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, percent)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
        }
        context.sendStickyBroadcast(intent)
    }
}
