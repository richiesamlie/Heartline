// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.heartline.datalayer.DataLayerTransport
import com.heartline.logging.HLog
import com.heartline.shared.diag.LogOffload
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.WatchSyncEngine
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.di.APP_SCOPE
import com.heartline.wear.diag.RawCapture
import com.heartline.wear.monitor.Charging
import com.heartline.wear.monitor.PowerGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Pushes the outbox to the phone; retried with backoff until everything is acked. */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val engine: WatchSyncEngine by inject()
    private val store: WatchRecordStore by inject()
    private val logs: LogOffload by inject()

    override suspend fun doWork(): Result {
        synchronized(stateLock) { running = true }
        try {
            var waiting: Boolean
            var stalled: Boolean
            while (true) {
                val sent = engine.flush()
                if (sent > 0) awaitAcks()
                // Log segments and raw sensor sessions wait on Power Saver / low battery while off the charger.
                val offloadAllowed = !PowerGuard.shouldThrottleActiveMonitoring(applicationContext) || Charging.isCharging(applicationContext)
                if (offloadAllowed) {
                    logs.run()
                } else {
                    HLog.i("Heartline/Sync", "log offload deferred: power saver or low battery")
                }
                // Heart-rate batches and alerts wait in the outbox too: retried like records, not left for the next delivery.
                waiting = hasPendingOutbox(store)
                stalled = offloadAllowed && logs.stalled
                val loop = synchronized(stateLock) {
                    if (rerun && !isStopped) {
                        rerun = false
                        true
                    } else {
                        running = false
                        false
                    }
                }
                if (!loop) break
            }
            return if (!waiting && !stalled) Result.success() else Result.retry()
        } finally {
            synchronized(stateLock) { running = false }
        }
    }

    private suspend fun awaitAcks() {
        if (!hasPendingOutbox(store)) return
        withTimeoutOrNull(ackWaitMs) {
            store.deliveries
                .onStart { emit(Unit) }
                .first { !hasPendingOutbox(store) }
        }
    }

    companion object {
        private const val NAME = "heartline-sync"
        private const val DEFAULT_ACK_WAIT_MS = 2_000L

        internal var ackWaitMs: Long = DEFAULT_ACK_WAIT_MS

        private val stateLock = Any()
        private var running = false
        private var rerun = false

        internal suspend fun hasPendingOutbox(store: WatchRecordStore): Boolean =
            store.pending().isNotEmpty() || store.pendingMessages().isNotEmpty() || store.pendingSessions().isNotEmpty()

        suspend fun hasPendingWork(context: Context, store: WatchRecordStore): Boolean {
            if (hasPendingOutbox(store)) return true
            val offloadAllowed = !PowerGuard.shouldThrottleActiveMonitoring(context) || Charging.isCharging(context)
            return offloadAllowed && (HLog.segmentFiles().isNotEmpty() || RawCapture.files().isNotEmpty())
        }

        internal suspend fun enqueueAfterInbound(context: Context, store: WatchRecordStore) {
            if (hasPendingOutbox(store)) {
                enqueue(context, replace = true)
            } else if (hasPendingWork(context, store)) {
                enqueue(context, replace = false)
            }
        }

        fun enqueue(context: Context, replace: Boolean = true) {
            val shouldEnqueue = synchronized(stateLock) {
                if (running) {
                    // Never cancel an active worker mid-flight (doing so cancels CapabilityClient /
                    // ChannelClient awaits and triggers backoff); ask it to run another pass instead.
                    if (replace) rerun = true
                    false
                } else {
                    true
                }
            }
            if (!shouldEnqueue) return
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}

/** Receives acks and delete requests from the phone. */
class WatchSyncService : WearableListenerService() {
    private val engine: WatchSyncEngine by inject()
    private val transport: DataLayerTransport by inject()
    private val store: WatchRecordStore by inject()
    private val scope: CoroutineScope by inject(APP_SCOPE)

    override fun onMessageReceived(event: MessageEvent) {
        val envelope = Envelope(event.path, event.data)
        scope.launch {
            runCatching {
                transport.deliver(envelope)
                engine.handle(envelope)
                SyncWorker.enqueueAfterInbound(this@WatchSyncService, store)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                HLog.w("Heartline/Sync", "message ${event.path} failed", e)
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        scope.launch {
            runCatching {
                engine.handle(Envelope(channel.path, transport.readChannel(channel)))
                SyncWorker.enqueueAfterInbound(this@WatchSyncService, store)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                HLog.w("Heartline/Sync", "channel ${channel.path} failed", e)
            }
        }
    }
}
