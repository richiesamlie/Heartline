// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ecg

import com.heartline.logging.HLog
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.ml.OnnxInferenceDispatcher
import com.heartline.phone.ml.OnnxRuntimeLock
import com.heartline.phone.ml.RetryableHolder
import com.heartline.phone.ml.createOrtSessionOptions
import com.heartline.phone.ml.extractModelAsset
import com.heartline.shared.ecg.EcgAnalysis
import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgFounderInput
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.ecg.RhythmFeatures
import com.heartline.shared.ecg.RhythmModel
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import java.nio.FloatBuffer
import kotlinx.coroutines.withContext

/** ECGFounder's 150 label probabilities for a recording (mean over its windows), or null. */
fun interface EcgFounder {
    fun probabilities(windows: List<FloatArray>): DoubleArray?
}

/**
 * ECGFounder single-lead (Li et al., NEJM AI 2025; MIT licence) with ONNX Runtime, bundled as fp16
 * (62 MB): 8-bit quantization distorted its outputs (tools/ml/ecg-ml/README.md). The asset is copied
 * once to app storage so the session maps the file instead of holding a 62 MB array on the heap.
 */
class OnnxEcgFounder private constructor(private val session: OrtSession) :
    EcgFounder,
    AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()

    @Volatile private var closed = false

    override fun probabilities(windows: List<FloatArray>): DoubleArray? {
        if (closed || windows.isEmpty()) return null
        val input = FloatBuffer.allocate(windows.size * EcgFounderInput.WINDOW)
        windows.forEach { input.put(it) }
        input.rewind()
        return runCatching {
            synchronized(OnnxRuntimeLock) {
                if (closed) return@runCatching null
                OnnxTensor.createTensor(env, input, longArrayOf(windows.size.toLong(), 1, EcgFounderInput.WINDOW.toLong())).use { tensor ->
                    session.run(mapOf("ecg" to tensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val out = result[0].value as Array<FloatArray>
                        if (out.isEmpty() || out[0].isEmpty() || out.any { row -> row.any { !it.isFinite() } }) return@use null
                        val avg = DoubleArray(out[0].size) { j -> out.sumOf { it[j].toDouble() } / out.size }
                        avg.takeIf { it.all(Double::isFinite) }
                    }
                }
            }
        }.onFailure { HLog.w(TAG, "ECGFounder failed", it) }.getOrNull()
    }

    override fun close() {
        synchronized(OnnxRuntimeLock) {
            if (!closed) {
                closed = true
                runCatching { session.close() }
            }
        }
    }

    companion object {
        const val ASSET = "ecg/ecgfounder_1lead_fp16.onnx"
        private const val TAG = "Heartline/ECG"

        fun fromAssets(context: Context): OnnxEcgFounder? = runCatching {
            val file = extractModelAsset(context, ASSET)
            createOrtSessionOptions().use { opts ->
                OnnxEcgFounder(OrtEnvironment.getEnvironment().createSession(file.path, opts))
            }
        }.onFailure { HLog.w(TAG, "ECGFounder unavailable: ${it.message}") }.getOrNull()
    }
}

/**
 * The phone's second opinion on a watch ECG: the app's own analysis of the stored recording plus
 * ECGFounder's label probabilities, through a model trained on both (tools/ml/ecg-ml,
 * train_second_opinion.py; cross-validated on CinC 2017, MIT-BIH, AFDB and CPSC 2021 it finds
 * more AFib with fewer false alarms than the watch model on each of them, docs/algorithms/ECG_ALGORITHM.md).
 * Shown next to the watch's result, never instead.
 */
class EcgSecondOpinion internal constructor(
    private val founder: () -> EcgFounder?,
    private val model: () -> RhythmModel?,
    private val onClose: () -> Unit = {},
    private val analyze: (FloatArray, Int, EcgSession) -> EcgAnalysis = EcgAnalyzer::analyze,
) : AutoCloseable {
    constructor(
        founder: () -> EcgFounder?,
        model: () -> RhythmModel?,
        onClose: () -> Unit = {},
    ) : this(founder, model, onClose, EcgAnalyzer::analyze)

    override fun close() = onClose()
    /** @return (result, AFib probability), or null when the model isn't available or the recording can't be read. */
    fun assess(wave: FloatArray, fs: Int, leadOffRatio: Float = 0f, segmentStarts: List<Int> = emptyList()): Pair<EcgResult, Double>? {
        if (fs <= 0) return null
        val f = founder() ?: return null
        val m = model() ?: return null
        val analysis = analyze(wave, fs, EcgSession(leadOffRatio = leadOffRatio, segmentStarts = segmentStarts))
        val bpm = analysis.rawAverageBpm
        if (analysis.signalReason != EcgPoorReason.NONE || bpm == null) {
            return EcgResult.POOR_RECORDING to 0.0
        }
        val probs = f.probabilities(EcgFounderInput.windows(wave, fs, segmentStarts = segmentStarts, inverted = analysis.metrics.inverted)) ?: return null
        val features = inputs(m.features, analysis.features, probs) ?: return null
        val p = m.predict(features)
        val rules = analysis.rules ?: com.heartline.shared.ecg.RhythmClassifier.Decision(analysis.result, analysis.metrics.note)
        val supportsEctopy = m.thresholds.containsKey("sinus_beneath")
        val beats = if (supportsEctopy) analysis.beatCounts.takeIf { com.heartline.shared.ecg.EctopicBeats.bundled != null } else null
        val sinusBeneath = if (supportsEctopy) analysis.sinusBeneath else 0.0
        val decision = EcgAnalyzer.decide(
            m,
            p,
            bpm,
            rules,
            beats,
            sinusBeneath,
            RhythmFeatures.sinusLike(analysis.features),
        )
        val afIdx = m.classes.indexOf("af")
        val afProb = if (afIdx in p.indices) p[afIdx] else 0.0
        return decision.result to afProb
    }

    /** Adds the second opinion to a newly synced ECG record. */
    suspend fun onRecordSaved(records: RecordRepository, meta: RecordMeta, wave: FloatArray?) {
        if (meta.kind != RecordKind.ECG || wave == null) return
        val summary = meta.summary as? RecordSummary.Ecg ?: return
        val metrics = summary.metrics ?: return
        if (metrics.secondOpinion != null) return
        val (result, af) = withContext(OnnxInferenceDispatcher) {
            assess(wave, meta.sampleRateHz, summary.leadOffRatio, metrics.segmentStarts)
        } ?: return
        records.updateEcgSecondOpinion(meta.id, result, af)
    }

    companion object {
        const val MODEL_ASSET = "ecg/second_opinion_model.json"

        /**
         * The model's inputs by name: the app's features it was trained with (algorithm 4 only
         * appended new ones, so a model trained on algorithm 3's still finds all of its own), then
         * ECGFounder's probabilities ("ef_…", in their order). Null if anything is missing.
         */
        fun inputs(names: List<String>, app: DoubleArray, founder: DoubleArray): DoubleArray? {
            val byName = RhythmFeatures.NAMES.zip(app.toList()).toMap()
            val ef = names.count { it.startsWith("ef_") }
            if (ef != founder.size) return null
            var k = 0
            val out = DoubleArray(names.size)
            for ((i, name) in names.withIndex()) out[i] = if (name.startsWith("ef_")) founder[k++] else byName[name] ?: return null
            return out
        }

        fun fromAssets(context: Context): EcgSecondOpinion {
            val founderHolder = RetryableHolder { OnnxEcgFounder.fromAssets(context) }
            val modelHolder = RetryableHolder {
                runCatching {
                    RhythmModel.parse(context.assets.open(MODEL_ASSET).use { it.readBytes().decodeToString() })
                }.onFailure { HLog.w("Heartline/ECG", "second opinion model unavailable", it) }.getOrNull()
            }
            return EcgSecondOpinion(founderHolder::get, modelHolder::get) {
                founderHolder.close()
                modelHolder.close()
            }
        }
    }
}
