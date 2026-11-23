// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.data

import com.heartline.logging.HLog
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PendingMessage
import com.heartline.shared.sync.PendingRecord
import com.heartline.shared.sync.PendingSession
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.WaveCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Watch-side history entry exposed by [WatchRecordStore] without exposing Room entities. */
data class WatchRecordEntry(val meta: RecordMeta, val delivered: Boolean)

/** Stores finished measurements and serves them to the sync engine as the outbox. */
class WatchRecordStore internal constructor(
    private val dao: WatchRecordDao,
    private val root: File,
    private val messages: MessageDao? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : Outbox {
    private val delivered = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits whenever an item is acknowledged and removed from the outbox. */
    val deliveries: Flow<Unit> = delivered.asSharedFlow()

    val recent: Flow<List<RecordMeta>> = dao.recent().map { list -> list.map { Protocol.json.decodeFromString<RecordMeta>(it.metaJson) } }

    /** Recent watch history entries with delivery status, for launcher history UI. */
    val historyEntries: Flow<List<WatchRecordEntry>> = dao.recent().map { list ->
        list.map { WatchRecordEntry(Protocol.json.decodeFromString<RecordMeta>(it.metaJson), it.delivered) }
    }

    /** A longer history (newest first) for streaks and the user's usual ranges. */
    val history: Flow<List<RecordMeta>> = dao.recent(HISTORY).map { list -> list.map { Protocol.json.decodeFromString<RecordMeta>(it.metaJson) } }

    private fun resolveUnder(subDir: String, relativeOrChild: File): File? {
        val base = File(root, subDir).canonicalFile.toPath()
        val target = relativeOrChild.canonicalFile
        return target.takeIf { it.toPath().startsWith(base) && it.toPath() != base }
    }

    suspend fun add(meta: RecordMeta, wave: FloatArray?) {
        val safeId = Protocol.requireSafeId(meta.id)
        var writtenFile: File? = null
        val path = wave?.let {
            withContext(Dispatchers.IO) {
                val relative = "outbox/$safeId.bin"
                val target = resolveUnder("outbox", File(root, relative))
                    ?: throw IllegalArgumentException("Invalid wave path: $relative")
                val tmp = resolveUnder("outbox", File(root, "outbox/$safeId.tmp"))
                    ?: throw IllegalArgumentException("Invalid wave path: $relative")
                target.parentFile?.mkdirs()
                try {
                    tmp.writeBytes(WaveCodec.encode(meta.kind.ordinal, meta.sampleRateHz, it))
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    tmp.delete()
                }
                writtenFile = target
                relative
            }
        }
        try {
            dao.insert(WatchRecordEntity(safeId, Protocol.json.encodeToString(meta), meta.startedAtMs, path))
        } catch (t: Throwable) {
            writtenFile?.let { file -> withContext(Dispatchers.IO) { file.delete() } }
            throw t
        }
    }

    override suspend fun hasPending(): Boolean =
        dao.hasPending() || (messages?.hasPending() == true) || hasPendingSessions()

    private suspend fun hasPendingSessions(): Boolean = withContext(Dispatchers.IO) {
        File(root, SESSIONS).listFiles { f -> f.extension == "hlbp" }?.isNotEmpty() == true
    }

    override suspend fun pending(): List<OutboxItem> = dao.pending().map { entity ->
        val wave = entity.wavePath?.let { path ->
            withContext(Dispatchers.IO) {
                resolveUnder("outbox", File(root, path))?.takeIf { it.exists() }?.let { WaveCodec.decode(it.readBytes()).samples }
            }
        }
        OutboxItem(Protocol.json.decodeFromString(entity.metaJson), wave)
    }

    override suspend fun pendingRecords(): List<PendingRecord> = dao.pending().map { entity ->
        val openWave: (suspend () -> InputStream?)? = entity.wavePath?.let { path ->
            suspend {
                withContext(Dispatchers.IO) {
                    resolveUnder("outbox", File(root, path))?.takeIf { it.exists() }?.inputStream()
                }
            }
        }
        PendingRecord(Protocol.json.decodeFromString(entity.metaJson), openWave)
    }

    suspend fun enqueueMessage(id: String, path: String, payload: ByteArray) {
        messages?.insert(MessageEntity(id, path, payload, now()))
        // Heart-rate batches go after 7 days; an alert the phone never got is kept for 30.
        val dropped = messages?.deleteOlderThan(now() - 7 * DAY_MS, Protocol.ALERT, now() - 30 * DAY_MS) ?: 0
        if (dropped > 0) HLog.w("Heartline/Sync", "outbox: $dropped old messages dropped (the phone was away)")
    }

    override suspend fun pendingMessages(): List<PendingMessage> =
        messages?.pending().orEmpty().map { PendingMessage(it.id, it.path, it.payload) }

    /**
     * Keeps a raw BP session log until the phone has it (see [BpSessionLog]). Oldest logs are
     * dropped beyond [MAX_SESSION_BYTES] so a watch that is never synced can't fill up.
     */
    suspend fun addSession(log: BpSessionLog) = withContext(Dispatchers.IO) {
        val safeId = Protocol.requireSafeId(log.header.id)
        val dir = File(root, SESSIONS).apply { mkdirs() }
        // Written aside, then renamed: the sync must never read a half-written log (a truncated
        // gzip fails to decode on the phone).
        val tmp = resolveUnder(SESSIONS, File(dir, "$safeId.tmp"))
            ?: throw IllegalArgumentException("Invalid session id: $safeId")
        val dst = resolveUnder(SESSIONS, File(dir, "$safeId.hlbp"))
            ?: throw IllegalArgumentException("Invalid session id: $safeId")
        try {
            tmp.writeBytes(log.encode())
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_SESSION_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    override suspend fun pendingSessions(): List<Pair<String, ByteArray>> = withContext(Dispatchers.IO) {
        File(root, SESSIONS).listFiles().orEmpty().filter { it.extension == "hlbp" }.sortedBy { it.lastModified() }
            .map { it.nameWithoutExtension to it.readBytes() }
    }

    override suspend fun pendingSessionStreams(): List<PendingSession> = withContext(Dispatchers.IO) {
        File(root, SESSIONS).listFiles { f -> f.extension == "hlbp" }
            .orEmpty()
            .sortedBy { it.lastModified() }
            .map { file ->
                PendingSession(file.nameWithoutExtension) {
                    withContext(Dispatchers.IO) { file.takeIf { it.exists() }?.inputStream() }
                }
            }
    }

    override suspend fun markDelivered(id: String) {
        val wavePath = dao.get(id)?.wavePath
        messages?.delete(id)
        dao.markDelivered(id)
        dao.prune()
        withContext(Dispatchers.IO) {
            if (Protocol.isSafeId(id)) {
                resolveUnder(SESSIONS, File(File(root, SESSIONS), "$id.hlbp"))?.delete()
            }
            wavePath?.let { path -> resolveUnder("outbox", File(root, path))?.delete() }
        }
        delivered.tryEmit(Unit)
    }

    suspend fun delete(id: String) {
        val wavePath = dao.get(id)?.wavePath
        dao.delete(id)
        wavePath?.let { path ->
            withContext(Dispatchers.IO) { resolveUnder("outbox", File(root, path))?.delete() }
        }
    }

    companion object {
        const val HISTORY = 400
        const val SESSIONS = "bp-sessions"
        const val MAX_SESSION_BYTES = 64L * 1024 * 1024
        private const val DAY_MS = 86_400_000L
    }
}
