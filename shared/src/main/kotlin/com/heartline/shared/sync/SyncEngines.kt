// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.profile.UserProfile
import java.io.InputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/** A finished measurement waiting on the watch until the phone acknowledges it. */
data class OutboxItem(val meta: RecordMeta, val wave: FloatArray?) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxItem) return false
        val waveMatches = wave === other.wave || (wave != null && other.wave != null && wave.contentEquals(other.wave))
        return meta == other.meta && waveMatches
    }

    override fun hashCode(): Int = 31 * meta.hashCode() + (wave?.contentHashCode() ?: 0)
}

/** Lazy handle for a pending measurement record so flushing streams waveforms one at a time. */
class PendingRecord(val meta: RecordMeta, val openWave: (suspend () -> InputStream?)? = null)

/** Lazy handle for a pending raw BP session log so flushing streams it from disk. */
class PendingSession(val id: String, val openStream: suspend () -> InputStream?)

/** A small JSON message (HR batch, alert) that must reach the phone; acked by [id]. */
data class PendingMessage(val id: String, val path: String, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PendingMessage && id == other.id && path == other.path && payload.contentEquals(other.payload))

    override fun hashCode(): Int = (31 * id.hashCode() + path.hashCode()) * 31 + payload.contentHashCode()
}

/** Watch-side persistence of unsent records (Room in the app, in-memory in tests). */
interface Outbox {
    suspend fun pending(): List<OutboxItem>

    suspend fun pendingMessages(): List<PendingMessage> = emptyList()

    /** Raw BP session logs (id, encoded bytes) waiting for the phone. */
    suspend fun pendingSessions(): List<Pair<String, ByteArray>> = emptyList()

    suspend fun hasPending(): Boolean = pending().isNotEmpty() || pendingMessages().isNotEmpty() || pendingSessions().isNotEmpty()

    suspend fun pendingRecords(): List<PendingRecord> = pending().map { item ->
        val wave = item.wave
        PendingRecord(
            item.meta,
            wave?.let { { WaveCodec.encode(item.meta.kind.ordinal, item.meta.sampleRateHz, it).inputStream() } }
        )
    }

    suspend fun pendingSessionStreams(): List<PendingSession> = pendingSessions().map { (id, bytes) ->
        PendingSession(id) { bytes.inputStream() }
    }

    /** Called for acked records and messages alike. */
    suspend fun markDelivered(id: String)
}

/** Phone-side sink for heart-rate trends and alerts. */
interface HeartDataSink {
    suspend fun saveBatch(batch: HrBatch)

    suspend fun saveAlert(alert: HealthAlert)
}

/** Phone-side persistence of received records. */
interface RecordSink {
    suspend fun contains(id: String): Boolean

    suspend fun save(meta: RecordMeta, wave: FloatArray?)

    suspend fun delete(id: String)
}

/**
 * Watch side of the protocol: pushes every pending record (meta, then waveform) and removes it
 * from the outbox when the phone acks. Sending is idempotent, so a retry after a lost ack is safe.
 */
class WatchSyncEngine(
    private val transport: SyncTransport,
    private val outbox: Outbox,
    private val onRemoteDelete: suspend (String) -> Unit = {},
    private val onSettings: suspend (MonitorSettings) -> Unit = {},
    private val onCalibration: suspend (BpCalibration?) -> Unit = {},
    private val onCaptureRequest: suspend (CaptureRequest) -> Unit = {},
    private val onProfile: suspend (UserProfile) -> Unit = {},
    private val onOpen: suspend (String) -> Unit = {},
    private val onStatus: suspend (PhoneStatus) -> Unit = {},
    private val onLogRequest: suspend (LogRequest) -> Unit = {},
    /** The phone stored a log file this watch sent to it ([sendArchive]). */
    private val onArchiveAck: suspend (ArchiveAck) -> Unit = {},
    /** The phone stopped a measurement it started. */
    private val onMeasureCancel: suspend (MeasureCancel) -> Unit = {},
    /** A message that could not be handled (it stays unacknowledged, so the phone sends it again). */
    private val onError: (path: String, error: Throwable) -> Unit = { _, _ -> }
) {
    /** Watch → phone: the diagnostic log (or its manifest) the phone asked for with [LogRequest]. */
    suspend fun sendLogs(requestId: String, text: ByteArray): Boolean = transport.sendLarge(Protocol.logsPath(requestId), text)

    /** Watch → phone: a finished log segment or raw session, streamed from its file into the phone's archive. */
    suspend fun sendArchive(type: String, name: String, input: InputStream): Boolean =
        transport.sendStream(Protocol.logArchivePath(type, name), input)

    /** Watch → phone: one log segment, streamed from its file. */
    suspend fun sendLogSegment(requestId: String, input: InputStream): Boolean =
        transport.sendStream(Protocol.logSegmentPath(requestId), input)

    /** Watch → phone: settings changed on the watch (the phone keeps the newer copy). */
    suspend fun sendSettings(settings: MonitorSettings): Boolean =
        transport.send(Protocol.SETTINGS, Protocol.json.encodeToString(settings).encodeToByteArray())

    /** Watch → phone: progress of a measurement the phone started (not retried; the next one supersedes it). */
    suspend fun sendMeasureState(state: MeasureState): Boolean =
        transport.sendImmediate(Protocol.MEASURE_STATE, Protocol.json.encodeToString(state).encodeToByteArray())

    /** Watch → phone: outcome of a measurement the phone started (also kept in the outbox until acked). */
    suspend fun sendMeasureResult(result: MeasureResult): Boolean =
        transport.sendImmediate(Protocol.MEASURE_RESULT, Protocol.json.encodeToString(result).encodeToByteArray())

    /** @return number of records handed to the transport. */
    suspend fun flush(): Int {
        var sent = 0
        for (item in outbox.pendingRecords()) {
            val metaSent = transport.send(Protocol.RECORD_META, Protocol.json.encodeToString(item.meta).encodeToByteArray())
            if (!metaSent) break
            val openWave = item.openWave
            if (openWave != null) {
                val stream = openWave()
                if (stream != null && !transport.sendStream(Protocol.wavePath(item.meta.id), stream)) break
            }
            sent++
        }
        for (message in outbox.pendingMessages()) {
            if (!transport.send(message.path, message.payload)) break
            sent++
        }
        for (session in outbox.pendingSessionStreams()) {
            val stream = session.openStream() ?: continue
            if (!transport.sendStream(Protocol.sessionPath(session.id), stream)) break
            sent++
        }
        return sent
    }

    /** Malformed or unknown messages are dropped rather than crashing the listener service. */
    suspend fun handle(envelope: Envelope) {
        runCatching { handleOrThrow(envelope) }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            onError(envelope.path, it)
        }
    }

    private suspend fun handleOrThrow(envelope: Envelope) {
        when (envelope.path) {
            Protocol.RECORD_ACK -> {
                val ack = Protocol.json.decodeFromString<Ack>(envelope.data.decodeToString())
                Protocol.requireSafeId(ack.id)
                if (ack.ok) outbox.markDelivered(ack.id)
            }
            Protocol.DELETE -> {
                val id = Protocol.json.decodeFromString<DeleteRecord>(envelope.data.decodeToString()).id
                onRemoteDelete(Protocol.requireSafeId(id))
            }
            Protocol.SETTINGS -> onSettings(Protocol.json.decodeFromString<MonitorSettings>(envelope.data.decodeToString()))
            Protocol.BP_CALIBRATION -> onCalibration(
                envelope.data.decodeToString().takeIf { it != "null" }?.let { Protocol.json.decodeFromString<BpCalibration>(it) }
            )
            Protocol.OPEN -> onOpen(envelope.data.decodeToString())
            Protocol.STATUS -> onStatus(Protocol.json.decodeFromString<PhoneStatus>(envelope.data.decodeToString()))
            Protocol.PROFILE -> onProfile(Protocol.json.decodeFromString<UserProfile>(envelope.data.decodeToString()))
            Protocol.BP_CALIBRATION_CAPTURE -> onCaptureRequest(
                Protocol.json.decodeFromString<CaptureRequest>(envelope.data.decodeToString())
            )
            Protocol.LOGS_REQUEST -> onLogRequest(Protocol.json.decodeFromString<LogRequest>(envelope.data.decodeToString()))
            Protocol.LOGS_ARCHIVE_ACK -> onArchiveAck(Protocol.json.decodeFromString<ArchiveAck>(envelope.data.decodeToString()))
            Protocol.MEASURE_CANCEL -> onMeasureCancel(Protocol.json.decodeFromString<MeasureCancel>(envelope.data.decodeToString()))
        }
    }
}

/**
 * Outbound phone → watch sync actions and deletion, decoupled from inbound [PhoneSyncEngine]
 * dispatch so phone repositories and UI models avoid circular DI dependencies.
 */
class PhoneSyncSender(private val transport: SyncTransport, private val sink: RecordSink) {
    suspend fun sendCalibration(calibration: BpCalibration?): Boolean =
        transport.send(Protocol.BP_CALIBRATION, Protocol.json.encodeToString(calibration).encodeToByteArray())

    suspend fun requestCapture(request: CaptureRequest): Boolean =
        transport.send(Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(request).encodeToByteArray())

    /** Asks the watch to open a measurement screen (it shows a tap-to-open notification). */
    suspend fun openOnWatch(route: String): Boolean = transport.send(Protocol.OPEN, route.encodeToByteArray())

    /** Phone → watch: stop the measurement this phone started. */
    suspend fun cancelMeasure(sessionId: String): Boolean =
        transport.sendImmediate(Protocol.MEASURE_CANCEL, Protocol.json.encodeToString(MeasureCancel(sessionId)).encodeToByteArray())

    suspend fun sendProfile(profile: UserProfile): Boolean =
        transport.send(Protocol.PROFILE, Protocol.json.encodeToString(profile).encodeToByteArray())

    /** Phone → watch: a moved log file is stored here; the watch may delete it. */
    suspend fun ackArchive(ack: ArchiveAck): Boolean =
        transport.send(Protocol.LOGS_ARCHIVE_ACK, Protocol.json.encodeToString(ack).encodeToByteArray())

    /** Phone → watch: send back (or erase) your diagnostic log. */
    suspend fun requestLogs(request: LogRequest): Boolean =
        transport.send(Protocol.LOGS_REQUEST, Protocol.json.encodeToString(request).encodeToByteArray())

    suspend fun sendStatus(status: PhoneStatus): Boolean =
        transport.send(Protocol.STATUS, Protocol.json.encodeToString(status).encodeToByteArray())

    suspend fun sendSettings(settings: MonitorSettings): Boolean =
        transport.send(Protocol.SETTINGS, Protocol.json.encodeToString(settings).encodeToByteArray())

    suspend fun requestDelete(id: String) {
        sink.delete(id)
        transport.send(Protocol.DELETE, Protocol.json.encodeToString(DeleteRecord(id)).encodeToByteArray())
    }
}

/**
 * Phone side: joins meta + waveform (either may arrive first), saves once, and acks. Records
 * already stored are acked again without re-saving (idempotency on [RecordMeta.id]).
 */
class PhoneSyncEngine(
    private val transport: SyncTransport,
    private val sink: RecordSink,
    private val heart: HeartDataSink? = null,
    private val onHello: suspend (Hello) -> Unit = {},
    private val onCaptureResult: suspend (CaptureResult) -> Unit = {},
    private val onSetupRequest: suspend (SetupRequest) -> Unit = {},
    private val onSettings: suspend (MonitorSettings) -> Unit = {},
    /** A raw BP session log arrived (id, encoded bytes); acked after it returns. */
    private val onSessionLog: suspend (String, ByteArray) -> Unit = { _, _ -> },
    private val onLogs: suspend (requestId: String, text: ByteArray) -> Unit = { _, _ -> },
    /** A log segment arrived whole (the app reads segment channels as streams, not through here). */
    private val onLogSegment: suspend (requestId: String, input: InputStream) -> Unit = { _, _ -> },
    /** A log file the watch moved here (read as a stream in the app); [ackArchive] once stored. */
    private val onArchive: suspend (type: String, name: String, input: InputStream) -> Unit = { _, _, _ -> },
    /** Progress of a measurement this phone started. */
    private val onMeasureState: suspend (MeasureState) -> Unit = {},
    /** How a measurement this phone started ended; acked after it returns. */
    private val onMeasureResult: suspend (MeasureResult) -> Unit = {},
    /** A message that could not be handled (it stays unacknowledged, so the watch sends it again). */
    private val onError: (path: String, error: Throwable) -> Unit = { _, _ -> },
    val sender: PhoneSyncSender = PhoneSyncSender(transport, sink)
) {
    private val metas = mutableMapOf<String, RecordMeta>()
    private val waves = mutableMapOf<String, FloatArray>()
    private val lock = Mutex()
    private val heartLock = Mutex()

    /** Safe to call concurrently (listener callbacks arrive on arbitrary threads). */
    suspend fun handle(envelope: Envelope) {
        runCatching { handleLocked(envelope) }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            onError(envelope.path, it)
        }
    }

    private suspend fun handleLocked(envelope: Envelope) {
        when {
            envelope.path == Protocol.RECORD_META -> lock.withLock {
                val meta = Protocol.json.decodeFromString<RecordMeta>(envelope.data.decodeToString())
                Protocol.requireSafeId(meta.id)
                if (sink.contains(meta.id)) {
                    metas.remove(meta.id)
                    waves.remove(meta.id)
                    ack(meta.id)
                } else {
                    if (metas.size >= MAX_PENDING) metas.keys.firstOrNull()?.let(metas::remove)
                    metas[meta.id] = meta
                    tryComplete(meta.id)
                }
            }
            envelope.path == Protocol.HR_BATCH -> heartLock.withLock {
                val batch = Protocol.json.decodeFromString<HrBatch>(envelope.data.decodeToString())
                heart?.saveBatch(batch)
                ack(batch.id)
            }
            envelope.path == Protocol.ALERT -> heartLock.withLock {
                val alert = Protocol.json.decodeFromString<HealthAlert>(envelope.data.decodeToString())
                heart?.saveAlert(alert)
                ack(alert.id)
            }
            envelope.path == Protocol.HELLO -> onHello(Protocol.json.decodeFromString<Hello>(envelope.data.decodeToString()))
            envelope.path == Protocol.SETTINGS -> onSettings(
                Protocol.json.decodeFromString<MonitorSettings>(envelope.data.decodeToString())
            )
            envelope.path == Protocol.SETUP_REQUEST -> onSetupRequest(
                Protocol.json.decodeFromString<SetupRequest>(envelope.data.decodeToString())
            )
            envelope.path == Protocol.BP_CALIBRATION_CAPTURE -> {
                val result = Protocol.json.decodeFromString<CaptureResult>(envelope.data.decodeToString())
                onCaptureResult(result)
                ack(result.id)
            }
            envelope.path == Protocol.MEASURE_STATE -> onMeasureState(
                Protocol.json.decodeFromString<MeasureState>(envelope.data.decodeToString())
            )
            envelope.path == Protocol.MEASURE_RESULT -> {
                val result = Protocol.json.decodeFromString<MeasureResult>(envelope.data.decodeToString())
                onMeasureResult(result)
                ack(result.id)
            }
            envelope.path.startsWith(Protocol.LOGS_PREFIX) -> onLogs(envelope.path.removePrefix(Protocol.LOGS_PREFIX), envelope.data)
            envelope.path.startsWith(Protocol.LOGS_ARCHIVE_PREFIX) -> {
                val (type, name) = envelope.path.removePrefix(Protocol.LOGS_ARCHIVE_PREFIX).split('/', limit = 2).let {
                    it[0] to
                        it.getOrElse(1) { "" }
                }
                onArchive(type, name, envelope.data.inputStream())
            }
            envelope.path.startsWith(Protocol.LOGS_SEGMENT_PREFIX) ->
                onLogSegment(envelope.path.removePrefix(Protocol.LOGS_SEGMENT_PREFIX), envelope.data.inputStream())
            envelope.path.startsWith(Protocol.BP_SESSION_PREFIX) -> {
                val id = Protocol.requireSafeId(envelope.path.removePrefix(Protocol.BP_SESSION_PREFIX))
                onSessionLog(id, envelope.data)
                ack(id)
            }
            envelope.path.startsWith(Protocol.RECORD_WAVE_PREFIX) -> lock.withLock {
                val id = Protocol.requireSafeId(envelope.path.removePrefix(Protocol.RECORD_WAVE_PREFIX))
                if (sink.contains(id)) {
                    metas.remove(id)
                    waves.remove(id)
                    ack(id)
                    return@withLock
                }
                if (waves.size >= MAX_PENDING) waves.keys.firstOrNull()?.let(waves::remove)
                waves[id] = WaveCodec.decode(envelope.data).samples
                tryComplete(id)
            }
        }
    }

    suspend fun sendCalibration(calibration: BpCalibration?): Boolean = sender.sendCalibration(calibration)

    suspend fun requestCapture(request: CaptureRequest): Boolean = sender.requestCapture(request)

    /** Asks the watch to open a measurement screen (it shows a tap-to-open notification). */
    suspend fun openOnWatch(route: String): Boolean = sender.openOnWatch(route)

    /** Phone → watch: stop the measurement this phone started. */
    suspend fun cancelMeasure(sessionId: String): Boolean = sender.cancelMeasure(sessionId)

    suspend fun sendProfile(profile: UserProfile): Boolean = sender.sendProfile(profile)

    /** Phone → watch: a moved log file is stored here; the watch may delete it. */
    suspend fun ackArchive(ack: ArchiveAck): Boolean = sender.ackArchive(ack)

    /** Phone → watch: send back (or erase) your diagnostic log. */
    suspend fun requestLogs(request: LogRequest): Boolean = sender.requestLogs(request)

    suspend fun sendStatus(status: PhoneStatus): Boolean = sender.sendStatus(status)

    suspend fun sendSettings(settings: MonitorSettings): Boolean = sender.sendSettings(settings)

    suspend fun requestDelete(id: String) = sender.requestDelete(id)

    private suspend fun tryComplete(id: String) {
        val meta = metas[id] ?: return
        val wave = waves[id]
        if (meta.sampleCount > 0 && wave == null) return
        sink.save(meta, wave)
        metas.remove(id)
        waves.remove(id)
        ack(id)
    }

    private suspend fun ack(id: String) {
        transport.send(Protocol.RECORD_ACK, Protocol.json.encodeToString(Ack(id, ok = true)).encodeToByteArray())
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}
