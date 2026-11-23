// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrSample
import com.heartline.shared.irn.IrnState
import com.heartline.shared.model.Metric
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.shared.vitals.TempSample
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.sensor.FakeHrSource
import com.heartline.wear.sensor.HrSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BackgroundArbitrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun preemptCancelsActiveBackgroundWorkWithoutCancellingCaller() = runBlocking {
        val started = AtomicBoolean(false)
        coroutineScope {
            val workerJob = launch(Dispatchers.Default) {
                val result = BackgroundSensors.runPreemptible {
                    started.set(true)
                    awaitCancellation()
                }
                assertNull(result)
            }
            withTimeout(2_000) {
                while (!started.get()) delay(5)
                delay(10)
                BackgroundSensors.preempt("test measurement")
                workerJob.join()
            }
        }
    }

    @Test
    fun updateVitalsPreservesConcurrentUpdates() = runBlocking {
        context.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit().clear().commit()
        val store = WatchSettingsStore(context)
        val day = 20_500L * 86_400_000L
        coroutineScope {
            repeat(2) { worker ->
                launch(Dispatchers.Default) {
                    repeat(20) { i ->
                        val ts = day + (worker * 20 + i) * 60_000L
                        store.updateVitals { current ->
                            val d = current.day(20_500L)
                            current.with(d.copy(temps = d.temps + TempSample(ts, 33.5f, null, HrContext.SLEEP, true).skinC))
                        }
                    }
                }
            }
        }
        assertEquals(40, store.vitals.day(20_500L).temps.size)
    }

    @Test
    fun standProbeWorkerReleasesLockBetweenSlices() = runBlocking {
        val perms = (
            PermissionPolicy.permissionsFor(Metric.HEART_RATE, android.os.Build.VERSION.SDK_INT) +
                BackgroundMonitoring.backgroundPermission
            ).distinct().toTypedArray()
        shadowOf(context as Application).grantPermissions(*perms)
        context.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit().clear().commit()
        val store = WatchSettingsStore(context)
        var clock = System.currentTimeMillis()
        val fake = FakeHrSource(bpm = 96.0, periodMs = 1, now = { clock.also { clock += 1_000 } })
        val observedUnlockedBetweenSlices = AtomicBoolean(false)
        var sliceCount = 0
        val source = object : HrSource {
            override fun stream(): Flow<HrSample> {
                clock += 120_000
                sliceCount++
                assertTrue(BackgroundSensors.lock.isLocked)
                return fake.stream()
            }
        }
        val output = object : MonitorOutput {
            override suspend fun loadIrnState() = IrnState()

            override suspend fun saveIrnState(state: IrnState) = Unit

            override suspend fun enqueueBatch(batch: HrBatch) = Unit

            override suspend fun enqueueAlert(alert: HealthAlert) = Unit

            override fun notify(alert: HealthAlert) = Unit
        }
        val coordinator = RemoteMeasureCoordinator({ true }, {}, CoroutineScope(Dispatchers.Unconfined))
        startKoin {
            modules(
                module {
                    single<HrSource> { source }
                    single { store }
                    single<MonitorOutput> { output }
                    single { WatchNotifier(context) }
                    single { coordinator }
                },
            )
        }
        try {
            val worker = TestListenableWorkerBuilder<StandProbeWorker>(context)
                .setInputData(
                    workDataOf(
                        StandProbeWorker.START_MS to System.currentTimeMillis(),
                        StandProbeWorker.SLICES_MS to longArrayOf(0L, 120L, 240L),
                    ),
                )
                .build()
            coroutineScope {
                val observer = launch(Dispatchers.Default) {
                    while (!observedUnlockedBetweenSlices.get()) {
                        if (sliceCount >= 1 && !BackgroundSensors.lock.isLocked) {
                            observedUnlockedBetweenSlices.set(true)
                            break
                        }
                        delay(5)
                    }
                }
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
                observer.cancel()
            }
            assertEquals(3, sliceCount)
            assertTrue("Expected BackgroundSensors.lock to be released between slices", observedUnlockedBetweenSlices.get())
        } finally {
            stopKoin()
        }
    }
}
