// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.heartline.shared.hr.ActivityChange
import com.heartline.shared.hr.HealthContext
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.WatchActivity
import com.heartline.shared.model.Metric
import com.heartline.shared.profile.Sex
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.shared.vitals.VitalsDay
import com.heartline.shared.vitals.VitalsHistory
import com.heartline.shared.vitals.VitalsMonitor
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.quick.QuickSources
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.sensor.FakeQuickSource
import com.heartline.wear.sensor.HrSource
import com.heartline.wear.sensor.QuickEvent
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.QuickSource
import com.heartline.wear.sensor.StressSource
import com.heartline.wear.sensor.SyncScheduler
import com.heartline.wear.sensor.sdk.sanitizeBia
import com.heartline.wear.sensor.sdk.sanitizeSkinTemp
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class Wave2dWearVitalsRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneOffset.UTC
    private lateinit var db: WatchDatabase
    private lateinit var store: WatchSettingsStore
    private lateinit var recordStore: WatchRecordStore
    private var quickStreams = 0
    private var batchSyncs = 0

    private fun countingQuickSource(metric: Metric): QuickSource {
        val delegate = FakeQuickSource.all().first { it.metric == metric }
        return object : QuickSource by delegate {
            override fun measure(profile: com.heartline.shared.profile.UserProfile?): Flow<QuickEvent> = flow {
                quickStreams += 1
                delegate.measure(profile).collect { emit(it) }
            }
        }
    }

    @Before
    fun setUp() {
        quickStreams = 0
        batchSyncs = 0
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
        recordStore = WatchRecordStore(db.records(), File(context.cacheDir, "wave2d-wear-test").apply { deleteRecursively() }, db.messages())
        store = WatchSettingsStore(context)
        val notifier = WatchNotifier(context)
        val output = WatchMonitorOutput(
            recordStore,
            store,
            notifier,
            SyncScheduler { batchSyncs++ },
            backgroundSync = { batchSyncs++ },
        )
        val bgHeart = BackgroundHeart(output, store::activityAt, { 35 }, { Sex.MALE }, steps = store::stepsAt) { store.settings.value }
        val profiles = WatchProfileStore(context)
        val coordinator = RemoteMeasureCoordinator({ true }, {}, CoroutineScope(Dispatchers.Unconfined))

        startKoin {
            modules(
                module {
                    single<HrSource> {
                        object : HrSource {
                            override fun stream(): Flow<HrSample> = flow {}
                        }
                    }
                    single { store }
                    single { output }
                    single { notifier }
                    single { bgHeart }
                    single { QuickSources(listOf(countingQuickSource(Metric.SKIN_TEMPERATURE), countingQuickSource(Metric.SPO2))) }
                    single { profiles }
                    single { coordinator }
                },
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
    fun asleepSinceBridgesShortWakeGapsStopsOnLongGapsAndFallsBackToScheduleWhenNoActivityChanges() {
        val tBase = LocalDate.ofEpochDay(20_050L).atStartOfDay(zone).toInstant().toEpochMilli()
        val settings = MonitorSettings(sleepStartMinute = 22 * 60, sleepEndMinute = 7 * 60)
        val sleepStore = WatchSettingsStore(context, now = { tBase + 5 * 3_600_000L })

        // No activity changes: falls back to usual sleep schedule.
        assertEquals(tBase - 2 * 3_600_000L, sleepStore.asleepSince(tBase + 2 * 3_600_000L, settings, zone))
        assertNull(sleepStore.asleepSince(tBase + 14 * 3_600_000L, settings, zone))

        // Short wake gap of 15 min is bridged back to 01:00.
        sleepStore.recordActivity(ActivityChange(tBase + 1 * 3_600_000L, WatchActivity.ASLEEP))
        sleepStore.recordActivity(ActivityChange(tBase + 2 * 3_600_000L, WatchActivity.PASSIVE))
        sleepStore.recordActivity(ActivityChange(tBase + 2 * 3_600_000L + 15 * 60_000L, WatchActivity.ASLEEP))
        assertEquals(tBase + 1 * 3_600_000L, sleepStore.asleepSince(tBase + 3 * 3_600_000L, settings, zone))

        // Long wake gap of 25 min resets sleep start to the resumed segment.
        sleepStore.recordActivity(ActivityChange(tBase + 3 * 3_600_000L + 5 * 60_000L, WatchActivity.PASSIVE))
        sleepStore.recordActivity(ActivityChange(tBase + 3 * 3_600_000L + 30 * 60_000L, WatchActivity.ASLEEP))
        assertEquals(tBase + 3 * 3_600_000L + 30 * 60_000L, sleepStore.asleepSince(tBase + 4 * 3_600_000L, settings, zone))
    }

    @Test
    fun stressWindowsRespectHasActivityAndPregnancyAndStressSourceGuardsBadInputs() = runBlocking {
        val dayMs = 20_050L * 86_400_000L
        val at2Am = dayMs + 2 * 3_600_000L
        assertEquals(HrContext.REST, StressWindows.contextAt(at2Am, MonitorSettings(), { null }, zone, hasActivity = true))

        val today = 20_050L
        val vitals = VitalsHistory((1..4).map { VitalsDay(today - it, temps = List(6) { 33.5f }) } + VitalsDay(today, temps = List(6) { 34.5f }))
        assertFalse(StressWindows.illness(vitals, com.heartline.shared.hr.HeartHistory(), MonitorSettings(health = HealthContext(pregnant = true)), today))

        val blocked = StressSource(
            hr = object : HrSource {
                override fun stream(): Flow<HrSample> = flow {}
            },
            hrvUnreadable = { true },
        )
        val firstEvent = blocked.measure(null).first()
        assertTrue(firstEvent is QuickEvent.Failed && firstEvent.hint == QuickHint.LOW_SIGNAL)

        // All-moving samples leave ibis empty & hrv null without arithmetic division crash.
        val moving = StressSource(
            hr = object : HrSource {
                override fun stream(): Flow<HrSample> = flow {
                    repeat(5) { i -> emit(HrSample(i * 1_000L, 70, listOf(850), moving = true)) }
                }
            },
            seconds = 2,
            tickMs = 1L,
            noDataSeconds = 1,
        )
        val events = mutableListOf<QuickEvent>()
        moving.measure(null).collect { events += it }
        assertTrue(events.last() is QuickEvent.Failed)
    }

    @Test
    fun sdkSanitizersRejectNonFiniteOrImplausibleValues() {
        assertNull(sanitizeSkinTemp(Float.NaN, 24f))
        assertNull(sanitizeSkinTemp(15f, 24f))
        val validTemp = sanitizeSkinTemp(33.5f, 150f)!!
        assertEquals(33.5f, validTemp.skinCelsius, 1e-6f)
        assertNull(validTemp.ambientCelsius)

        assertNull(sanitizeBia(Float.NaN))
        assertNull(sanitizeBia(90f))
        val scrubbed = sanitizeBia(
            fatPercent = 18.5f,
            muscleKg = 32f,
            waterKg = 41f,
            bmrKcal = 90_000,
            impedanceOhm = 40f,
            phaseAngleDeg = 90f,
        )!!
        assertEquals(18.5f, scrubbed.bodyFatPercent, 1e-6f)
        assertNull(scrubbed.bmrKcal)
        assertNull(scrubbed.impedanceOhm)
        assertNull(scrubbed.phaseAngleDeg)
    }

    @Test
    fun vitalsWorkerSkipsIdleSensorLockAndRunsAfterNightBeforePowerSaverEarlyExit() = runBlocking {
        val now = System.currentTimeMillis()
        store.update(MonitorSettings(heartMonitoring = true, spo2Monitoring = true, skinTempMonitoring = true, updatedAtMs = 1L))
        store.lastTempMs = now
        store.lastSpo2Ms = now

        val worker = { TestListenableWorkerBuilder<VitalsWorker>(context).build() }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, quickStreams)
        assertEquals(0, batchSyncs)

        if (LocalTime.now().hour >= VitalsMonitor.CHECK_HOUR) {
            store.updateVitals { VitalsHistory() }
            shadowOf(context.getSystemService(PowerManager::class.java)!!).setIsPowerSaveMode(true)
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            assertEquals(LocalDate.now().toEpochDay(), store.vitals.lastNightCheck)
            assertEquals(0, quickStreams)
        }
    }
}
