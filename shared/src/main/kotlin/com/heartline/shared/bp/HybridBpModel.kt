// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import com.heartline.shared.dsp.forwardFillNonFinite
import kotlin.math.abs
import kotlin.math.roundToInt

/** Turns one recording into a fixed-length vector for the learned model. */
fun interface PpgEmbedder {
    fun embed(ppg: FloatArray?, fs: Int, features: PpgFeatureVector): DoubleArray?
}

/**
 * Default embedder, pure Kotlin: the 32-point ensemble beat, its slope at 16 points, and the
 * core timing features scaled to similar ranges. The phone can swap in a pretrained PPG encoder
 * (PaPaGei, see tools/ml/bp-ml) behind the same interface.
 */
object MorphologyEmbedder : PpgEmbedder {
    override fun embed(ppg: FloatArray?, fs: Int, features: PpgFeatureVector): DoubleArray? {
        val shape = features.shape
        if (shape.size != PpgFeatureVector.SHAPE_POINTS) return null
        val slope = List(16) { k ->
            val i = 1 + k * (shape.size - 3) / 15
            ((shape[i + 1] - shape[i - 1]) * 4).toDouble()
        }
        val timing = listOf(
            features.heartRateBpm / 60.0,
            features.upstrokeMs / 100.0,
            features.width50Ms / 300.0,
            features.reflectionDelayMs / 300.0,
            features.reflectionIndex
        )
        return (shape.map { it.toDouble() } + slope + timing).toDoubleArray()
    }
}

/**
 * Personal learned correction on top of the calibrated estimator (the "head" of the hybrid model).
 *
 * Target: cuff − classical estimate on this user's cuff checks, from an embedding of the pulse
 * wave. Ridge regression with the penalty chosen by leave-one-out (LOO) error, so with the few
 * samples one person has it stays close to "no correction". It is used only when its LOO error
 * beats the classical estimator's by [GATE] on this user's own data; otherwise the classical
 * estimate stands. Every learned model in the literature degrades on people and states it
 * wasn't trained on, so the gate is the point.
 */
class HybridBpModel(private val embedder: PpgEmbedder = MorphologyEmbedder) {
    /** One cuff check: the recording, the classical estimate made before this cuff reading was known, the cuff. */
    data class Sample(
        val features: PpgFeatureVector,
        val ppg: FloatArray?,
        val classicalSys: Int,
        val classicalDia: Int,
        val cuffSys: Int,
        val cuffDia: Int,
        val fs: Int = BpCalibration.PPG_FS
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Sample) return false
            val ppgMatches = ppg === other.ppg || (ppg != null && other.ppg != null && ppg.contentEquals(other.ppg))
            return features == other.features &&
                ppgMatches &&
                classicalSys == other.classicalSys &&
                classicalDia == other.classicalDia &&
                cuffSys == other.cuffSys &&
                cuffDia == other.cuffDia &&
                fs == other.fs
        }

        override fun hashCode(): Int {
            var result = features.hashCode()
            result = 31 * result + (ppg?.contentHashCode() ?: 0)
            result = 31 * result + classicalSys
            result = 31 * result + classicalDia
            result = 31 * result + cuffSys
            result = 31 * result + cuffDia
            result = 31 * result + fs
            return result
        }
    }

    class Trained internal constructor(
        private val embedder: PpgEmbedder,
        private val fs: Int,
        private val mean: DoubleArray,
        private val sysWeights: DoubleArray,
        private val diaWeights: DoubleArray,
        private val sysBias: Double,
        private val diaBias: Double,
        val lambda: Double,
        val looMaeClassical: Double,
        val looMaeHybrid: Double,
        /** LOO absolute errors of the hybrid, for a conformal interval. */
        val looErrors: List<Double>,
        private val trainCentred: List<DoubleArray> = emptyList(),
        private val kInv: Array<DoubleArray>? = null,
        private val scale: Double = 1.0,
        private val maxTrainNormSq: Double = 0.0,
        private val maxTrainOrth: Double = 0.0
    ) {
        /** Scale-invariant leverage / out-of-distribution shrinkage factor in (0, 1]. */
        internal fun oodShrink(x: DoubleArray): Double {
            if (kInv == null || trainCentred.isEmpty()) return 1.0
            val normSq = x.sumOf { it * it }
            val denom = maxOf(maxTrainNormSq, scale).coerceAtLeast(1e-12)
            val excessNorm = maxOf(0.0, normSq / denom - OOD_NORM_TOLERANCE)
            val kVec = DoubleArray(trainCentred.size) { i ->
                val ci = trainCentred[i]
                ci.indices.sumOf { ci[it] * x[it] }
            }
            var quad = 0.0
            for (i in kVec.indices) {
                var rowDot = 0.0
                for (j in kVec.indices) rowDot += kInv[i][j] * kVec[j]
                quad += kVec[i] * rowDot
            }
            val orthRatio = maxOf(0.0, (normSq - quad) / scale.coerceAtLeast(1e-12))
            val excessOrth = maxOf(0.0, orthRatio - maxOf(1.0, 2.0 * maxTrainOrth))
            return 1.0 / (1.0 + excessNorm + excessOrth)
        }

        /** @return the corrected (systolic, diastolic), or null when the recording can't be embedded. */
        fun correct(classical: BpEstimate, features: PpgFeatureVector, ppg: FloatArray?, fs: Int = this.fs): Pair<Int, Int>? {
            val e = embedder.embed(ppg, fs, features) ?: return null
            val x = DoubleArray(e.size) { e[it] - mean[it] }
            val shrink = oodShrink(x)
            val ds = shrink * (sysBias + x.indices.sumOf { sysWeights[it] * x[it] })
            val dd = shrink * (diaBias + x.indices.sumOf { diaWeights[it] * x[it] })
            // Rounded, not truncated (truncation moved every correction towards zero, by up to 1 mmHg).
            val sys = (classical.systolic + ds.coerceIn(-MAX_CORRECTION, MAX_CORRECTION)).roundToInt().coerceIn(BpEstimator.SYSTOLIC_LIMITS)
            val dia = (
                classical.diastolic + dd.coerceIn(
                    -MAX_CORRECTION,
                    MAX_CORRECTION
                )
                ).roundToInt().coerceIn(BpEstimator.DIASTOLIC_LIMITS)
            return sys to dia.coerceAtMost(sys - 15)
        }
    }

    /** @return a model that beat the classical estimator on nested LOO, or null (use the classical estimate). */
    fun train(samples: List<Sample>, fs: Int = BpCalibration.PPG_FS): Trained? = trainBest(listOf(embedder), samples, fs)

    private class Ridge(
        val mean: DoubleArray,
        val weights: DoubleArray,
        val bias: Double,
        val centred: List<DoubleArray>,
        val kInv: Array<DoubleArray>?,
        val scale: Double,
        val maxTrainNormSq: Double,
        val maxTrainOrth: Double
    ) {
        fun predict(e: DoubleArray) = bias + e.indices.sumOf { weights[it] * (e[it] - mean[it]) }
    }

    companion object {
        const val MIN_SAMPLES = 12

        /** The hybrid must also cut the LOO systolic error by at least this much, mmHg. */
        const val MIN_GAIN_MMHG = 1.5

        /** The hybrid must cut the LOO error by at least 10 %. */
        const val GATE = 0.9
        const val MAX_CORRECTION = 25.0
        private const val OOD_NORM_TOLERANCE = 1.5
        private const val BIAS_LAMBDA = 1.0
        private const val INNER_FOLDS = 5
        private val LAMBDAS = doubleArrayOf(0.03, 0.1, 0.3, 1.0, 3.0, 10.0, 30.0, 100.0)

        /**
         * Jointly evaluates candidate [embedders] via nested LOO so selecting both the embedder
         * and ridge penalty cannot overfit the leave-one-out gate. Each sample is embedded once per
         * candidate embedder upfront.
         */
        fun trainBest(embedders: List<PpgEmbedder>, samples: List<Sample>, fs: Int = BpCalibration.PPG_FS): Trained? {
            if (embedders.isEmpty() || samples.size < MIN_SAMPLES) return null
            val embeddedByCandidate = embedders.mapNotNull { emb ->
                val vecs = samples.map { s -> emb.embed(s.ppg, s.fs, s.features) }
                val valid = vecs.filterNotNull()
                if (valid.size < MIN_SAMPLES) return@mapNotNull null
                val dims = valid.first().size
                if (dims == 0 || valid.any { it.size != dims }) return@mapNotNull null
                emb to vecs
            }
            if (embeddedByCandidate.isEmpty()) return null
            val common = samples.indices.filter { i -> embeddedByCandidate.all { (_, vecs) -> vecs[i] != null } }
            val activeCandidates: List<Pair<PpgEmbedder, List<DoubleArray>>>
            val activeSamples: List<Sample>
            if (common.size >= MIN_SAMPLES) {
                activeSamples = common.map { samples[it] }
                activeCandidates = embeddedByCandidate.map { (emb, vecs) -> emb to common.map { vecs[it]!! } }
            } else {
                val (emb, vecs) = embeddedByCandidate.maxBy { (_, v) -> v.count { it != null } }
                val idx = samples.indices.filter { vecs[it] != null }
                if (idx.size < MIN_SAMPLES) return null
                activeSamples = idx.map { samples[it] }
                activeCandidates = listOf(emb to idx.map { vecs[it]!! })
            }

            val n = activeSamples.size
            val ySys = activeSamples.map { (it.cuffSys - it.classicalSys).toDouble() }
            val yDia = activeSamples.map { (it.cuffDia - it.classicalDia).toDouble() }
            val classicalMae = ySys.map { abs(it) }.average()
            val diaBaselineMae = yDia.map { abs(it) }.average()

            val sysErrors = mutableListOf<Double>()
            val diaErrors = mutableListOf<Double>()
            for (out in 0 until n) {
                val keep = (0 until n).filter { it != out }
                val keepSys = keep.map { ySys[it] }
                val keepDia = keep.map { yDia[it] }
                val (candIdx, lamSys) = selectCandidateAndLambda(
                    activeCandidates.map { (_, xs) -> keep.map { xs[it] } },
                    keepSys
                ) ?: return null
                val chosenXs = activeCandidates[candIdx].second
                val keepXs = keep.map { chosenXs[it] }
                val fittedSys = ridge(keepXs, keepSys, lamSys)
                sysErrors += abs(ySys[out] - fittedSys.predict(chosenXs[out]))

                val lamDia = selectLambda(keepXs, keepDia)
                val fittedDia = ridge(keepXs, keepDia, lamDia)
                diaErrors += abs(yDia[out] - fittedDia.predict(chosenXs[out]))
            }

            val hybridMae = sysErrors.average()
            if (
                !hybridMae.isFinite() ||
                !classicalMae.isFinite() ||
                hybridMae > classicalMae * GATE ||
                hybridMae > classicalMae - MIN_GAIN_MMHG
            ) {
                return null
            }
            val diaLoo = diaErrors.average()
            val useDia = diaLoo.isFinite() && diaBaselineMae.isFinite() && diaLoo <= diaBaselineMae

            val (finalCandIdx, finalSysLam) = selectCandidateAndLambda(
                activeCandidates.map { it.second },
                ySys
            ) ?: return null
            val (finalEmbedder, finalXs) = activeCandidates[finalCandIdx]
            val finalDiaLam = selectLambda(finalXs, yDia)
            val sys = ridge(finalXs, ySys, finalSysLam)
            val dia = if (useDia) ridge(finalXs, yDia, finalDiaLam) else null
            return Trained(
                finalEmbedder,
                fs,
                sys.mean,
                sys.weights,
                dia?.weights ?: DoubleArray(sys.weights.size),
                sys.bias,
                dia?.bias ?: 0.0,
                finalSysLam,
                classicalMae,
                hybridMae,
                sysErrors,
                sys.centred,
                sys.kInv,
                sys.scale,
                sys.maxTrainNormSq,
                sys.maxTrainOrth
            )
        }

        private fun selectCandidateAndLambda(candidatesX: List<List<DoubleArray>>, y: List<Double>): Pair<Int, Double>? {
            var best: Triple<Int, Double, Double>? = null
            for ((candIdx, xs) in candidatesX.withIndex()) {
                val (lam, mae) = bestLambda(xs, y)
                if (best == null || mae < best.third) {
                    best = Triple(candIdx, lam, mae)
                }
            }
            return best?.let { it.first to it.second }
        }

        private fun selectLambda(x: List<DoubleArray>, y: List<Double>): Double = bestLambda(x, y).first

        private fun bestLambda(x: List<DoubleArray>, y: List<Double>): Pair<Double, Double> {
            val m = x.size
            val k = minOf(INNER_FOLDS, m)
            var bestLam = LAMBDAS.first()
            var bestMae = Double.POSITIVE_INFINITY
            for (lambda in LAMBDAS) {
                var sumErr = 0.0
                for (fold in 0 until k) {
                    val tr = (0 until m).filter { it % k != fold }
                    val va = (0 until m).filter { it % k == fold }
                    val fitted = ridge(tr.map { x[it] }, tr.map { y[it] }, lambda)
                    for (idx in va) {
                        sumErr += abs(y[idx] - fitted.predict(x[idx]))
                    }
                }
                val mae = sumErr / m
                if (mae.isFinite() && mae < bestMae) {
                    bestMae = mae
                    bestLam = lambda
                }
            }
            return bestLam to bestMae
        }

        /**
         * Ridge in its dual form (n ≪ d): w = Xᵀ(XXᵀ + λ·scale·I)⁻¹(y − b), where `scale` is
         * the mean squared norm of the centred embeddings so λ is comparable across embedders of
         * different sizes. The bias is shrunk towards 0 independently of λ.
         */
        private fun ridge(x: List<DoubleArray>, y: List<Double>, lambda: Double): Ridge {
            val n = x.size
            val d = x.first().size
            val mean = DoubleArray(d) { j -> x.sumOf { it[j] } / n }
            val c = x.map { row -> DoubleArray(d) { row[it] - mean[it] } }
            val scale = (c.sumOf { row -> row.sumOf { it * it } } / n).coerceAtLeast(1e-6)
            val reg = lambda * scale
            val bias = y.sum() / (n + BIAS_LAMBDA)
            val kNoReg = Array(n) { i -> DoubleArray(n) { j -> c[i].indices.sumOf { c[i][it] * c[j][it] } } }
            val k = Array(n) { i -> DoubleArray(n) { j -> kNoReg[i][j] + if (i == j) reg else 0.0 } }
            val alpha = solve(k, DoubleArray(n) { y[it] - bias }) ?: DoubleArray(n)
            val kInv = invert(k)
            val maxNormSq = c.maxOf { row -> row.sumOf { it * it } }
            val maxOrth = if (kInv == null) {
                0.0
            } else {
                (0 until n).maxOf { i ->
                    var quad = 0.0
                    for (j in 0 until n) {
                        var rowDot = 0.0
                        for (m in 0 until n) rowDot += kInv[j][m] * kNoReg[i][m]
                        quad += kNoReg[i][j] * rowDot
                    }
                    maxOf(0.0, (kNoReg[i][i] - quad) / scale)
                }
            }
            val w = DoubleArray(d) { j -> (0 until n).sumOf { alpha[it] * c[it][j] } }
            return Ridge(mean, w, bias, c, kInv, scale, maxNormSq, maxOrth)
        }

        private fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
            val n = a.size
            val m = Array(n) { i ->
                DoubleArray(2 * n).also { row ->
                    a[i].copyInto(row)
                    row[n + i] = 1.0
                }
            }
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(m[it][col]) }
                if (abs(m[pivot][col]) < 1e-12) return null
                val tmp = m[col]
                m[col] = m[pivot]
                m[pivot] = tmp
                val div = m[col][col]
                for (c in col until 2 * n) m[col][c] /= div
                for (r in 0 until n) {
                    if (r == col) continue
                    val factor = m[r][col]
                    for (c in col until 2 * n) m[r][c] -= factor * m[col][c]
                }
            }
            return Array(n) { i -> m[i].copyOfRange(n, 2 * n) }
        }

        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(m[it][col]) }
                if (abs(m[pivot][col]) < 1e-12) return null
                val tmp = m[col]
                m[col] = m[pivot]
                m[pivot] = tmp
                for (r in 0 until n) {
                    if (r == col) continue
                    val factor = m[r][col] / m[col][col]
                    for (cc in col..n) m[r][cc] -= factor * m[col][cc]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}

/**
 * Input for the PaPaGei PPG encoder (Pillai et al., ICLR 2025): upright, band-passed 0.5–12 Hz,
 * 50 ms smoothed, resampled to 125 Hz and cut into z-scored 10 s segments of 1250 samples, as
 * in its reference preprocessing (tools/ml/bp-ml/ppg_prep.py mirrors this).
 */
object PapageiInput {
    const val FS = 125
    const val SEGMENT = FS * 10

    fun segments(raw: FloatArray, fs: Int, polarity: Boolean? = null): List<FloatArray> {
        if (fs <= 0 || raw.size < fs * 10 || raw.size < 2) return emptyList()
        val finite = raw.forwardFillNonFinite()
        val filtered = finite.filtFilt(Biquad.highPass(0.5, fs.toDouble()), Biquad.lowPass(12.0, fs.toDouble()))
        val x = if (PpgFeatures.shouldInvert(finite, filtered, polarity)) FloatArray(filtered.size) { -filtered[it] } else filtered
        val w = maxOf(1, (0.05 * fs).toInt())
        val smooth = FloatArray(x.size) { i ->
            val from = maxOf(0, i - w / 2)
            val to = minOf(x.size - 1, i + (w - 1) / 2)
            var s = 0f
            for (k in from..to) s += x[k]
            s / (to - from + 1)
        }
        val n = (smooth.size.toLong() * FS / fs).toInt()
        if (n < SEGMENT) return emptyList()
        val resampled = FloatArray(n) { k ->
            val pos = k.toDouble() * fs / FS
            val i = pos.toInt().coerceAtMost(smooth.size - 2)
            val f = (pos - i).toFloat()
            smooth[i] * (1 - f) + smooth[i + 1] * f
        }
        return (0..resampled.size - SEGMENT step SEGMENT).mapNotNull { start ->
            val seg = resampled.copyOfRange(start, start + SEGMENT)
            if (seg.any { !it.isFinite() }) return@mapNotNull null
            val mean = seg.average().toFloat()
            val sd = kotlin.math.sqrt(seg.sumOf { ((it - mean) * (it - mean)).toDouble() } / seg.size).toFloat()
            if (!sd.isFinite() || sd <= 1e-6f) return@mapNotNull null
            FloatArray(seg.size) { (seg[it] - mean) / sd }
        }
    }
}
