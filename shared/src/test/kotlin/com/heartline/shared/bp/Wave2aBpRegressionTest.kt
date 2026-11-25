// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.PpgRepair
import com.heartline.shared.dsp.filtFilt
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sample.SyntheticSession
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave2aBpRegressionTest {
    private val day = BpCalibration.DAY_MS

    private fun features(hr: Double = 68.0, stiffness: Double = 0.5, seed: Int = 1) =
        PpgFeatures.extract(SyntheticPpg.generate(20.0, hr, stiffness, seed = seed), 100)!!

    @Test
    fun biquadFiltFiltEvenReflectionPreservesMidPeakAndHandlesShortInputs() {
        assertTrue(floatArrayOf(42f).filtFilt(Biquad.highPass(0.5, 100.0)).contentEquals(floatArrayOf(42f)))
        val sig = floatArrayOf(1f, 2f, 3f, 4f)
        assertTrue(sig.filtFilt().contentEquals(sig))

        // Pulsatile PPG starting mid-peak: even mirror reflection keeps the first pulse much closer to steady interior pulses.
        val ppg = SyntheticPpg.generate(20.0, 68.0, 0.5, noise = 0.0, seed = 1)
        val midPeak = ppg.copyOfRange(22, ppg.size)
        val filtered = midPeak.filtFilt(Biquad.highPass(0.5, 100.0), Biquad.lowPass(8.0, 100.0))
        val interiorPeak = (300 until 600).maxOf { filtered[it] }
        val firstPeak = (0 until 40).maxOf { abs(filtered[it]) }
        assertTrue("firstPeak=$firstPeak interiorPeak=$interiorPeak", firstPeak <= interiorPeak * 1.35f)
    }

    @Test
    fun ppgFeaturesNormalizeTemplateGuardApgBoundsAndCleanPerfusionIndex() {
        val raw = SyntheticPpg.generate(20.0, 160.0, 0.6, seed = 4)
        val fast = PpgFeatures.extract(raw, 100)!!
        assertTrue(fast.apgBa.isFinite() && fast.apgDa.isFinite())
        // Re-normalized template ensures minimum of shape is 0 and max is near 1.
        assertEquals(0.0f, fast.shape.min(), 1e-4f)
        assertTrue(fast.shape.max() > 0.95f)

        // NaN / PLACEHOLDER samples must not corrupt DC or blow up perfusionIndex; zero DC must return 0.
        val zeroDc = SyntheticPpg.generate(20.0, 68.0, 0.5, seed = 2)
        val zeroPi = PpgFeatures.extract(zeroDc, 100)!!
        assertEquals(0.0, zeroPi.perfusionIndex, 1e-9)

        val withDc = FloatArray(zeroDc.size) { i ->
            if (i % 50 == 17) {
                Float.NaN
            } else if (i % 50 == 18) {
                PpgRepair.PLACEHOLDER
            } else {
                100_000f - 2_000f * zeroDc[i]
            }
        }
        val repairedPi = PpgFeatures.extract(PpgRepair.repair(withDc)!!, 100)!!
        assertTrue("${repairedPi.perfusionIndex}", repairedPi.perfusionIndex.isFinite() && repairedPi.perfusionIndex in 0.5..10.0)
    }

    @Test
    fun fusionDownweightsCorrelatedPpgPairAndExcludesPatWhenEcgPttPresent() {
        val green = ChannelEstimate(BpChannel.PWA_GREEN, 130.0, 82.0, sdSys = 10.0, sdDia = 7.0)
        val ir = ChannelEstimate(BpChannel.PWA_IR, 130.0, 82.0, sdSys = 10.0, sdDia = 7.0)
        val bcg = ChannelEstimate(BpChannel.BCG_PTT, 110.0, 72.0, sdSys = 10.0, sdDia = 7.0)
        val fusedCorrelated = BpFusion.fuse(listOf(green, ir, bcg), HemodynamicState.STEADY)!!
        val wGreen = fusedCorrelated.channels.first { it.channel == BpChannel.PWA_GREEN }.weight
        val wBcg = fusedCorrelated.channels.first { it.channel == BpChannel.BCG_PTT }.weight
        assertTrue(
            "correlated green/ir should each carry lower weight than independent bcg: wGreen=$wGreen wBcg=$wBcg",
            wBcg > wGreen * 1.4
        )

        val pat = ChannelEstimate(BpChannel.PAT, 150.0, 95.0, sdSys = 4.0, sdDia = 3.0)
        val ecgPtt = ChannelEstimate(BpChannel.ECG_PTT, 120.0, 78.0, sdSys = 6.0, sdDia = 4.0)
        val fusedPtt = BpFusion.fuse(listOf(green.copy(systolic = 120.0), pat, ecgPtt), HemodynamicState.STEADY)!!
        assertEquals(0.0, fusedPtt.channels.first { it.channel == BpChannel.PAT }.weight, 1e-9)
        assertTrue("chi2=${fusedPtt.chi2} must exclude PAT outlier", fusedPtt.chi2 < BpFusion.CONFLICT_CHI2)
    }

    @Test
    fun pipelineOmitsEcgPttWhenPepNotMeasuredAndFlagsFusedMoveBeyondCalibration() {
        val quickPts = (1..3).map { s ->
            val ses = SyntheticSession.generate(
                SyntheticSession.Spec(systolic = 130.0 + s, diastolic = 80.0, refSystolic = 130.0, seed = s)
            )
            CalibrationPoint.of(BpPipeline.capture(ses.input)!!, 130 + s, 80, 70)
        }
        val precPts = (1..3).map { s ->
            val ses = SyntheticSession.generate(
                SyntheticSession.Spec(systolic = 130.0 + s, diastolic = 80.0, refSystolic = 130.0, precise = true, seed = 10 + s)
            )
            CalibrationPoint.of(BpPipeline.capture(ses.input)!!, 130 + s, 80, 70)
        }
        val calPrecise = BpCalibration("c-prec", 0L, quickPts, extraPoints = precPts)

        // Without accelerometer (pepMeasured == false), capture() must omit pttMs and run() must omit ECG_PTT.
        val noImuInput = SyntheticSession.generate(
            SyntheticSession.Spec(systolic = 130.0, refSystolic = 130.0, precise = true, withImu = false, seed = 21)
        ).input
        val cap = BpPipeline.capture(noImuInput)!!
        assertNotNull(cap.patMs)
        assertNull(cap.pttMs)
        val precRes = BpPipeline.run(calPrecise, noImuInput, 1_000L)
        val precEst = (precRes.outcome as BpOutcome.Ok).estimate
        assertTrue(precEst.channels.none { it.channel == BpChannel.ECG_PTT })

        // Fused reading pulled > BEYOND_DELTA_SYS away from cal by transit while PWA_GREEN stays near cal must flag beyondCalibration.
        val shifted = SyntheticSession.generate(
            SyntheticSession.Spec(
                systolic = 75.0,
                diastolic = 50.0,
                refSystolic = 130.0,
                heartRateStart = 110.0,
                heartRateEnd = 110.0,
                stiffness = 0.5,
                irStiffness = 0.5,
                perfusionIndex = 0.45,
                irPerfusionIndex = 0.5,
                seed = 25
            )
        ).input
        val shiftedRes = BpPipeline.run(BpCalibration("c-q", 0L, quickPts), shifted, 1_000L)
        val shiftedEst = (shiftedRes.outcome as BpOutcome.Ok).estimate
        assertTrue("expected fused move > BEYOND_DELTA_SYS on $shiftedEst", abs(shiftedEst.deltaSystolic) > BpEstimator.BEYOND_DELTA_SYS)
        assertTrue("expected beyondCalibration=true on $shiftedEst", shiftedEst.beyondCalibration)
    }

    @Test
    fun estimatorCentersSlopesClampsDiaRatioGrowsCovAndTransitClampsAndAnchors() {
        val basePts = listOf(
            CalibrationPoint(features(66.0, 0.45, 1), 118, 82, 66, bcgPttMs = 155.0),
            CalibrationPoint(features(68.0, 0.50, 2), 122, 80, 68, bcgPttMs = 150.0),
            CalibrationPoint(features(70.0, 0.55, 3), 126, 78, 70, bcgPttMs = 145.0)
        )
        // Late cuff check with extreme inverted dia change tests diaRatio floor clamp >= 0.15.
        val lateCheck = CalibrationPoint(features(68.0, 0.50, 4), 160, 50, 68, atMs = 20 * day, bcgPttMs = 120.0)
        val cal = BpCalibration("c-est", 0L, basePts, extraPoints = listOf(lateCheck))
        val model = BpEstimator.fit(cal, nowMs = 20 * day)
        assertTrue("diaRatio=${model.diaRatio} must clamp to 0.15..0.85", model.diaRatio in 0.15..0.85)
        // Sys covariance extrapolation must strictly grow for a feature delta away from ref.
        val near = (BpEstimator.estimate(cal, features(68.0, 0.50, 5), 20 * day) as BpOutcome.Ok).estimate.channels.single().scaleSys
        val far = (BpEstimator.estimate(cal, features(90.0, 0.85, 6), 20 * day) as BpOutcome.Ok).estimate.channels.single().scaleSys
        assertTrue("far scaleSys=$far must exceed near scaleSys=$near", far > near)

        // TransitEstimator clamps noisy positive raw slope to <= -0.1 and anchors toward recent 5-day cuff point.
        val noisyTransitPts = listOf(
            CalibrationPoint(features(66.0, 0.45, 1), 110, 70, 66, bcgPttMs = 130.0),
            CalibrationPoint(features(70.0, 0.55, 2), 140, 90, 70, bcgPttMs = 170.0), // raw dx vs dy positive!
            CalibrationPoint(features(68.0, 0.50, 3), 120, 78, 68, bcgPttMs = 150.0)
        )
        val tCal = BpCalibration("c-tr", 0L, noisyTransitPts, extraPoints = listOf(lateCheck))
        val tEst = TransitEstimator.estimate(tCal, BpChannel.BCG_PTT, 120.0, 20 * day)!!
        assertTrue("slope=${tEst.parts["slope"]}", tEst.parts.getValue("slope") <= -0.1)
        assertTrue("anchor should pull toward 160 at 120 ms: sys=${tEst.systolic}", tEst.systolic > 135.0)
    }

    @Test
    fun raisedWindowKeepsLongestSameSignStretchWithinTimeBounds() {
        val sec = 1_000_000_000L
        val ts = LongArray(90) { it * (sec / 10) } // 10 Hz for 9 s
        val vals = FloatArray(90 * 3)
        for (i in 0 until 90) {
            val gx = when {
                i in 5..30 -> -9.0f // 26 samples arm hang (< -45 deg)
                i in 35..64 -> 9.0f // 30 samples arm raise (> +45 deg)
                else -> 0.0f
            }
            vals[i * 3] = gx
            vals[i * 3 + 2] = 4.0f
        }
        val stream = SensorStream(ImuStreams.ACCEL, listOf("x", "y", "z"), ts, vals)
        val imu = ImuStreams(stream, null, null)
        val w = imu.raisedWindow(fromNs = 3 * sec, toNs = 8 * sec)
        assertNotNull(w)
        assertTrue("must pick +9f stretch (3.5s..6.4s): $w", w!!.first >= 35 * (sec / 10) && w.last <= 64 * (sec / 10))
    }

    @Test
    fun hybridModelIndependentDiaGateAndScaleInvariantOodShrink() {
        val baseFeature = features(68.0, 0.5, 1)
        val samples = (0 until 16).map { i ->
            val v = (i - 7.5) / 8.0
            val f = baseFeature.copy(
                shape = List(PpgFeatureVector.SHAPE_POINTS) { k -> (0.5f + v * (k / 31.0)).toFloat() },
                upstrokeMs = 120.0 + i * 3.0,
                width50Ms = 200.0 - i * 2.0
            )
            // Strong sys linear signal in shape/timing; constant dia error so dia model doesn't beat baseline.
            HybridBpModel.Sample(f, null, 120, 80, (120 + (8.0 * v).toInt()), 80)
        }
        val trained = HybridBpModel.trainBest(listOf(MorphologyEmbedder), samples)
        assertNotNull("Systolic should pass LOO gate", trained)
        // Check OOD shrinkage is scale-invariant: scaling features & train points proportionally keeps same shrink!
        val e1 = doubleArrayOf(1.0, 0.0, 0.0)
        val e2 = doubleArrayOf(2.0, 0.0, 0.0)
        val t1 = HybridBpModel.Trained(
            MorphologyEmbedder,
            100,
            DoubleArray(3),
            doubleArrayOf(1.0, 0.0, 0.0),
            DoubleArray(3),
            0.0,
            0.0,
            1.0,
            5.0,
            2.0,
            listOf(1.0),
            trainCentred = listOf(e1, e2),
            kInv = arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0)),
            scale = 1.0,
            maxTrainNormSq = 4.0,
            maxTrainOrth = 0.0
        )
        val t100 = HybridBpModel.Trained(
            MorphologyEmbedder,
            100,
            DoubleArray(3),
            doubleArrayOf(0.01, 0.0, 0.0),
            DoubleArray(3),
            0.0,
            0.0,
            1.0,
            5.0,
            2.0,
            listOf(1.0),
            trainCentred = listOf(doubleArrayOf(100.0, 0.0, 0.0), doubleArrayOf(200.0, 0.0, 0.0)),
            kInv = arrayOf(doubleArrayOf(1e-4, 0.0), doubleArrayOf(0.0, 1e-4)),
            scale = 10_000.0,
            maxTrainNormSq = 40_000.0,
            maxTrainOrth = 0.0
        )
        val s1 = t1.oodShrink(doubleArrayOf(5.0, 2.0, 0.0))
        val s100 = t100.oodShrink(doubleArrayOf(500.0, 200.0, 0.0))
        assertEquals(s1, s100, 1e-6)
        assertTrue("oodShrink=$s1 should be < 1 for OOD input", s1 < 0.8)
    }

    @Test
    fun conservativePriorPreventsNoisyApgExplosionWhileLearningRange() {
        val base = features(68.0, 0.5, 1)
        val jitterCal = BpCalibration(
            "noisy-apg",
            0,
            listOf(
                CalibrationPoint(base.copy(apgBa = -0.35, areaRatio = 2.0), 120, 78, 68),
                CalibrationPoint(base.copy(apgBa = -0.33, areaRatio = 2.2), 132, 84, 68),
                CalibrationPoint(base.copy(apgBa = -0.31, areaRatio = 2.1), 124, 80, 68)
            )
        )
        val jitterModel = BpEstimator.fit(jitterCal)
        assertTrue("apgBa slope=${jitterModel.sysWeights[4]} should stay damped", abs(jitterModel.sysWeights[4]) <= 15.0)
        assertTrue("areaRatio slope=${jitterModel.sysWeights[3]} should stay damped", abs(jitterModel.sysWeights[3]) <= 8.0)

        val rangeCal = BpCalibration(
            "range",
            0,
            listOf(
                CalibrationPoint(features(66.0, 0.3, 11), 108, 70, 66),
                CalibrationPoint(features(66.0, 0.5, 12), 124, 80, 66),
                CalibrationPoint(features(66.0, 0.7, 13), 140, 90, 66)
            )
        )
        val low = (BpEstimator.estimate(rangeCal, features(66.0, 0.3, 21), 1_000) as BpOutcome.Ok).estimate
        val high = (BpEstimator.estimate(rangeCal, features(66.0, 0.7, 22), 1_000) as BpOutcome.Ok).estimate
        assertTrue("low=$low", low.systolic in 104..118)
        assertTrue("high=$high", high.systolic in 130..148)
    }
}
