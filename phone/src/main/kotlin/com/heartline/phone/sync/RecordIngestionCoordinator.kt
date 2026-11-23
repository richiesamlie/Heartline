// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.sync

import com.heartline.logging.HLog
import com.heartline.phone.bp.BpRefiner
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.ecg.EcgSecondOpinion
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.RecordSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persists incoming watch records in [RecordRepository] and triggers asynchronous post-save enrichment
 * (BP personal refinement + safety alerts, ECG second opinion, and body-weight profile updates).
 */
class RecordIngestionCoordinator(
    private val records: RecordRepository,
    private val bpRefiner: BpRefiner,
    private val secondOpinion: EcgSecondOpinion? = null,
    private val profiles: ProfileRepository? = null,
    private val onBpSafety: (RecordSummary.BloodPressure) -> Unit = {},
    private val scope: CoroutineScope,
    private val enqueueEnrichment: ((recordId: String, notifiedSafety: BpSafety?) -> Unit)? = null,
) : RecordSink {
    override suspend fun contains(id: String) = records.contains(id)

    override suspend fun save(meta: RecordMeta, wave: FloatArray?) {
        records.save(meta, wave)
        val watchBp = meta.summary as? RecordSummary.BloodPressure
        val immediateSafety = watchBp?.takeIf { it.confirmed && it.safety != BpSafety.NONE }?.let {
            onBpSafety(it)
            it.safety
        }
        val enqueue = enqueueEnrichment
        if (enqueue != null) {
            enqueue(meta.id, immediateSafety)
        } else {
            scope.launch { onRecordSaved(meta, wave, alreadyNotifiedBpSafety = immediateSafety) }
        }
    }

    override suspend fun delete(id: String) = records.delete(id)

    /** Loads a persisted record and its waveform by ID and runs post-save enrichment (called from [RecordEnrichmentWorker]). */
    suspend fun enrich(recordId: String, alreadyNotifiedBpSafety: BpSafety? = null) {
        val record = records.get(recordId) ?: return
        val wave = records.wave(record)
        val meta = RecordMeta(
            id = record.id,
            kind = record.kind,
            startedAtMs = record.startedAtMs,
            durationMs = record.durationMs,
            sampleRateHz = record.sampleRateHz,
            sampleCount = record.sampleCount,
            summary = record.summary,
        )
        onRecordSaved(meta, wave, alreadyNotifiedBpSafety = alreadyNotifiedBpSafety)
    }

    /** Runs post-save enrichment and notifications for a newly persisted record. */
    suspend fun onRecordSaved(meta: RecordMeta, wave: FloatArray?, alreadyNotifiedBpSafety: BpSafety? = null) {
        runCatching {
            val final = bpRefiner.refineIfReady(meta, wave)
            if (final != null && final.confirmed && final.safety != BpSafety.NONE && final.safety != alreadyNotifiedBpSafety) {
                onBpSafety(final)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            HLog.w("Heartline/BP", "refine failed", it)
        }
        secondOpinion?.let { opinion ->
            runCatching { opinion.onRecordSaved(records, meta, wave) }
                .onFailure {
                    if (it is CancellationException) throw it
                    HLog.w("Heartline/ECG", "second opinion failed", it)
                }
        }
        profiles?.let { prof ->
            runCatching { prof.onRecordSaved(meta) }
                .onFailure {
                    if (it is CancellationException) throw it
                    HLog.w("Heartline/Body", "weight update failed", it)
                }
        }
    }
}
