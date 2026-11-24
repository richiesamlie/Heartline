// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.ecg

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import com.heartline.shared.ecg.EcgFilter
import com.heartline.shared.ecg.RPeakDetector
import com.heartline.shared.sample.SyntheticEcg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgProcessingTest {
    private val fs = SyntheticEcg.SAMPLE_RATE_HZ

    private fun rms(x: FloatArray) = sqrt(x.map { it.toDouble() * it }.average())

    private fun sine(hz: Double, seconds: Double = 4.0) = FloatArray((seconds * fs).toInt()) { sin(2 * PI * hz * it / fs).toFloat() }

    @Test
    fun notchRemovesMainsButKeepsSignalBand() {
        val notched = sine(50.0).filtFilt(Biquad.notch(50.0, fs.toDouble()))
        assertTrue(rms(notched.copyOfRange(fs, 3 * fs)) < 0.05)
        val kept = sine(10.0).filtFilt(Biquad.notch(50.0, fs.toDouble()))
        assertTrue(rms(kept.copyOfRange(fs, 3 * fs)) > 0.65)
    }

    @Test
    fun cleanRemovesBaselineWander() {
        val drift = FloatArray(10 * fs) { (0.8 * sin(2 * PI * 0.1 * it / fs)).toFloat() }
        val cleaned = EcgFilter.clean(drift, fs)
        assertTrue(rms(cleaned.copyOfRange(2 * fs, 8 * fs)) < 0.1)
    }

    @Test
    fun detectsEveryBeatAcrossHeartRates() {
        for (bpm in listOf(45.0, 72.0, 110.0, 150.0)) {
            val signal = EcgFilter.clean(SyntheticEcg.generate(30.0, bpm, irregularity = 0.0, noiseMv = 0.03, seed = bpm.toInt()), fs)
            val peaks = RPeakDetector.detect(signal, fs)
            val expected = 30.0 * bpm / 60.0
            assertTrue("bpm=$bpm peaks=${peaks.size} expected≈$expected", abs(peaks.size - expected) <= 2)
            val hr = RPeakDetector.heartRateBpm(peaks, fs)
            assertNotNull(hr)
            assertTrue("bpm=$bpm hr=$hr", abs(hr!! - bpm) <= 3)
            assertTrue("peaks must stay sorted & separated", peaks.toList().zipWithNext().all { (a, b) -> b - a >= (0.2 * fs).toInt() })
        }
    }

    @Test
    fun detectsSvtAt175BpmWithoutDroppingEveryOtherBeat() {
        val bpm = 175.0
        val signal = EcgFilter.clean(SyntheticEcg.generate(30.0, bpm, irregularity = 0.0, noiseMv = 0.02, seed = 175), fs)
        val expected = 30.0 * bpm / 60.0
        val ptPeaks = RPeakDetector.detect(signal, fs)
        val gradPeaks = GradientQrsDetector.detect(signal, fs)
        assertTrue("RPeakDetector peaks=${ptPeaks.size} expected≈$expected", abs(ptPeaks.size - expected) <= 5)
        assertTrue("GradientQrsDetector peaks=${gradPeaks.size} expected≈$expected", abs(gradPeaks.size - expected) <= 5)
    }

    @Test
    fun pauseOverThreeSecondsRecoversSubsequentBeatsWithoutFabricatingNoiseBeats() {
        val r = kotlin.random.Random(11)
        val total = 20
        val signal = FloatArray(total * fs) { (0.015 * (r.nextDouble() * 2 - 1)).toFloat() }
        fun g(t: Double, c: Double, w: Double, a: Double) = a * kotlin.math.exp(-0.5 * ((t - c) / w).let { it * it })
        val beatTimes = (1..7).map { it * 0.85 } + (0..7).map { 10.5 + it * 0.85 }
        for (t0 in beatTimes) {
            val from = ((t0 - 0.1) * fs).toInt().coerceAtLeast(0)
            val to = ((t0 + 0.4) * fs).toInt().coerceAtMost(signal.size - 1)
            for (i in from..to) {
                val t = i.toDouble() / fs
                signal[i] += (g(t, t0, 0.012, 0.6) + g(t, t0 + 0.025, 0.01, -0.15) + g(t, t0 + 0.25, 0.04, 0.15)).toFloat()
            }
        }
        val clean = EcgFilter.clean(signal, fs)
        val peaks = RPeakDetector.detect(clean, fs)
        val inPause = peaks.filter { it in (6.5 * fs).toInt()..(10.1 * fs).toInt() }
        assertTrue("unexpected beats fabricated inside 4.5s pause: $inPause", inPause.isEmpty())
        val afterPause = peaks.filter { it >= (10.3 * fs).toInt() }
        assertTrue("expected post-pause beats to be recovered, got ${afterPause.size}", afterPause.size >= 6)
    }

    @Test
    fun tallTWaveDuringSearchBackPauseIsNotDoubleCounted() {
        val total = 16
        val signal = FloatArray(total * fs)
        fun g(t: Double, c: Double, w: Double, a: Double) = a * kotlin.math.exp(-0.5 * ((t - c) / w).let { it * it })
        val rTimes = mutableListOf(0.85)
        repeat(5) { rTimes += rTimes.last() + 0.85 }
        rTimes += rTimes.last() + 1.45 // > 1.66 * 0.85 s triggers RPeakDetector search-back
        repeat(6) { rTimes += rTimes.last() + 0.85 }
        for (t0 in rTimes) {
            val from = ((t0 - 0.1) * fs).toInt().coerceAtLeast(0)
            val to = ((t0 + 0.45) * fs).toInt().coerceAtMost(signal.size - 1)
            for (i in from..to) {
                val t = i.toDouble() / fs
                signal[i] += (g(t, t0, 0.010, 0.55) + g(t, t0 + 0.02, 0.008, -0.12) + g(t, t0 + 0.26, 0.055, 0.35)).toFloat()
            }
        }
        val clean = EcgFilter.clean(signal, fs)
        val ptPeaks = RPeakDetector.detect(clean, fs)
        val gradPeaks = GradientQrsDetector.detect(clean, fs)
        assertTrue("RPeakDetector double-counted T waves: ${ptPeaks.size} vs ${rTimes.size}", ptPeaks.size <= rTimes.size + 1)
        assertTrue("GradientQrsDetector double-counted T waves: ${gradPeaks.size} vs ${rTimes.size}", gradPeaks.size <= rTimes.size + 1)
    }
}
