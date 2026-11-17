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
import com.heartline.datalayer.diag.HLog
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
import kotlinx.coroutines.launch
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
        engine.flush()
        // Log segments and raw sensor sessions wait on Power Saver / low battery while off the charger.
        val offloadAllowed = !PowerGuard.shouldThrottleActiveMonitoring(applicationContext) || Charging.isCharging(applicationContext)
        if (offloadAllowed) {
            logs.run()
        } else {
            HLog.i("Heartline/Sync", "log offload deferred: power saver or low battery")
        }
        // Heart-rate batches and alerts wait in the outbox too: retried like records, not left for the next delivery.
        val waiting = store.pending().isNotEmpty() || store.pendingMessages().isNotEmpty()
        return if (!waiting && (!offloadAllowed || !logs.stalled)) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "heartline-sync"

        suspend fun hasPendingWork(context: Context, store: WatchRecordStore): Boolean {
            if (store.pending().isNotEmpty() || store.pendingMessages().isNotEmpty() || store.pendingSessions().isNotEmpty()) return true
            val offloadAllowed = !PowerGuard.shouldThrottleActiveMonitoring(context) || Charging.isCharging(context)
            return offloadAllowed && (HLog.segmentFiles().isNotEmpty() || RawCapture.files().isNotEmpty())
        }

        fun enqueue(context: Context, replace: Boolean = true) {
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
                if (SyncWorker.hasPendingWork(this@WatchSyncService, store)) {
                    SyncWorker.enqueue(this@WatchSyncService, replace = true)
                }
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
                if (SyncWorker.hasPendingWork(this@WatchSyncService, store)) {
                    SyncWorker.enqueue(this@WatchSyncService, replace = true)
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                HLog.w("Heartline/Sync", "channel ${channel.path} failed", e)
            }
        }
    }
}
