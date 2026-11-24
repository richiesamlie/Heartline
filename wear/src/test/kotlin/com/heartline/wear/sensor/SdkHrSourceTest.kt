// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.sdk.HrTrackerHandle
import com.heartline.wear.sensor.sdk.SdkHrSource
import com.heartline.wear.sensor.sdk.SdkSensorGateway
import com.heartline.wear.sensor.sdk.SdkServiceHandle
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.TrackerUserProfile
import com.samsung.android.service.health.tracking.data.Value
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SdkHrSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun synchronousFlushBurstOfOneThousandSamplesIsDeliveredWithoutDropping() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var attachedListener: HealthTracker.TrackerEventListener? = null
        val handle = object : HrTrackerHandle {
            override fun setEventListener(listener: HealthTracker.TrackerEventListener) {
                attachedListener = listener
            }

            override fun unsetEventListener() {
                attachedListener = null
            }

            override fun flush(): Boolean {
                attachedListener?.onFlushCompleted()
                return true
            }
        }
        try {
            val gateway = SdkSensorGateway(
                context = context,
                heartRateInUse = { false },
                measurementsBusy = { false },
                backgroundBusy = { false },
                isForeground = { true },
                connectTimeoutMs = 500L,
                idleDisconnectDelayMs = -1L,
                scope = scope,
            ) { listener, _ ->
                object : SdkServiceHandle {
                    override fun connectService() = listener.onConnectionSuccess()
                    override fun disconnectService() = Unit
                    override fun supportedTrackers(): Pair<Set<TrackerKind>, String?> = setOf(TrackerKind.HEART_RATE_CONTINUOUS) to "1.4.1"
                    override fun getTracker(type: HealthTrackerType): HealthTracker? = null
                    override fun getPpgTracker(types: Set<PpgType>): HealthTracker? = null
                    override fun getTrackerWithProfile(type: HealthTrackerType, profile: TrackerUserProfile): HealthTracker? = null
                }
            }
            val source = SdkHrSource(gateway) { handle }
            val collected = mutableListOf<Int>()
            val collector = launch {
                source.stream().take(1_000).toList().mapTo(collected) { it.bpm }
            }
            withTimeout(5_000) {
                while (!source.isActive || attachedListener == null) delay(10)
            }
            val burst = (0 until 1_000).map { i ->
                DataPoint(
                    mapOf(
                        ValueKey.HeartRateSet.HEART_RATE to Value(60 + (i % 40)),
                        ValueKey.HeartRateSet.HEART_RATE_STATUS to Value(1),
                        ValueKey.HeartRateSet.IBI_LIST to Value(listOf(800)),
                        ValueKey.HeartRateSet.IBI_STATUS_LIST to Value(listOf(0)),
                    ),
                )
            }
            attachedListener!!.onDataReceived(burst)
            withTimeout(5_000) { collector.join() }
            assertEquals(1_000, collected.size)
            assertEquals(60, collected.first())
            assertEquals(60 + (999 % 40), collected.last())
        } finally {
            scope.cancel()
        }
    }
}
