// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.ecg

import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.model.EcgResult
import com.heartline.shared.sample.SyntheticEcg
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmClassifierTest {
    private val fs = SyntheticEcg.SAMPLE_RATE_HZ

    private fun classify(bpm: Double, irregular: Double = 0.02, noise: Double = 0.015, seed: Int = 1, leadOff: Float = 0f) =
        EcgAnalyzer.analyze(SyntheticEcg.generate(30.0, bpm, irregular, noise, seed), fs, leadOff).result

    @Test
    fun regularNormalRateIsSinus() {
        for (seed in 1..5) assertEquals(EcgResult.SINUS_RHYTHM, classify(72.0, seed = seed))
        assertEquals(EcgResult.SINUS_RHYTHM, classify(58.0))
        assertEquals(EcgResult.SINUS_RHYTHM, classify(95.0))
    }

    @Test
    fun irregularWithoutPWavesIsAfib() {
        for (seed in 1..5) assertEquals("seed $seed", EcgResult.AFIB_SIGNS, classify(90.0, irregular = 0.3, seed = seed))
    }

    @Test
    fun heartRateBounds() {
        assertEquals(EcgResult.HIGH_HEART_RATE, classify(135.0))
        assertEquals(EcgResult.LOW_HEART_RATE, classify(44.0))
        assertEquals(EcgResult.INCONCLUSIVE, classify(110.0))
    }

    @Test
    fun noisyOrDetachedRecordingsArePoor() {
        assertEquals(EcgResult.POOR_RECORDING, classify(72.0, leadOff = 0.6f))
        val random = Random(3)
        val noise = FloatArray(15_000) { (random.nextGaussian() * 0.4).toFloat() }
        assertEquals(EcgResult.POOR_RECORDING, EcgAnalyzer.analyze(noise, fs, 0f).result)
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12)
        val v = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    @Test
    fun unknownClassProbabilityReturnsZeroAndResourceFailuresAreReported() {
        val rhythm = RhythmModel.bundled
        assertNotNull(rhythm)
        assertNotNull(EctopicBeats.bundled)
        assertNotNull(EcgModels.sinusBeneath)
        assertNotNull(EcgModels.longPr)
        assertEquals(0.0, rhythm!!.probability(DoubleArray(RhythmFeatures.NAMES.size), "nonexistent"), 0.0)

        RhythmModel.clearLoadFailures()
        try {
            assertNull(RhythmModel.resource("/ecg/does_not_exist.json", emptyList()))
            assertNull(RhythmModel.resource("/ecg/rhythm_model.json", listOf("wrong")))
            val recorded = RhythmModel.loadFailures()
            assertEquals(2, recorded.size)
            assertTrue(recorded[0].reason.contains("missing"))
            assertTrue(recorded[1].reason.contains("feature mismatch"))
        } finally {
            RhythmModel.clearLoadFailures()
        }
    }

    @Test
    fun featureSubsetBindingAndLogitCalibrationWork() {
        val rawModel = RhythmModel.parse(
            """
            {
              "features": ["f_b", "f_a"],
              "classes": ["normal", "af"],
              "learningRate": 1.0,
              "init": [0.0, 0.0],
              "trees": [[
                {"feature": [0], "threshold": [0.0], "left": [-1], "right": [-1], "value": [0.0]},
                {"feature": [0], "threshold": [5.0], "left": [1, -1, -1], "right": [2, -1, -1], "value": [0.0, -1.0, 1.0]}
              ]],
              "temperature": 2.0,
              "logitBias": [0.0, 1.0]
            }
            """.trimIndent()
        )
        // Reordered against full feature vector ["f_a", "f_unused", "f_b"]: f_b is at index 2.
        assertNull(rawModel.bindFeatures(listOf("f_a", "missing")))
        val bound = rawModel.bindFeatures(listOf("f_a", "f_unused", "f_b"))
        assertNotNull(bound)
        // f_b = 6.0 > 5.0 -> raw logits [0.0, 1.0] -> calibrated [(0+0)/2, (1+1)/2] = [0.0, 1.0].
        val probs = bound!!.predict(doubleArrayOf(1.0, 99.0, 6.0))
        val expectedAf = kotlin.math.exp(1.0) / (1.0 + kotlin.math.exp(1.0))
        assertEquals(expectedAf, probs[1], 1e-9)
        assertEquals(1.0 - expectedAf, probs[0], 1e-9)
    }
}
