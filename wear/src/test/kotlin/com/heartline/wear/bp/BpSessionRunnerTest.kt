// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.bp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.bp.BpSessionReplay
import com.heartline.shared.bp.BpSessionStreams
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sample.SyntheticSession
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.EcgChunk
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.FakePpgSource
import com.heartline.wear.sensor.ImuRecorder
import com.heartline.wear.sensor.PpgChunk
import com.heartline.wear.sensor.PpgSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BpSessionRunnerTest {
    private lateinit var db: WatchDatabase
    private lateinit var records: WatchRecordStore
    private lateinit var bp: WatchBpStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("bp", 0).edit().clear().commit()
        db = WatchDatabase.inMemory(context)
        records = WatchRecordStore(db.records(), File(context.cacheDir, "bp-runner-test").apply { deleteRecursively() }, db.messages())
        bp = WatchBpStore(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun uncalibratedPrepareReturnsNullAndCalibratedRunPersistsWithoutViewModel() = runBlocking {
        var scheduled = 0
        val runner = BpSessionRunner(FakePpgSource(chunkDelayMs = 0), bp, records, { scheduled++ }, uiIntervalMs = 0)
        assertNull(runner.prepare())

        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5), 100)!!
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), List(3) { CalibrationPoint(features, 122, 80, 68) }))
        val session = runner.prepare()!!
        val states = mutableListOf<BpRunState>()
        runner.run(session) { states += it }

        assertTrue(states.last() is BpRunState.Done)
        assertEquals(1, records.pending().size)
        assertTrue(scheduled >= 1)
    }

    @Test
    fun quickModeTimesOutToPoorSignalWhenNoContact() = runBlocking {
        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5), 100)!!
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), List(3) { CalibrationPoint(features, 122, 80, 68) }))

        var fakeNow = 1_000L
        val offWrist = object : PpgSource {
            override fun stream(): Flow<PpgChunk> = flow {
                while (true) {
                    fakeNow += 1_000L
                    emit(PpgChunk(FloatArray(5), contact = false))
                }
            }
        }
        val runner = BpSessionRunner(
            offWrist,
            bp,
            records,
            {},
            now = { fakeNow },
            uiIntervalMs = 0,
            noContactTimeoutMs = 15_000L,
        )
        val states = mutableListOf<BpRunState>()
        runner.run(runner.prepare()!!) { states += it }
        assertEquals(BpRunState.PoorSignal, states.last())
        assertTrue(records.pending().isEmpty())
        val log = BpSessionLog.decode(records.pendingSessions().single().second)
        assertEquals("no contact", log.header.notes["result"])
        assertTrue(log.header.events.any { it.type == "timeout" })

        val silent = object : PpgSource {
            override fun stream(): Flow<PpgChunk> = flow { awaitCancellation() }
        }
        val silentRunner = BpSessionRunner(silent, bp, records, {}, uiIntervalMs = 0, noContactTimeoutMs = 50L)
        val silentStates = mutableListOf<BpRunState>()
        silentRunner.run(silentRunner.prepare()!!) { silentStates += it }
        assertEquals(BpRunState.PoorSignal, silentStates.last())
        assertTrue(records.pending().isEmpty())
        val silentLog = BpSessionLog.decode(records.pendingSessions().single { it.first != log.header.id }.second)
        assertEquals("no contact", silentLog.header.notes["result"])
    }

    @Test
    fun preciseModeSurvivesBriefLeadOffAndResetsOnLongLiftAndAbandonsAfter10s() = runBlocking {
        val spec = SyntheticSession.Spec(
            systolic = 104.0,
            diastolic = 70.0,
            refSystolic = 104.0,
            stiffness = 0.3,
            precise = true,
            seconds = 36.0,
        )
        val points = listOf(false, true).flatMap { precise ->
            (1..3).map {
                val s = SyntheticSession.generate(spec.copy(heartRateStart = 68.0 + it, seed = it, precise = precise))
                CalibrationPoint.of(BpPipeline.capture(s.input)!!, 103 + it, 70, 70)
            }
        }
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), points))
        val session = SyntheticSession.generate(spec.copy(seed = 9))
        val precise = session.input.precise!!
        val imu = object : ImuRecorder {
            override fun start() = Unit

            override fun stop() = 0.05

            override fun streams() = session.input.imu
        }

        // Wait 20 s before first touch without timing out, then tolerate 100 ms leadOff + 100 ms saturated blips.
        val baseMs = precise.startNs / 1_000_000L
        var fakeNow = baseMs - 20_100L
        val preTouchAndBlips = object : EcgSource {
            override fun stream(): Flow<EcgChunk> = flow {
                val n = 50
                for (i in 0 until 200) {
                    fakeNow += 100L
                    emit(EcgChunk(FloatArray(n), leadOff = true, timestampMs = fakeNow))
                }
                for (offset in 0 until precise.ecg.size - n step n) {
                    val lastMs = (precise.startNs + ((offset + n - 1) * 1e9 / precise.fs).toLong()) / 1_000_000L
                    fakeNow = lastMs
                    val isLeadOffBlip = offset == 5_000
                    val isSatBlip = offset == 10_000
                    emit(
                        EcgChunk(
                            precise.ecg.copyOfRange(offset, offset + n),
                            leadOff = isLeadOffBlip,
                            timestampMs = lastMs,
                            ppg = if (isLeadOffBlip || isSatBlip) null else precise.ppg.copyOfRange(offset, offset + n),
                            saturated = isSatBlip,
                        ),
                    )
                }
            }
        }
        val resilientRunner = BpSessionRunner(
            FakePpgSource(chunkDelayMs = 0),
            bp,
            records,
            {},
            now = { fakeNow },
            uiIntervalMs = 0,
            imu = imu,
            ecg = preTouchAndBlips,
        )
        val doneStates = mutableListOf<BpRunState>()
        resilientRunner.run(resilientRunner.prepare(BpRunMode.PRECISE)!!) { doneStates += it }
        val done = doneStates.last() as BpRunState.Done
        assertTrue("${done.channels}", BpChannel.ECG_PTT in done.channels)
        val okLog = BpSessionLog.decode(records.pendingSessions().single().second)
        assertTrue(okLog.header.events.none { it.type == "contact.lost" || it.type == "contact.abandoned" })
        val startNote = okLog.header.notes[BpSessionStreams.NOTE_PRECISE_START_NS]?.toLongOrNull()
        assertNotNull(startNote)
        val replayed = BpSessionReplay.input(okLog).precise!!
        assertEquals(precise.startNs, startNote)
        assertEquals(34 * 500, replayed.ecg.size)

        // After 5 s of recording, 500 ms lift resets progress and 10 s of lead-off abandons to PoorSignal.
        val longLift = object : EcgSource {
            override fun stream(): Flow<EcgChunk> = flow {
                val n = 50
                for (offset in 0 until 2_500 step n) {
                    val lastMs = (precise.startNs + ((offset + n - 1) * 1e9 / precise.fs).toLong()) / 1_000_000L
                    emit(EcgChunk(precise.ecg.copyOfRange(offset, offset + n), leadOff = false, timestampMs = lastMs, ppg = precise.ppg.copyOfRange(offset, offset + n)))
                }
                for (k in 0 until 110) {
                    val lastMs = (precise.startNs + ((2_500 + (k + 1) * n - 1) * 1e9 / precise.fs).toLong()) / 1_000_000L
                    emit(EcgChunk(FloatArray(n), leadOff = true, timestampMs = lastMs))
                }
            }
        }
        val abandonRunner = BpSessionRunner(
            FakePpgSource(chunkDelayMs = 0),
            bp,
            records,
            {},
            uiIntervalMs = 0,
            imu = imu,
            ecg = longLift,
        )
        val abandonStates = mutableListOf<BpRunState>()
        abandonRunner.run(abandonRunner.prepare(BpRunMode.PRECISE)!!) { abandonStates += it }
        assertEquals(BpRunState.PoorSignal, abandonStates.last())
        val badLog = BpSessionLog.decode(records.pendingSessions().first { it.first != okLog.header.id }.second)
        assertTrue(badLog.header.events.any { it.type == "contact.lost" })
        assertTrue(badLog.header.events.any { it.type == "contact.abandoned" })
        assertEquals("precise: abandoned after lead-off", badLog.header.notes["result"])
    }

    @Test
    fun preciseModeAbandonsToPoorSignalWhenPreTouchTimeoutExpires() = runBlocking {
        val spec = SyntheticSession.Spec(precise = true, seconds = 36.0)
        val points = listOf(false, true).flatMap { precise ->
            (1..3).map {
                val s = SyntheticSession.generate(spec.copy(heartRateStart = 68.0 + it, seed = it, precise = precise))
                CalibrationPoint.of(BpPipeline.capture(s.input)!!, 118, 76, 68)
            }
        }
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), points))
        val silent = object : EcgSource {
            override fun stream(): Flow<EcgChunk> = flow { awaitCancellation() }
        }
        val runner = BpSessionRunner(
            FakePpgSource(chunkDelayMs = 0),
            bp,
            records,
            {},
            uiIntervalMs = 0,
            ecg = silent,
            preTouchTimeoutMs = 50L,
        )
        val states = mutableListOf<BpRunState>()
        runner.run(runner.prepare(BpRunMode.PRECISE)!!) { states += it }
        assertEquals(BpRunState.PoorSignal, states.last())
        val log = BpSessionLog.decode(records.pendingSessions().single().second)
        assertEquals("precise: no touch", log.header.notes["result"])
        assertTrue(log.header.events.any { it.type == "timeout" })
    }

    @Test
    fun preparedSessionsKeepIndependentRawIds() {
        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5), 100)!!
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), List(3) { CalibrationPoint(features, 122, 80, 68) }))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        com.heartline.wear.diag.RawCapture.init(context, "test") {}
        com.heartline.wear.diag.RawCapture.setEnabled(true)
        try {
            val runner = BpSessionRunner(FakePpgSource(chunkDelayMs = 0), bp, records, {}, uiIntervalMs = 0)
            val first = runner.prepare()!!
            val second = runner.prepare()!!
            assertTrue(first.rawId.isNotEmpty() && second.rawId.isNotEmpty())
            assertTrue(first.rawId != second.rawId)
            com.heartline.wear.diag.RawCapture.discard(first.rawId)
            com.heartline.wear.diag.RawCapture.discard(second.rawId)
        } finally {
            com.heartline.wear.diag.RawCapture.setEnabled(false)
        }
    }
}
