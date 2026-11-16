// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.stress.StressBaseline
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.WatchSyncEngine
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garbage from the other device must never crash a listener service. */
class RobustnessTest {
    private val paths = listOf(
        Protocol.RECORD_META,
        Protocol.wavePath("x"),
        Protocol.RECORD_ACK,
        Protocol.HR_BATCH,
        Protocol.ALERT,
        Protocol.SETTINGS,
        Protocol.PROFILE,
        Protocol.BP_CALIBRATION,
        Protocol.BP_CALIBRATION_CAPTURE,
        Protocol.DELETE,
        Protocol.OPEN,
        Protocol.MEASURE_STATE,
        Protocol.MEASURE_RESULT,
        Protocol.MEASURE_CANCEL,
        "/hl/v1/unknown"
    )

    @Test
    fun enginesIgnoreMalformedPayloads() = runBlocking {
        val (a, b) = InMemoryTransport.pair()
        val saved = mutableListOf<String>()
        val sink = object : RecordSink {
            override suspend fun contains(id: String) = false

            override suspend fun save(meta: RecordMeta, wave: FloatArray?) {
                saved += meta.id
            }

            override suspend fun delete(id: String) = Unit
        }
        val outbox = object : Outbox {
            override suspend fun pending() = emptyList<OutboxItem>()

            override suspend fun markDelivered(id: String) = Unit
        }
        val phone = PhoneSyncEngine(a, sink)
        val watch = WatchSyncEngine(b, outbox)
        val random = Random(42)
        repeat(500) {
            val path = paths[random.nextInt(paths.size)]
            val payload = when (random.nextInt(4)) {
                0 -> ByteArray(random.nextInt(0, 64)).also(random::nextBytes)
                1 -> "{}".encodeToByteArray()
                2 -> """{"id":1}""".encodeToByteArray()
                else -> ByteArray(0)
            }
            phone.handle(Envelope(path, payload))
            watch.handle(Envelope(path, payload))
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `signal and stress helpers guard non finite and invalid inputs`() {
        org.junit.Assert.assertEquals(0, StressBaseline.score(Double.NaN, 70, null))
        org.junit.Assert.assertEquals(0, StressBaseline.score(-5.0, 70, null))
        org.junit.Assert.assertNull(PpgFeatures.rhythm(floatArrayOf(0f, 0f, 0f), fs = 0))
    }

    @Test
    fun `phase 1 and 2 zero-fabrication and crisis thresholds hold`() {
        val marked = com.heartline.shared.ecg.EcgQuality.markPauses(
            kinds = listOf(com.heartline.shared.ecg.SecondKind.MOTION, com.heartline.shared.ecg.SecondKind.GOOD),
            peaks = intArrayOf(600),
            valid = booleanArrayOf(true),
            segments = listOf(0..999),
            fs = 500
        )
        org.junit.Assert.assertEquals(com.heartline.shared.ecg.SecondKind.MOTION, marked[0])
        org.junit.Assert.assertTrue(com.heartline.shared.ecg.EcgFounderInput.windows(FloatArray(5000), 500).isEmpty())
        org.junit.Assert.assertTrue(com.heartline.shared.bp.PapageiInput.segments(FloatArray(3000), 100).isEmpty())

        val poor = com.heartline.shared.ecg.EcgAnalyzer.analyze(FloatArray(2000), 500, 0f)
        org.junit.Assert.assertEquals(com.heartline.shared.model.EcgResult.POOR_RECORDING, poor.result)
        org.junit.Assert.assertNull(poor.averageBpm)
        org.junit.Assert.assertNull(poor.metrics.minBpm)
        org.junit.Assert.assertNull(poor.metrics.maxBpm)
        org.junit.Assert.assertEquals(0, poor.metrics.beats)
        org.junit.Assert.assertEquals(0, poor.metrics.ectopicBeats)
        org.junit.Assert.assertEquals(0, poor.metrics.pauses)
        org.junit.Assert.assertNull(poor.metrics.sdnnMs)
        org.junit.Assert.assertNull(poor.metrics.rmssdMs)
        org.junit.Assert.assertNull(poor.metrics.qrsMs)
        org.junit.Assert.assertNull(poor.metrics.prMs)
        org.junit.Assert.assertFalse(poor.metrics.longPr)

        org.junit.Assert.assertEquals(com.heartline.shared.bp.BpCategory.CRISIS, com.heartline.shared.bp.BpCategory.of(180, 110))
        org.junit.Assert.assertEquals(com.heartline.shared.bp.BpCategory.CRISIS, com.heartline.shared.bp.BpCategory.of(130, 120))
        org.junit.Assert.assertEquals(com.heartline.shared.bp.BpCategory.HIGH_STAGE_2, com.heartline.shared.bp.BpCategory.of(179, 119))
        org.junit.Assert.assertNull(
            com.heartline.shared.bp.BpPipeline.capture(
                com.heartline.shared.bp.BpSessionInput(green = FloatArray(500), fs = 25)
            )
        )
    }
}
