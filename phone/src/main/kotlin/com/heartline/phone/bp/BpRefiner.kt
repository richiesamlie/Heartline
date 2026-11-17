// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.bp

import com.heartline.logging.HLog
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpEstimate
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpSessionReplay
import com.heartline.shared.bp.HybridBpModel
import com.heartline.shared.bp.MorphologyEmbedder
import com.heartline.shared.bp.PpgEmbedder
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.dsp.PpgRepair
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Refines incoming blood-pressure readings on the phone using the personal learned model
 * ([HybridBpModel]) once the user's cuff checks beat the classical estimate.
 */
class BpRefiner(
    private val bp: BpRepository,
    private val records: RecordRepository,
    /** Candidate encoders for the personal model; the one with the lowest leave-one-out error wins. */
    private val embedders: () -> List<PpgEmbedder> = { listOf(MorphologyEmbedder) },
) {
    private val sampleCache = ConcurrentHashMap<String, HybridBpModel.Sample>()

    @Volatile
    private var sampleCacheKey: String? = null

    /** Refines and updates [records] when the personal model improves on the watch's reading; returns the final summary. */
    suspend fun refineIfReady(meta: RecordMeta, wave: FloatArray?): RecordSummary.BloodPressure? {
        val summary = meta.summary as? RecordSummary.BloodPressure ?: return null
        val refined = if (wave != null) refine(meta, wave, summary) else null
        if (refined != null) records.updateSummary(meta.id, refined)
        return refined ?: summary
    }

    /** The personal model's refinement of a reading, or null when it isn't yet better than the watch's estimate. */
    internal suspend fun refine(
        meta: RecordMeta,
        wave: FloatArray,
        summary: RecordSummary.BloodPressure,
    ): RecordSummary.BloodPressure? = withContext(Dispatchers.Default) {
        // The personal model learned steady readings; one taken in another state is left as the watch gave it.
        if (summary.rangeOnly || (summary.bodyState != null && summary.bodyState != "STEADY")) return@withContext null
        if (bp.validations.first().count { it.readingId != meta.id } < HybridBpModel.MIN_SAMPLES) return@withContext null
        val fs = meta.sampleRateHz.takeIf { it > 0 } ?: BpCalibration.PPG_FS
        val cal = bp.calibration.first()?.aligned()
        val polarity = cal?.polarity(BpChannel.PWA_GREEN, fs)
        val cleanWave = PpgRepair.repair(wave) ?: wave
        val features = PpgFeatures.extract(cleanWave, fs, polarity) ?: return@withContext null
        val samples = trainingSamples(excluding = meta.id)
        if (samples.size < HybridBpModel.MIN_SAMPLES) return@withContext null
        val model = embedders().mapNotNull { HybridBpModel(it).train(samples, fs) }.minByOrNull { it.looMaeHybrid }
            ?: return@withContext null
        val classical = BpEstimate(summary.systolic, summary.diastolic, summary.pulse ?: 0, summary.uncertainty ?: 0)
        val (sys, dia) = model.correct(classical, features, cleanWave, fs) ?: return@withContext null
        val refinedSd = maxOf(summary.uncertainty ?: 0, ceil(model.looMaeHybrid * 1.2533).toInt())
        val span = cal?.systolicSpan
        val beyond = summary.beyondCalibration ||
            (span != null && (sys < span.first - BpEstimator.BEYOND_DELTA_SYS || sys > span.last + BpEstimator.BEYOND_DELTA_SYS))
        HLog.i(
            TAG,
            "refined ${summary.systolic}/${summary.diastolic} → $sys/$dia " +
                "(LOO ${"%.1f".format(model.looMaeHybrid)} vs ${"%.1f".format(model.looMaeClassical)})",
        )
        summary.copy(
            systolic = sys,
            diastolic = dia,
            uncertainty = refinedSd,
            beyondCalibration = beyond,
            rangeOnly = refinedSd > BpEstimator.RANGE_ONLY_SD,
            algorithm = ALGORITHM_HYBRID,
            watchSystolic = summary.systolic,
            watchDiastolic = summary.diastolic,
        )
    }

    /** Every cuff check whose reading's pulse wave is on the phone. */
    internal suspend fun trainingSamples(excluding: String? = null): List<HybridBpModel.Sample> {
        val cal = bp.calibration.first()?.aligned()
        val validations = bp.validations.first()
        val key = cal?.let { "${it.id}:${it.extraPoints.size}:${validations.size}" } ?: "none:${validations.size}"
        if (sampleCacheKey != key) {
            sampleCache.clear()
            sampleCacheKey = key
        }
        return validations.filter { it.readingId != excluding }.mapNotNull { v ->
            sampleCache[v.readingId]?.let { return@mapNotNull it }
            val record = records.get(v.readingId) ?: return@mapNotNull null
            val s = record.summary as? RecordSummary.BloodPressure ?: return@mapNotNull null
            val rawWave = records.wave(record) ?: return@mapNotNull null
            val wave = PpgRepair.repair(rawWave) ?: rawWave
            val fs = record.sampleRateHz.takeIf { it > 0 } ?: BpCalibration.PPG_FS
            val polarity = cal?.polarity(BpChannel.PWA_GREEN, fs)
            val features = PpgFeatures.extract(wave, fs, polarity) ?: return@mapNotNull null
            // The classical estimate as the current algorithm gives it (algorithm 6.4: the number
            // shown then may have had a correction since removed), from the reading's raw session
            // and the calibration without this check's own point.
            val classical = s.sessionId?.let { bp.session(it) }?.let { log ->
                cal?.let { c ->
                    val own = c.copy(extraPoints = c.extraPoints.filter { it.sessionId != s.sessionId && it.atMs != record.startedAtMs })
                    withContext(Dispatchers.Default) {
                        val result = BpPipeline.run(own, BpSessionReplay.input(log), record.startedAtMs).outcome
                        (result as? com.heartline.shared.bp.BpOutcome.Ok)?.estimate
                    }
                }
            }
            if (s.sessionId != null && classical == null) return@mapNotNull null
            HybridBpModel.Sample(
                features,
                wave,
                classical?.systolic ?: s.watchSystolic ?: s.systolic,
                classical?.diastolic ?: s.watchDiastolic ?: s.diastolic,
                v.cuffSystolic,
                v.cuffDiastolic,
                fs,
            ).also { sampleCache[v.readingId] = it }
        }
    }

    private companion object {
        const val TAG = "Heartline/BP"
        const val ALGORITHM_HYBRID = 4
    }
}
