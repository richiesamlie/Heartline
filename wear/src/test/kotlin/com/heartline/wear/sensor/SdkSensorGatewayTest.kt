// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.sdk.SdkSensorGateway
import com.heartline.wear.sensor.sdk.SdkServiceHandle
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.TrackerUserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SdkSensorGatewayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeHandle(
        val listener: ConnectionListener,
        private val onConnect: (FakeHandle) -> Unit = {},
    ) : SdkServiceHandle {
        var disconnected = false
        var trackers: Set<TrackerKind> = emptySet()
        var version: String? = "1.4.1"

        override fun connectService() = onConnect(this)

        override fun disconnectService() {
            disconnected = true
        }

        override fun supportedTrackers(): Pair<Set<TrackerKind>, String?> = trackers to version

        override fun getTracker(type: HealthTrackerType): HealthTracker? = null

        override fun getPpgTracker(types: Set<PpgType>): HealthTracker? = null

        override fun getTrackerWithProfile(type: HealthTrackerType, profile: TrackerUserProfile): HealthTracker? = null
    }

    @Test
    fun timeoutInAwaitConnectedResetsConnectingAndAllowsRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handles = mutableListOf<FakeHandle>()
        try {
            var attempt = 0
            val gateway = SdkSensorGateway(
                context = context,
                heartRateInUse = { false },
                measurementsBusy = { false },
                backgroundBusy = { false },
                isForeground = { true },
                connectTimeoutMs = 40L,
                idleDisconnectDelayMs = -1L,
                scope = scope,
            ) { listener, _ ->
                attempt++
                FakeHandle(listener) { handle ->
                    if (attempt >= 2) {
                        handle.trackers = setOf(TrackerKind.HEART_RATE_CONTINUOUS)
                        handle.listener.onConnectionSuccess()
                    }
                }.also { handles += it }
            }

            assertNull(gateway.awaitConnected(40L))
            assertEquals(GatewayState.Disconnected, gateway.state.value)
            assertTrue(handles.first().disconnected)

            val state = gateway.awaitConnected(500L)
            assertTrue("$state", state is GatewayState.Connected)
            assertEquals(setOf(TrackerKind.HEART_RATE_CONTINUOUS), (state as GatewayState.Connected).trackers)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleConnectionCallbacksDoNotClobberActiveConnection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handles = mutableListOf<FakeHandle>()
        try {
            var attempt = 0
            val gateway = SdkSensorGateway(
                context = context,
                heartRateInUse = { false },
                measurementsBusy = { false },
                backgroundBusy = { false },
                isForeground = { true },
                connectTimeoutMs = 40L,
                idleDisconnectDelayMs = -1L,
                scope = scope,
            ) { listener, _ ->
                attempt++
                FakeHandle(listener) { handle ->
                    if (attempt >= 2) {
                        handle.trackers = setOf(TrackerKind.ECG_ON_DEMAND)
                        handle.version = "2.0"
                        handle.listener.onConnectionSuccess()
                    }
                }.also { handles += it }
            }

            assertNull(gateway.awaitConnected(40L))
            val active = gateway.awaitConnected(500L) as GatewayState.Connected
            assertEquals("2.0", active.serviceVersion)

            val stale = handles.first()
            stale.trackers = emptySet()
            stale.version = "0.1"
            stale.listener.onConnectionSuccess()
            stale.listener.onConnectionEnded()
            stale.listener.onConnectionFailed(HealthTrackerException("stale error"))

            val current = gateway.state.value
            assertTrue("$current", current is GatewayState.Connected)
            assertEquals("2.0", (current as GatewayState.Connected).serviceVersion)
            assertEquals(setOf(TrackerKind.ECG_ON_DEMAND), current.trackers)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun connectServiceThrowingResetsToDisconnected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gateway = SdkSensorGateway(
                context = context,
                heartRateInUse = { false },
                measurementsBusy = { false },
                backgroundBusy = { false },
                isForeground = { true },
                connectTimeoutMs = 40L,
                idleDisconnectDelayMs = -1L,
                scope = scope,
            ) { listener, _ ->
                FakeHandle(listener) { throw IllegalStateException("binder dead") }
            }

            gateway.connect()
            assertEquals(GatewayState.Disconnected, gateway.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun idleDisconnectRunsOnlyInBackgroundAfterLastLease() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var foreground = false
            val gateway = SdkSensorGateway(
                context = context,
                heartRateInUse = { false },
                measurementsBusy = { false },
                backgroundBusy = { false },
                isForeground = { foreground },
                connectTimeoutMs = 200L,
                idleDisconnectDelayMs = 40L,
                scope = scope,
            ) { listener, _ ->
                FakeHandle(listener) { h ->
                    h.trackers = setOf(TrackerKind.HEART_RATE_CONTINUOUS)
                    h.listener.onConnectionSuccess()
                }
            }

            foreground = true
            assertTrue(gateway.awaitConnected() is GatewayState.Connected)
            gateway.acquireLease()
            gateway.releaseLease()
            delay(100)
            assertTrue(gateway.state.value is GatewayState.Connected)

            foreground = false
            gateway.acquireLease()
            delay(100)
            assertTrue(gateway.state.value is GatewayState.Connected)
            gateway.releaseLease()
            delay(150)
            assertEquals(GatewayState.Disconnected, gateway.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun probeHealthReturnsNotSupportedWhenTimedOut() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gateway = SdkSensorGateway(
                context = context,
                heartRateInUse = { false },
                measurementsBusy = { false },
                backgroundBusy = { false },
                isForeground = { false },
                connectTimeoutMs = 30L,
                idleDisconnectDelayMs = -1L,
                scope = scope,
            ) { listener, _ -> FakeHandle(listener) }

            assertEquals(SensorProblem.NOT_SUPPORTED, gateway.probeHealth())
            assertEquals(GatewayState.Disconnected, gateway.state.value)
        } finally {
            scope.cancel()
        }
    }
}
