// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import com.heartline.shared.bp.BpSessionLog
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun syncEnginesRejectPathTraversalIds() = runBlocking {
        assertTrue(Protocol.isSafeId("abc-123_XYZ"))
        assertFalse(Protocol.isSafeId(""))
        assertFalse(Protocol.isSafeId("../escape"))
        assertFalse(Protocol.isSafeId("a/b"))
        assertFalse(Protocol.isSafeId("a\\b"))
        assertFalse(Protocol.isSafeId("x".repeat(129)))

        val (phoneT, watchT) = InMemoryTransport.pair()
        val savedIds = mutableListOf<String>()
        val sessionIds = mutableListOf<String>()
        val deliveredIds = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()
        val sink = object : RecordSink {
            override suspend fun contains(id: String) = false

            override suspend fun save(meta: RecordMeta, wave: FloatArray?) {
                savedIds += meta.id
            }

            override suspend fun delete(id: String) = Unit
        }
        val outbox = object : Outbox {
            override suspend fun pending() = emptyList<OutboxItem>()

            override suspend fun markDelivered(id: String) {
                deliveredIds += id
            }
        }
        val phone = PhoneSyncEngine(phoneT, sink, onSessionLog = { id, _ -> sessionIds += id })
        val watch = WatchSyncEngine(watchT, outbox, onRemoteDelete = { deletedIds += it })

        val badId = "../escape"
        val badMeta = """{"id":"$badId","kind":"ECG","startedAtMs":1,"durationMs":1,"sampleRateHz":500,"sampleCount":0,""" +
            """"summary":{"type":"Ecg","averageBpm":70,"result":"SINUS_RHYTHM","leadOffRatio":0.0}}"""
        phone.handle(Envelope(Protocol.RECORD_META, badMeta.encodeToByteArray()))
        phone.handle(Envelope(Protocol.wavePath(badId), WaveCodec.encode(0, 500, FloatArray(0))))
        phone.handle(Envelope(Protocol.sessionPath(badId), byteArrayOf(1, 2, 3)))
        watch.handle(Envelope(Protocol.RECORD_ACK, """{"id":"$badId","ok":true}""".encodeToByteArray()))
        watch.handle(Envelope(Protocol.DELETE, """{"id":"$badId"}""".encodeToByteArray()))

        assertEquals(emptyList<String>(), savedIds)
        assertEquals(emptyList<String>(), sessionIds)
        assertEquals(emptyList<String>(), deliveredIds)
        assertEquals(emptyList<String>(), deletedIds)
    }

    @Test
    fun binaryDecodersRejectMalformedCountsAndOversizedPayloads() {
        fun gzipBytes(block: DataOutputStream.() -> Unit): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(GZIPOutputStream(out)).use { it.block() }
            return out.toByteArray()
        }

        val badJsonLen = gzipBytes {
            write("HLBP".encodeToByteArray())
            writeInt(BpSessionLog.FORMAT)
            writeInt(-1)
        }
        val badStreamCount = gzipBytes {
            write("HLBP".encodeToByteArray())
            writeInt(BpSessionLog.FORMAT)
            val hdr = """{"id":"s1","startedAtMs":1,"kind":"measure","device":"W","appVersion":"1"}""".encodeToByteArray()
            writeInt(hdr.size)
            write(hdr)
            writeInt(999)
        }
        val overflowFloats = gzipBytes {
            write("HLBP".encodeToByteArray())
            writeInt(BpSessionLog.FORMAT)
            val hdr = """{"id":"s1","startedAtMs":1,"kind":"measure","device":"W","appVersion":"1"}""".encodeToByteArray()
            writeInt(hdr.size)
            write(hdr)
            writeInt(1)
            writeUTF("PPG")
            writeInt(4)
            repeat(4) { writeUTF("c$it") }
            writeInt(600_000_000) // 600M * 4 overflows Int
        }

        for (payload in listOf(badJsonLen, badStreamCount, overflowFloats)) {
            assertTrue(runCatching { BpSessionLog.decode(payload) }.isFailure)
        }

        val badWave = ByteBuffer.allocate(WaveCodec.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x31574C48).putInt(0).putInt(500).putInt(WaveCodec.MAX_SAMPLES + 1).array()
        assertTrue(runCatching { WaveCodec.decode(badWave) }.isFailure)
    }
}
