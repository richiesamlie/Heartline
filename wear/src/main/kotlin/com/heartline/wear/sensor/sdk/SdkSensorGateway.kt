// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor.sdk

import com.heartline.logging.HLog
import android.app.Activity
import android.content.Context
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.SensorProblem
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.TrackerUserProfile
import com.samsung.android.service.health.tracking.data.DataPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface SdkServiceHandle {
    fun connectService()

    fun disconnectService()

    fun supportedTrackers(): Pair<Set<TrackerKind>, String?>

    fun getTracker(type: HealthTrackerType): HealthTracker?

    fun getPpgTracker(types: Set<PpgType>): HealthTracker?

    fun getTrackerWithProfile(type: HealthTrackerType, profile: TrackerUserProfile): HealthTracker?
}

private class AndroidSdkServiceHandle(
    listener: ConnectionListener,
    context: Context,
) : SdkServiceHandle {
    private val delegate = HealthTrackingService(listener, context)

    override fun connectService() = delegate.connectService()

    override fun disconnectService() = delegate.disconnectService()

    override fun supportedTrackers(): Pair<Set<TrackerKind>, String?> {
        val capability = delegate.trackingCapability
        val trackers = capability?.supportHealthTrackerTypes.orEmpty()
            .mapNotNull { it.name.let { name -> TrackerKind.entries.firstOrNull { kind -> kind.name == name } } }
            .toSet()
        return trackers to capability?.version
    }

    override fun getTracker(type: HealthTrackerType): HealthTracker? = delegate.getHealthTracker(type)

    override fun getPpgTracker(types: Set<PpgType>): HealthTracker? = delegate.getHealthTracker(HealthTrackerType.PPG_ON_DEMAND, types)

    override fun getTrackerWithProfile(type: HealthTrackerType, profile: TrackerUserProfile): HealthTracker? =
        delegate.getHealthTracker(type, profile)
}

/** Health Sensor Service connection via Samsung Health Sensor SDK 1.4.1. */
class SdkSensorGateway internal constructor(
    private val context: Context,
    /** Whether the shared heart-rate tracker is running (see [probeHealth]). */
    private val heartRateInUse: () -> Boolean,
    /** Whether an on-demand measurement is currently active (see [probeHealth]). */
    private val measurementsBusy: () -> Boolean,
    /** Whether a background worker holds the sensors (see [probeHealth]). */
    private val backgroundBusy: () -> Boolean,
    /** Whether the watch UI is in the foreground (skips idle disconnect). */
    private val isForeground: () -> Boolean,
    private val connectTimeoutMs: Long,
    private val idleDisconnectDelayMs: Long,
    private val scope: CoroutineScope,
    private val serviceFactory: (ConnectionListener, Context) -> SdkServiceHandle,
) : SensorGateway {
    constructor(
        context: Context,
        heartRateInUse: () -> Boolean = { false },
        measurementsBusy: () -> Boolean = { false },
        backgroundBusy: () -> Boolean = { false },
        isForeground: () -> Boolean = { false },
    ) : this(
        context = context,
        heartRateInUse = heartRateInUse,
        measurementsBusy = measurementsBusy,
        backgroundBusy = backgroundBusy,
        isForeground = isForeground,
        connectTimeoutMs = CONNECT_TIMEOUT_MS,
        idleDisconnectDelayMs = IDLE_DISCONNECT_DELAY_MS,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        serviceFactory = { listener, app -> AndroidSdkServiceHandle(listener, app) },
    )

    private val mutable = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    override val state: StateFlow<GatewayState> = mutable.asStateFlow()

    @Volatile private var service: SdkServiceHandle? = null

    @Volatile private var lastException: HealthTrackerException? = null

    private var activeLeases = 0
    private var idleDisconnectJob: Job? = null
    internal val hrMonitor = Any()

    @Synchronized
    override fun connect() {
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        if (mutable.value is GatewayState.Connected || mutable.value == GatewayState.Connecting) return
        mutable.value = GatewayState.Connecting
        val previous = service
        service = null
        previous?.let { runCatching { it.disconnectService() } }
        var handle: SdkServiceHandle? = null
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                val activeHandle = synchronized(this@SdkSensorGateway) {
                    if (service == null || service !== handle) return
                    handle
                } ?: return
                val info = runCatching { activeHandle.supportedTrackers() }
                    .onFailure { HLog.w(TAG, "Connected callback failed to read capabilities", it) }
                    .getOrNull()
                synchronized(this@SdkSensorGateway) {
                    if (service !== activeHandle) return
                    if (info == null) {
                        service = null
                        mutable.value = GatewayState.Disconnected
                        runCatching { activeHandle.disconnectService() }
                    } else {
                        HLog.i(TAG, "Connected; service=${info.second} trackers=${info.first}")
                        mutable.value = GatewayState.Connected(info.first, info.second)
                    }
                }
            }

            override fun onConnectionEnded() {
                synchronized(this@SdkSensorGateway) {
                    if (service !== handle) return
                    service = null
                    mutable.value = GatewayState.Disconnected
                }
            }

            override fun onConnectionFailed(e: HealthTrackerException) {
                synchronized(this@SdkSensorGateway) {
                    if (service !== handle) return
                    lastException = e
                    service = null
                    val problem = when (e.errorCode) {
                        HealthTrackerException.PACKAGE_NOT_INSTALLED -> SensorProblem.SERVICE_MISSING
                        HealthTrackerException.OLD_PLATFORM_VERSION -> SensorProblem.SERVICE_OUTDATED
                        else -> SensorProblem.NOT_SUPPORTED
                    }
                    HLog.w(TAG, "Connection failed: code=${e.errorCode} resolvable=${e.hasResolution()}", e)
                    mutable.value = GatewayState.Failed(problem, e.hasResolution())
                }
            }
        }
        try {
            handle = serviceFactory(listener, context.applicationContext)
            service = handle
            handle.connectService()
        } catch (e: Exception) {
            HLog.w(TAG, "connectService failed", e)
            if (service === handle) {
                service = null
                mutable.value = GatewayState.Disconnected
            }
        }
    }

    @Synchronized
    override fun disconnect() {
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        val previous = service
        service = null
        mutable.value = GatewayState.Disconnected
        previous?.let { runCatching { it.disconnectService() } }
    }

    override fun resolve(activity: Activity) {
        lastException?.takeIf { it.hasResolution() }?.resolve(activity)
    }

    internal suspend fun awaitConnected(timeoutMs: Long = connectTimeoutMs): GatewayState? {
        connect()
        val result = withTimeoutOrNull(timeoutMs) {
            state.first { it is GatewayState.Connected || it is GatewayState.Failed }
        }
        if (result == null) {
            HLog.w(TAG, "health service connect timed out after ${timeoutMs}ms; resetting")
            resetIfConnecting()
        }
        return result
    }

    @Synchronized
    private fun resetIfConnecting() {
        if (mutable.value == GatewayState.Connecting) {
            val stuck = service
            service = null
            mutable.value = GatewayState.Disconnected
            stuck?.let { runCatching { it.disconnectService() } }
        }
    }

    @Synchronized
    internal fun acquireLease() {
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        activeLeases++
    }

    @Synchronized
    internal fun releaseLease() {
        activeLeases = (activeLeases - 1).coerceAtLeast(0)
        if (activeLeases == 0 && !isForeground() && idleDisconnectDelayMs >= 0) {
            idleDisconnectJob?.cancel()
            idleDisconnectJob = scope.launch {
                delay(idleDisconnectDelayMs)
                disconnectIfIdle()
            }
        }
    }

    @Synchronized
    private fun disconnectIfIdle() {
        if (activeLeases == 0 && !isForeground() && mutable.value is GatewayState.Connected) {
            disconnect()
        }
    }

    override suspend fun probeHealth(): SensorProblem? {
        val connected = awaitConnected()
        val result = when (connected) {
            null -> SensorProblem.NOT_SUPPORTED
            is GatewayState.Failed -> connected.problem
            // A running heart-rate tracker shows the platform allows it; probing it would replace
            // that listener and cut off a background window or the live screen.
            is GatewayState.Connected -> if (heartRateInUse()) {
                null
            } else {
                // Any tracker surfaces the policy/permission errors; heart rate exists on every Galaxy Watch.
                val kind = PROBE_TRACKERS.firstOrNull {
                    it in connected.trackers &&
                        (!it.onDemand || (!measurementsBusy() && !backgroundBusy())) &&
                        !(it == TrackerKind.HEART_RATE_CONTINUOUS && backgroundBusy())
                }
                val tracker = kind?.let { tracker(it) }
                if (tracker == null) null else probe(tracker, kind)
            }
            else -> SensorProblem.NOT_SUPPORTED
        }
        HLog.i(TAG, "health probe -> ${result ?: "OK"}")
        return result
    }

    /** Listens briefly: an error means a problem; data or silence means the tracker is allowed to run. */
    private suspend fun probe(tracker: HealthTracker, kind: TrackerKind): SensorProblem? {
        acquireLease()
        return try {
            withContext(Dispatchers.Main) {
                val outcome = CompletableDeferred<SensorProblem?>()
                val trackerListener = object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(points: List<DataPoint>) {
                        outcome.complete(null)
                    }

                    override fun onFlushCompleted() = Unit

                    override fun onError(error: HealthTracker.TrackerError) {
                        HLog.w(TAG, "probe tracker error: $error")
                        outcome.complete(mapError(error))
                    }
                }
                if (kind == TrackerKind.HEART_RATE_CONTINUOUS) {
                    synchronized(hrMonitor) {
                        if (heartRateInUse()) return@withContext null
                        tracker.setEventListener(trackerListener)
                    }
                } else {
                    tracker.setEventListener(trackerListener)
                }
                try {
                    withTimeoutOrNull(PROBE_TIMEOUT_MS) { outcome.await() }
                } finally {
                    if (kind == TrackerKind.HEART_RATE_CONTINUOUS) {
                        synchronized(hrMonitor) {
                            if (!heartRateInUse()) runCatching { tracker.unsetEventListener() }
                        }
                    } else if (!kind.onDemand || (!measurementsBusy() && !backgroundBusy())) {
                        runCatching { tracker.unsetEventListener() }
                    }
                }
            }
        } finally {
            releaseLease()
        }
    }

    fun ppgTracker(types: Set<PpgType>): HealthTracker? =
        trackerCall { it.getPpgTracker(types) }

    fun trackerWithProfile(kind: TrackerKind, profile: TrackerUserProfile): HealthTracker? =
        trackerCall { it.getTrackerWithProfile(kind.toSdk(), profile) }

    /** For the tracker sources (P3+); null until connected. */
    fun tracker(kind: TrackerKind): HealthTracker? =
        trackerCall { it.getTracker(kind.toSdk()) }

    private inline fun trackerCall(block: (SdkServiceHandle) -> HealthTracker?): HealthTracker? {
        val current = synchronized(this) { service } ?: return null
        return try {
            block(current)
        } catch (e: IllegalStateException) {
            HLog.w(TAG, "health service binder disconnected; resetting state", e)
            synchronized(this) {
                if (service === current) {
                    service = null
                    mutable.value = GatewayState.Disconnected
                }
            }
            null
        } catch (e: UnsupportedOperationException) {
            HLog.w(TAG, "tracker unsupported on this watch", e)
            null
        } catch (e: IllegalArgumentException) {
            HLog.w(TAG, "invalid tracker arguments", e)
            null
        }
    }

    companion object {
        const val TAG = "Heartline/Sensor"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val IDLE_DISCONNECT_DELAY_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
        private val PROBE_TRACKERS = listOf(TrackerKind.HEART_RATE_CONTINUOUS, TrackerKind.PPG_CONTINUOUS, TrackerKind.ECG_ON_DEMAND)

        fun HealthTrackerType.toKind(): TrackerKind? = TrackerKind.entries.firstOrNull { it.name == name }

        fun TrackerKind.toSdk(): HealthTrackerType = HealthTrackerType.valueOf(name)

        fun mapError(error: HealthTracker.TrackerError): SensorProblem = when (error) {
            HealthTracker.TrackerError.PERMISSION_ERROR -> SensorProblem.PERMISSION
            HealthTracker.TrackerError.SDK_POLICY_ERROR -> SensorProblem.SDK_POLICY
        }
    }
}

/**
 * Samsung Health Sensor SDK's `DataPoint.getValue` indexes its internal map without a null check,
 * throwing `NullPointerException` when an optional or intermediate key is absent.
 */
internal fun <T> DataPoint.getOrNull(key: com.samsung.android.service.health.tracking.data.ValueKey<T>): T? =
    try {
        getValue(key)
    } catch (_: NullPointerException) {
        null
    }
