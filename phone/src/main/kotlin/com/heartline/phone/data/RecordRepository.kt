// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import com.heartline.shared.ecg.EcgFilter
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.Symptom
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Persistence-agnostic health measurement record exposed by [RecordRepository]. */
data class HealthRecord(
    val id: String,
    val kind: RecordKind,
    val startedAtMs: Long,
    val durationMs: Long,
    val sampleRateHz: Int,
    val sampleCount: Int,
    val summary: RecordSummary,
    val hasWave: Boolean = false,
    val note: String? = null,
    val receivedAtMs: Long = 0L,
)

class RecordRepository internal constructor(
    private val dao: RecordDao,
    private val waves: WaveStore,
    private val now: () -> Long = System::currentTimeMillis,
) : RecordSink {
    private val writeLock = Mutex()

    fun observe(kind: RecordKind): Flow<List<HealthRecord>> = dao.observe(kind).map { list -> list.map { it.decode() } }

    fun observe(id: String): Flow<HealthRecord?> = dao.observeById(id).map { it?.decode() }

    suspend fun since(kind: RecordKind, fromMs: Long): List<HealthRecord> = dao.since(kind, fromMs).map { it.decode() }

    suspend fun latestSince(kind: RecordKind, fromMs: Long): HealthRecord? = dao.latestSince(kind, fromMs)?.decode()

    suspend fun wave(record: HealthRecord): FloatArray? =
        if (!record.hasWave) null else dao.get(record.id)?.wavePath?.let { path -> withContext(Dispatchers.IO) { waves.read(path) } }

    /** The waveform filtered for display (the watch stores raw sensor values). */
    suspend fun displayWave(record: HealthRecord): FloatArray? = wave(record)?.let { raw ->
        withContext(Dispatchers.Default) {
            if (record.kind == RecordKind.ECG) EcgFilter.clean(raw, record.sampleRateHz) else raw
        }
    }

    override suspend fun contains(id: String) = dao.exists(id) || dao.isDeleted(id)

    override suspend fun save(meta: RecordMeta, wave: FloatArray?) = writeLock.withLock {
        if (dao.exists(meta.id) || dao.isDeleted(meta.id)) return@withLock
        val path = wave?.let { withContext(Dispatchers.IO) { waves.write(meta.id, meta.kind.ordinal, meta.sampleRateHz, it) } }
        try {
            val rowId = dao.insert(
                RecordEntity(
                    id = meta.id,
                    kind = meta.kind,
                    startedAtMs = meta.startedAtMs,
                    durationMs = meta.durationMs,
                    sampleRateHz = meta.sampleRateHz,
                    sampleCount = meta.sampleCount,
                    summaryJson = Protocol.json.encodeToString<RecordSummary>(meta.summary),
                    wavePath = path,
                    receivedAtMs = now(),
                ),
            )
            if (rowId == -1L && path != null) {
                withContext(Dispatchers.IO) { waves.delete(path) }
            }
        } catch (t: Throwable) {
            if (path != null) withContext(Dispatchers.IO) { waves.delete(path) }
            throw t
        }
    }

    suspend fun updateEcgSymptoms(id: String, symptoms: List<Symptom>, note: String?) = writeLock.withLock {
        val entity = dao.get(id) ?: return@withLock
        val summary = entity.decode().summary as? RecordSummary.Ecg ?: return@withLock
        dao.updateSummary(id, Protocol.json.encodeToString<RecordSummary>(summary.copy(symptoms = symptoms)), note)
    }

    /** Replaces a record's summary (the phone refined a blood-pressure reading), keeping its note. */
    suspend fun updateSummary(id: String, summary: RecordSummary) = writeLock.withLock {
        val entity = dao.get(id) ?: return@withLock
        dao.updateSummary(id, Protocol.json.encodeToString<RecordSummary>(summary), entity.note)
    }

    /** Adds the phone's ECG second opinion without overwriting symptoms or note edited in the meantime. */
    suspend fun updateEcgSecondOpinion(id: String, result: EcgResult, afProbability: Double) = writeLock.withLock {
        val entity = dao.get(id) ?: return@withLock
        val current = entity.decode().summary as? RecordSummary.Ecg ?: return@withLock
        val metrics = current.metrics ?: return@withLock
        val updated = current.copy(
            metrics = metrics.copy(secondOpinion = result, secondOpinionAfProbability = afProbability),
        )
        dao.updateSummary(id, Protocol.json.encodeToString<RecordSummary>(updated), entity.note)
    }

    suspend fun get(id: String): HealthRecord? = dao.get(id)?.decode()

    override suspend fun delete(id: String) {
        writeLock.withLock {
            val path = dao.get(id)?.wavePath
            dao.deleteWithTombstone(id, now())
            path?.let { withContext(Dispatchers.IO) { waves.delete(it) } }
        }
    }

    suspend fun deleteDemo(): Int = writeLock.withLock {
        val paths = dao.demoWavePaths()
        val removed = dao.deleteDemo()
        if (paths.isNotEmpty()) {
            withContext(Dispatchers.IO) { paths.forEach { waves.delete(it) } }
        }
        removed
    }

    suspend fun deleteAll() = writeLock.withLock {
        dao.deleteAllAndTombstones()
        withContext(Dispatchers.IO) { waves.deleteAll() }
    }

    suspend fun isEmpty() = dao.count() == 0

    suspend fun all(): List<HealthRecord> = dao.all().map { it.decode() }

    private fun RecordEntity.decode() = HealthRecord(
        id = id,
        kind = kind,
        startedAtMs = startedAtMs,
        durationMs = durationMs,
        sampleRateHz = sampleRateHz,
        sampleCount = sampleCount,
        summary = Protocol.json.decodeFromString<RecordSummary>(summaryJson),
        hasWave = wavePath != null,
        note = note,
        receivedAtMs = receivedAtMs,
    )
}
