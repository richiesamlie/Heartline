// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.ecg

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import com.heartline.shared.dsp.forwardFillNonFinite
import com.heartline.shared.hr.RrFeatures
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * The recording-level features the learned rhythm model reads. Computed by the same Kotlin code
 * on the watch, the phone and in training (tools/ml/ecg-eval), so they can't drift apart.
 */
object RhythmFeatures {
    val NAMES = listOf(
        "bpm", "nrmssd", "entropy", "tpr", "cov", "clean_nrmssd", "clean_entropy", "clean_cov", "clean_share",
        "p_ratio", "ectopic_share", "other_shape_share", "patterned", "noise", "usable_share", "valid_beat_share",
        "mean_b", "median_ln_k", "sd1_sd2", "near_median_share", "max_min_ratio", "pauses", "qrs_width_ms", "amplitude_mv",
        // Algorithm 4: what kind of early beats, wave timing, atrial activity, how much rhythm was seen.
        "early_other_shape_share", "early_corr", "early_width_diff_ms", "compensatory_ratio",
        "qrs_ms", "pr_ms", "atrial_peak_hz", "atrial_concentration", "atrial_ratio", "intervals", "lag1",
        "s_beat_share", "v_beat_share"
    )

    /** What the early beats look like, from the beat clusters. */
    data class Ectopy(
        /** Correlation of each beat with the dominant template. */
        val dominantCorrelation: DoubleArray,
        /** QRS width of each beat's cluster minus the dominant one's, ms. */
        val widthDiffMs: DoubleArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ectopy) return false
            return dominantCorrelation.contentEquals(other.dominantCorrelation) &&
                widthDiffMs.contentEquals(other.widthDiffMs)
        }

        override fun hashCode(): Int = 31 * dominantCorrelation.contentHashCode() + widthDiffMs.contentHashCode()

        companion object {
            val NONE = Ectopy(DoubleArray(0), DoubleArray(0))
        }
    }

    fun of(
        bpm: Int?,
        runs: List<List<RrInterval>>,
        premature: Set<Int>,
        otherShape: Set<Int>,
        pRatio: Double?,
        patterned: Boolean,
        noise: Double,
        usableShare: Double,
        validBeatShare: Double,
        sqis: List<WindowSqi?>,
        pauses: Int,
        qrsWidthMs: Double,
        amplitudeMv: Double,
        ectopy: Ectopy = Ectopy.NONE,
        timing: EcgMorphology.Timing? = null,
        atrial: AtrialActivity.Spectrum = AtrialActivity.NONE,
        beatCounts: EctopicBeats.Counts = EctopicBeats.Counts.NONE
    ): DoubleArray {
        val all = runs.flatten().map { it.ms }
        val f = RrFeatures.of(all)
        val clean = RhythmClassifier.filtered(runs, premature)
        val c = RrFeatures.of(clean)
        val v = sqis.filterNotNull()
        val pairs = runs.flatMap { run -> run.zipWithNext { a, b -> a.ms to b.ms } }
        val sd1sd2 = if (pairs.size < 3) {
            0.0
        } else {
            val d = pairs.map { (a, b) -> (b - a) / sqrt(2.0) }
            val s = pairs.map { (a, b) -> (b + a) / sqrt(2.0) }
            sd(d) / sd(s).coerceAtLeast(1e-9)
        }
        val median = all.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val early = premature.toList()
        // Short interval + the next one against twice the median: 1 for a full compensatory pause
        // (typical after a ventricular beat), less when the sinus node was reset (atrial beats).
        val compensatory = runs.flatMap { run ->
            run.indices.filter { run[it].beat in premature && it + 1 < run.size }.map { (run[it].ms + run[it + 1].ms) / (2 * median) }
        }
        return doubleArrayOf(
            bpm?.toDouble() ?: 0.0,
            f?.nRmssd ?: 0.0,
            f?.shannonEntropy ?: 0.0,
            f?.turningPointRatio ?: 0.0,
            f?.cov ?: 0.0,
            c?.nRmssd ?: 0.0,
            c?.shannonEntropy ?: 0.0,
            c?.cov ?: 0.0,
            if (all.isEmpty()) 0.0 else clean.size.toDouble() / all.size,
            pRatio ?: -1.0,
            premature.size.toDouble() / (all.size + 1),
            otherShape.size.toDouble() / (all.size + 1),
            if (patterned) 1.0 else 0.0,
            noise,
            usableShare,
            validBeatShare,
            if (v.isEmpty()) 0.0 else v.map { it.b }.average(),
            if (v.isEmpty()) 0.0 else EcgQuality.median(v.map { ln(it.k.coerceAtLeast(1e-3)) }),
            sd1sd2,
            if (all.isEmpty()) 0.0 else all.count { abs(it - median) <= 15.0 }.toDouble() / all.size,
            if (all.isEmpty()) 0.0 else all.max() / all.min(),
            pauses.toDouble(),
            qrsWidthMs,
            amplitudeMv,
            premature.count { it in otherShape }.toDouble() / (all.size + 1),
            if (early.isEmpty()) 1.0 else medianOf(early.mapNotNull { ectopy.dominantCorrelation.getOrNull(it) }, 1.0),
            if (early.isEmpty()) 0.0 else medianOf(early.mapNotNull { ectopy.widthDiffMs.getOrNull(it) }, 0.0),
            medianOf(compensatory, 0.0),
            timing?.qrsMs ?: 0.0,
            timing?.prMs ?: -1.0,
            atrial.peakHz,
            atrial.concentration,
            atrial.ratio,
            all.size.toDouble(),
            f?.lag1 ?: 0.0,
            beatCounts.supraventricular.toDouble() / beatCounts.classified.coerceAtLeast(1),
            beatCounts.ventricular.toDouble() / beatCounts.classified.coerceAtLeast(1)
        )
    }

    /**
     * Regular (after removing early beats) with a P wave before the beats: what names a fast or
     * slow rate "sinus" (tools/ml/ecg-eval/evaluate_short.py checks it on 10 s databases).
     */
    fun sinusLike(features: DoubleArray): Boolean {
        val at = { name: String -> features[NAMES.indexOf(name)] }
        val cleanShare = at("clean_share")
        return (at("intervals") * cleanShare).roundToInt() >= 3 &&
            cleanShare >= 0.5 &&
            at("p_ratio") >= RhythmClassifier.P_WAVE_PRESENT_RATIO &&
            at("clean_nrmssd") < 0.08 &&
            at("clean_cov") < 0.10
    }

    private fun medianOf(v: List<Double>, empty: Double) = if (v.isEmpty()) empty else v.sorted()[v.size / 2]

    private fun sd(x: List<Double>): Double {
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1).coerceAtLeast(1))
    }
}

/**
 * Gradient-boosted decision trees over [RhythmFeatures], trained offline on PhysioNet/CinC 2017
 * and MIT-BIH (tools/ml/ecg-eval/train_rhythm.py) and shipped as a JSON resource. Classes:
 * normal, AF, other rhythm, noisy. Pure Kotlin, so the watch runs it too.
 */
@Serializable
class RhythmModel(
    val features: List<String>,
    val classes: List<String>,
    val learningRate: Double,
    val init: List<Double>,
    /** trees[round][class] */
    val trees: List<List<Tree>>,
    /** Decision thresholds chosen by cross-validation (see train_rhythm.py). */
    val thresholds: Map<String, Double> = emptyMap(),
    val info: String = "",
    /** Post-fit temperature scaling (> 0) and per-class logit offsets for calibrated probabilities. */
    val temperature: Double = 1.0,
    val logitBias: List<Double> = emptyList()
) {
    @Transient
    private var featureIndexMap: IntArray? = null

    @Serializable
    class Tree(val feature: IntArray, val threshold: DoubleArray, val left: IntArray, val right: IntArray, val value: DoubleArray) {
        fun predict(x: DoubleArray, featureMap: IntArray? = null): Double {
            var n = 0
            while (left[n] >= 0) {
                val f = feature[n]
                val idx = featureMap?.get(f) ?: f
                n = if (x[idx] <= threshold[n]) left[n] else right[n]
            }
            return value[n]
        }
    }

    /** Class probabilities in [classes] order. */
    fun predict(x: DoubleArray): DoubleArray {
        if (classes.isEmpty()) return DoubleArray(0)
        val map = featureIndexMap
        val raw = DoubleArray(classes.size) { init[it] }
        for (round in trees) for (k in round.indices) raw[k] += learningRate * round[k].predict(x, map)
        val temp = temperature.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        if (logitBias.isNotEmpty() || temp != 1.0) {
            for (k in raw.indices) {
                raw[k] = (raw[k] + logitBias.getOrElse(k) { 0.0 }) / temp
            }
        }
        val max = raw.max()
        val e = raw.map { exp(it - max) }
        val sum = e.sum()
        return DoubleArray(raw.size) { e[it] / sum }
    }

    internal fun bindFeatures(availableFeatures: List<String>): RhythmModel? {
        if (features.isEmpty()) return null
        val indexOf = availableFeatures.withIndex().associate { (i, name) -> name to i }
        val mapping = IntArray(features.size) { i -> indexOf[features[i]] ?: return null }
        val n = features.size
        if (trees.any { round -> round.any { tree -> tree.feature.any { it !in 0 until n } } }) return null
        featureIndexMap = if (features == availableFeatures) null else mapping
        return this
    }

    fun probability(x: DoubleArray, cls: String): Double {
        val idx = classes.indexOf(cls)
        if (idx !in classes.indices) return 0.0
        val p = predict(x)
        return if (idx in p.indices) p[idx] else 0.0
    }

    companion object {
        data class LoadFailure(val path: String, val reason: String, val error: Throwable? = null)

        private val json = Json { ignoreUnknownKeys = true }
        private val failures = CopyOnWriteArrayList<LoadFailure>()

        @Volatile
        var onLoadFailure: (LoadFailure) -> Unit = {}

        fun loadFailures(): List<LoadFailure> = failures.toList()

        internal fun clearLoadFailures() {
            failures.clear()
        }

        private fun reportFailure(path: String, reason: String, error: Throwable? = null): Nothing? {
            val failure = LoadFailure(path, reason, error)
            failures += failure
            onLoadFailure(failure)
            return null
        }

        fun parse(text: String): RhythmModel = json.decodeFromString(text)

        /** The bundled model, or null if it isn't there (the rules alone then decide). */
        val bundled: RhythmModel? by lazy { resource("/ecg/rhythm_model.json", RhythmFeatures.NAMES) }

        /** A model bundled as a resource, if it is there and reads a subset of [features]. */
        internal fun resource(path: String, features: List<String>): RhythmModel? {
            val stream = RhythmModel::class.java.getResourceAsStream(path) ?: return reportFailure(path, "missing resource")
            val model = try {
                stream.use { json.decodeFromString<RhythmModel>(it.readBytes().decodeToString()) }
            } catch (t: Throwable) {
                return reportFailure(path, "decode failed: ${t.message ?: t::class.simpleName}", t)
            }
            return model.bindFeatures(features) ?: reportFailure(
                path,
                "feature mismatch (expected subset of ${features.size}, got ${model.features.size})"
            )
        }
    }
}

/**
 * Input windows for ECGFounder (Li et al., NEJM AI 2025): 500 Hz, 0.5 Hz HPF + 50/60 Hz notch
 * (Q 30), per contact segment, polarity-aligned, up to three 10 s windows, each z-scored
 * (`sd > 1e-6`) — as in tools/ml/ecg-ml/ecgfounder_features.py.
 */
object EcgFounderInput {
    const val FS = 500
    const val WINDOW = 10 * FS

    fun windows(
        raw: FloatArray,
        fs: Int,
        max: Int = 3,
        segmentStarts: List<Int> = emptyList(),
        inverted: Boolean = false
    ): List<FloatArray> {
        if (fs <= 0 || raw.size < 2 || max <= 0) return emptyList()
        val out = mutableListOf<FloatArray>()
        val hpf = Biquad.highPass(0.5, FS.toDouble())
        val n50 = Biquad.iirNotch(50.0, FS.toDouble(), 30.0)
        val n60 = Biquad.iirNotch(60.0, FS.toDouble(), 30.0)
        for (seg in EcgAnalyzer.segments(segmentStarts, raw.size)) {
            if (out.size >= max) break
            val segLen = seg.last - seg.first + 1
            if (segLen < 2) continue
            val finite = raw.copyOfRange(seg.first, seg.last + 1).forwardFillNonFinite()
            if (inverted) for (i in finite.indices) finite[i] = -finite[i]
            val x = if (fs == FS) {
                finite
            } else {
                FloatArray((finite.size.toLong() * FS / fs).toInt()) { k ->
                    val pos = k.toDouble() * fs / FS
                    val i = pos.toInt().coerceAtMost(finite.size - 2)
                    (finite[i] + (finite[i + 1] - finite[i]) * (pos - i)).toFloat()
                }
            }
            if (x.size < WINDOW) continue
            val cleaned = x.filtFilt(hpf, n50, n60)
            var start = 0
            while (start + WINDOW <= cleaned.size && out.size < max) {
                val w = cleaned.copyOfRange(start, start + WINDOW)
                start += WINDOW
                if (w.any { !it.isFinite() }) continue
                val mean = w.average()
                val sd = sqrt(w.sumOf { (it - mean) * (it - mean) } / w.size)
                if (!sd.isFinite() || sd <= 1e-6) continue
                out += FloatArray(WINDOW) { ((w[it] - mean) / sd).toFloat() }
            }
        }
        return out
    }
}
