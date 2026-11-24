// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.ecg

import com.heartline.shared.model.EcgNote
import com.heartline.shared.model.EcgResult
import com.heartline.shared.sample.SyntheticEcg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave2bEcgRegressionTest {
    private val fs = 500

    @Test
    fun founderWindowsSplitSegmentsInvertAndNotch60HzHum() {
        // Short 4 s noisy first segment (< 10 s), followed by 12 s clean segment with 60 Hz hum, inverted.
        val seg1 = FloatArray(4 * fs) { i -> 2.0f * sin(2 * PI * 1.0 * i / fs).toFloat() }
        val clean12 = SyntheticEcg.generate(12.0, 70.0, seed = 2)
        val seg2 = FloatArray(clean12.size) { i ->
            -(clean12[i] + (0.6 * sin(2 * PI * 60.0 * i / fs)).toFloat())
        }
        val combined = seg1 + seg2
        val windows = EcgFounderInput.windows(combined, fs, max = 3, segmentStarts = listOf(0, seg1.size), inverted = true)
        assertEquals("only the >=10 s segment should yield 1 window", 1, windows.size)

        val refWindows = EcgFounderInput.windows(clean12, fs, max = 3)
        assertEquals(1, refWindows.size)
        // Correlation between notched/inverted seg2 window and clean upright reference should be very high (> 0.9).
        val a = windows[0]
        val b = refWindows[0]
        val corr = a.indices.sumOf { a[it].toDouble() * b[it].toDouble() } / a.size
        assertTrue("corr=$corr", corr > 0.90)
    }

    @Test
    fun sinusLikeRequiresEnoughCleanIntervals() {
        val base = DoubleArray(RhythmFeatures.NAMES.size)
        fun set(name: String, v: Double) {
            base[RhythmFeatures.NAMES.indexOf(name)] = v
        }
        set("p_ratio", 0.12)
        set("clean_nrmssd", 0.03)
        set("clean_cov", 0.04)
        set("intervals", 20.0)
        set("clean_share", 0.8)
        assertTrue(RhythmFeatures.sinusLike(base))

        // Clean share below 0.5 -> must not be called sinusLike.
        set("clean_share", 0.4)
        assertFalse(RhythmFeatures.sinusLike(base))

        // Bradycardia with 6 clean intervals still passes.
        set("clean_share", 1.0)
        set("intervals", 6.0)
        assertTrue(RhythmFeatures.sinusLike(base))

        // Fewer than 3 clean intervals (< RrFeatures.of minimum) -> sentinel values must not pass.
        set("clean_share", 1.0)
        set("intervals", 2.0)
        assertFalse(RhythmFeatures.sinusLike(base))

        set("clean_share", 0.5)
        set("intervals", 4.0)
        assertFalse(RhythmFeatures.sinusLike(base))
    }

    @Test
    fun morphologyTimingCapsLookbackInTachycardiaAndAnalyzerOrientsTemplateUpright() {
        // Construct a beat with a false bump 280 ms before R (previous beat's T wave in fast tachycardia)
        // and a true P wave 120 ms before R; with medianRrS = 0.36 (~167 bpm), 0.45 * 0.36 = 162 ms
        // prevents reaching back 280 ms to pick the larger bump.
        val n = ((EcgMorphology.PRE_S + EcgMorphology.POST_S) * fs).toInt()
        val r = (EcgMorphology.PRE_S * fs).toInt()
        val t = DoubleArray(n)
        t[r] = 1.0
        t[r - 1] = 0.5
        t[r + 1] = 0.5
        // False bump at -280 ms (larger than true P)
        val falseIdx = r - (0.28 * fs).toInt()
        for (k in -8..8) t[falseIdx + k] = 0.40
        // True P wave at -120 ms
        val pIdx = r - (0.12 * fs).toInt()
        for (k in -8..8) t[pIdx + k] = 0.15

        val uncapped = EcgMorphology.timing(t, fs, medianRrS = null)
        val capped = EcgMorphology.timing(t, fs, medianRrS = 0.36)
        assertTrue("capped pr=${capped.prMs} should stay < 200 ms vs uncapped=${uncapped.prMs}", (capped.prMs ?: 999.0) < 170.0)
        assertTrue("uncapped pr=${uncapped.prMs} picked far bump", (uncapped.prMs ?: 0.0) > 220.0)

        // Inverted ECG must produce the same signed EcgMorphology features as upright ECG via EcgAnalyzer.
        val upright = SyntheticEcg.generate(30.0, 72.0, seed = 8)
        val inverted = FloatArray(upright.size) { -upright[it] }
        val aUp = EcgAnalyzer.analyze(upright, fs, EcgSession())
        val aInv = EcgAnalyzer.analyze(inverted, fs, EcgSession())
        assertTrue(aInv.metrics.inverted && !aUp.metrics.inverted)
        val fUp = EcgMorphology.features(aUp.template!!, fs)
        val fInv = EcgMorphology.features(aInv.template!!, fs)
        val diff = fUp.indices.maxOf { abs(fUp[it] - fInv[it]) }
        assertTrue("morphology diff=$diff", diff < 0.05)
    }

    @Test
    fun decidePreservesPausesAndSurfacesBeatModelNotes() {
        val model = RhythmModel.bundled!!
        val normalP = DoubleArray(model.classes.size).also { p ->
            p[model.classes.indexOf("normal")] = 0.95
        }
        val otherP = DoubleArray(model.classes.size).also { p ->
            p[model.classes.indexOf("other")] = 0.95
        }
        val countsS = EctopicBeats.Counts(supraventricular = 2, ventricular = 0, classified = 30)
        val countsFreqV = EctopicBeats.Counts(supraventricular = 0, ventricular = 5, classified = 30)
        val pauseRules = RhythmClassifier.Decision(EcgResult.SINUS_RHYTHM, EcgNote.PAUSES)

        // Pauses must not be masked by SINUS_SLOW, SINUS_FAST, or EXTRA_BEATS.
        assertEquals(EcgNote.PAUSES, EcgAnalyzer.decide(model, normalP, 44, pauseRules, countsS, sinusLike = true).note)
        assertEquals(EcgNote.PAUSES, EcgAnalyzer.decide(model, normalP, 110, pauseRules, countsS, sinusLike = true).note)
        assertEquals(EcgNote.PAUSES, EcgAnalyzer.decide(model, normalP, 72, pauseRules, countsFreqV, sinusLike = true).note)
        assertEquals(EcgNote.PAUSES, EcgAnalyzer.decide(model, normalP, 130, pauseRules, countsS, sinusLike = false).note)

        // Without pauses, Alg-4 beat counts surface on high, low (non-sinus), and inconclusive frequent extra beats.
        val noPauseRules = RhythmClassifier.Decision(EcgResult.INCONCLUSIVE, EcgNote.EXTRA_BEATS)
        assertEquals(EcgNote.EXTRA_BEATS_UPPER, EcgAnalyzer.decide(model, otherP, 130, noPauseRules, countsS, sinusLike = false).note)
        assertEquals(EcgNote.EXTRA_BEATS_LOWER, EcgAnalyzer.decide(model, otherP, 44, noPauseRules, countsFreqV, sinusLike = false).note)
        assertEquals(EcgNote.EXTRA_BEATS_LOWER, EcgAnalyzer.decide(model, otherP, 75, noPauseRules, countsFreqV, sinusLike = false).note)
    }

    @Test
    fun recorderThrottlesArmVerifyToAbout4Hz() {
        var checks = 0
        val recorder = EcgRecorder(fs, verify = { _, _ ->
            checks++
            false
        })
        val signal = SyntheticEcg.generate(6.0, 70.0, seed = 1)
        val step = 10 // 50 Hz chunk arrival
        var i = 0
        while (i + step <= signal.size) {
            recorder.accept(signal.copyOfRange(i, i + step), leadOff = false)
            i += step
        }
        // 0.5 s debounce + 1.0 s settle + 3.0 s verifyWindow = 4.5 s to first check, leaving ~1.5 s -> ~7 checks at 4 Hz (<< 75 unthrottled).
        assertTrue("verify checks=$checks should be throttled (~4 Hz), expected 4..10", checks in 3..10)
    }
}
