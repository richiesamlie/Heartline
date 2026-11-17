// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.heartline.shared.diag.LogOffload
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.Ack
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.SyncTransport
import com.heartline.shared.sync.WatchSyncEngine
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
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

@RunWith(RobolectricTestRunner::class)
class WatchSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WatchDatabase
    private lateinit var store: WatchRecordStore
    private lateinit var watchSide: InMemoryTransport
    private lateinit var phoneSide: InMemoryTransport
    private lateinit var engine: WatchSyncEngine
    private val saved = mutableListOf<RecordMeta>()
    private lateinit var testScope: CoroutineScope

    private val sink = object : RecordSink {
        override suspend fun contains(id: String) = saved.any { it.id == id }

        override suspend fun save(meta: RecordMeta, wave: FloatArray?) {
            saved += meta
        }

        override suspend fun delete(id: String) {
            saved.removeAll { it.id == id }
        }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = WatchDatabase.inMemory(context)
        val root = File(context.cacheDir, "watch-sync-test").apply {
            deleteRecursively()
            mkdirs()
        }
        store = WatchRecordStore(db.records(), root, db.messages())
        val (w, p) = InMemoryTransport.pair()
        watchSide = w
        phoneSide = p
        saved.clear()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = WatchSyncEngine(watchSide, store)
        val offload = LogOffload(pending = { emptyList() }, send = { _, _ -> true })
        startKoin {
            modules(
                module {
                    single { store }
                    single { engine }
                    single { offload }
                },
            )
        }
        SyncWorker.ackWaitMs = 2_000L
    }

    @After
    fun tearDown() {
        SyncWorker.ackWaitMs = 2_000L
        testScope.cancel()
        stopKoin()
        db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun hrMeta(id: String, startedAtMs: Long = 1_000L) = RecordMeta(
        id,
        RecordKind.HEART_RATE,
        startedAtMs,
        30_000L,
        0,
        0,
        RecordSummary.HeartRate(72, 66, 78, 30),
    )

    private fun worker() = TestListenableWorkerBuilder<SyncWorker>(context).build()

    @Test
    fun syncWorkerWaitsForAsyncAckAndReturnsSuccessWithoutSpuriousRetry() = runBlocking {
        store.add(hrMeta("hr-1"), null)
        val phone = PhoneSyncEngine(phoneSide, sink)
        val pump = testScope.launch {
            phoneSide.incoming.collect { env ->
                // Simulate realistic BLE round-trip latency (~80 ms) before phone ack returns.
                delay(80)
                phone.handle(env)
            }
        }
        val watchPump = testScope.launch {
            watchSide.incoming.collect { engine.handle(it) }
        }
        try {
            val result = worker().doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(listOf("hr-1"), saved.map { it.id })
            assertTrue(store.pending().isEmpty())
        } finally {
            pump.cancel()
            watchPump.cancel()
        }
    }

    @Test
    fun syncWorkerReturnsRetryWhenPeerOfflineOrAckTimesOut() = runBlocking {
        SyncWorker.ackWaitMs = 100L
        store.add(hrMeta("hr-offline"), null)
        phoneSide.connected = false

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(1, store.pending().size)

        // Connected, but phone never sends ack before ackWaitMs expires.
        phoneSide.connected = true
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test
    fun enqueueWhileRunningRequestsSecondPassInsteadOfCancellingWorker() = runBlocking {
        val firstFlushStarted = CompletableDeferred<Unit>()
        val releaseFirstFlush = CompletableDeferred<Unit>()
        val flushedIds = mutableListOf<String>()

        val gatingTransport = object : SyncTransport by watchSide {
            override suspend fun send(path: String, data: ByteArray): Boolean {
                if (path == Protocol.RECORD_META) {
                    val meta = Protocol.json.decodeFromString<RecordMeta>(data.decodeToString())
                    flushedIds += meta.id
                    if (meta.id == "hr-a" && !firstFlushStarted.isCompleted) {
                        firstFlushStarted.complete(Unit)
                        releaseFirstFlush.await()
                    }
                    store.markDelivered(meta.id)
                    return true
                }
                return watchSide.send(path, data)
            }
        }
        stopKoin()
        val customEngine = WatchSyncEngine(gatingTransport, store)
        val offload = LogOffload(pending = { emptyList() }, send = { _, _ -> true })
        startKoin {
            modules(
                module {
                    single { store }
                    single { customEngine }
                    single { offload }
                },
            )
        }

        store.add(hrMeta("hr-a", 1_000L), null)
        val runningJob = async(Dispatchers.Default) { worker().doWork() }
        firstFlushStarted.await()

        // A second item arrives while SyncWorker is mid-flight: enqueue must NOT schedule a cancelling
        // WorkManager replacement; it marks rerun so the active worker drains hr-b on its next pass.
        store.add(hrMeta("hr-b", 2_000L), null)
        SyncWorker.enqueue(context, replace = true)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("heartline-sync").get().isEmpty())

        releaseFirstFlush.complete(Unit)
        assertEquals(ListenableWorker.Result.success(), runningJob.await())
        assertEquals(listOf("hr-a", "hr-b"), flushedIds)
        assertTrue(store.pending().isEmpty())
    }
}
