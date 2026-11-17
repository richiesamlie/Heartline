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
) : RecordSink {
    override suspend fun contains(id: String) = records.contains(id)

    override suspend fun save(meta: RecordMeta, wave: FloatArray?) {
        records.save(meta, wave)
        scope.launch { onRecordSaved(meta, wave) }
    }

    override suspend fun delete(id: String) = records.delete(id)

    /** Runs post-save enrichment and notifications for a newly persisted record. */
    suspend fun onRecordSaved(meta: RecordMeta, wave: FloatArray?) {
        runCatching {
            val final = bpRefiner.refineIfReady(meta, wave)
            if (final != null && final.confirmed && final.safety != BpSafety.NONE) onBpSafety(final)
        }.onFailure { HLog.w("Heartline/BP", "refine failed", it) }
        secondOpinion?.let { opinion ->
            runCatching { opinion.onRecordSaved(records, meta, wave) }
                .onFailure { HLog.w("Heartline/ECG", "second opinion failed", it) }
        }
        profiles?.let { prof ->
            runCatching { prof.onRecordSaved(meta) }
                .onFailure { HLog.w("Heartline/Body", "weight update failed", it) }
        }
    }
}
