// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import com.heartline.logging.HLog
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpProfile
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.bp.BpSessionReplay
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.RecordKind
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.PhoneSyncSender
import com.heartline.shared.sync.Protocol
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

/** A watch reading compared with a cuff reading taken right after it (validation mode). */
data class BpValidation(
    val id: String,
    val readingId: String,
    val atMs: Long,
    val watchSystolic: Int,
    val watchDiastolic: Int,
    val cuffSystolic: Int,
    val cuffDiastolic: Int,
)

@Entity(tableName = "bp_calibrations", indices = [Index("createdAtMs")])
internal data class BpCalibrationEntity(@PrimaryKey val id: String, val createdAtMs: Long, val json: String)

@Entity(tableName = "bp_validations", indices = [Index("atMs"), Index("readingId")])
internal data class BpValidationEntity(
    @PrimaryKey val id: String,
    val readingId: String,
    val atMs: Long,
    val watchSystolic: Int,
    val watchDiastolic: Int,
    val cuffSystolic: Int,
    val cuffDiastolic: Int,
)

@Dao
internal interface BpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(calibration: BpCalibrationEntity)

    @Query("SELECT * FROM bp_calibrations ORDER BY createdAtMs DESC LIMIT 1")
    fun latest(): Flow<BpCalibrationEntity?>

    @Query("DELETE FROM bp_calibrations")
    suspend fun deleteAll()

    /** Every calibration kept, oldest first (the diagnostic export includes their cuff values). */
    @Query("SELECT * FROM bp_calibrations ORDER BY createdAtMs")
    suspend fun all(): List<BpCalibrationEntity>

    @Query("DELETE FROM bp_calibrations WHERE id LIKE 'demo%'")
    suspend fun deleteDemo(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertValidation(validation: BpValidationEntity)

    @Query("SELECT * FROM bp_validations ORDER BY atMs DESC")
    fun validations(): Flow<List<BpValidationEntity>>

    @Query("DELETE FROM bp_validations")
    suspend fun deleteValidations()
}

/**
 * Blood-pressure calibration (source of truth on the phone), cuff checks, raw sessions, and watch sync.
 * Personal model refinement lives in [com.heartline.phone.bp.BpRefiner], and diagnostic/dataset export in
 * [com.heartline.phone.bp.BpDatasetExporter].
 */
class BpRepository internal constructor(
    private val dao: BpDao,
    private val records: RecordRepository,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the watch's raw session logs are kept (algorithm 6); null keeps none. */
    private val sessionsDir: java.io.File? = null,
    private val sender: PhoneSyncSender,
) {
    private val captures = MutableSharedFlow<CaptureResult>(extraBufferCapacity = 8)
    private val sessionLock = Mutex()
    private val calibrationLock = Mutex()

    /** Calibration rounds recorded on the watch, as they arrive. */
    val captureResults: SharedFlow<CaptureResult> = captures.asSharedFlow()

    val calibration: Flow<BpCalibration?> = dao.latest().map { it?.let { e -> Protocol.json.decodeFromString<BpCalibration>(e.json) } }

    val readings: Flow<List<HealthRecord>> = records.observe(RecordKind.BLOOD_PRESSURE)

    suspend fun onCaptureResult(result: CaptureResult) = captures.emit(result)

    suspend fun requestCapture(request: CaptureRequest) = sender.requestCapture(request)

    suspend fun saveCalibration(stored: BpCalibration) = calibrationLock.withLock {
        saveCalibrationLocked(stored)
    }

    private suspend fun saveCalibrationLocked(stored: BpCalibration) {
        // A round the watch read the other way up is read again the calibration's way (algorithm 6.2).
        val calibration = withContext(Dispatchers.Default) { stored.aligned() }
        HLog.i(
            TAG,
            "BP calibration saved: id=${calibration.id.take(8)} rounds=${calibration.points.size} checks=${calibration.extraPoints.size} " +
                "cuff=[${calibration.timedPoints().joinToString(" ") { (p, _) -> "${p.cuffSystolic}/${p.cuffDiastolic}${if (p.standing) "s" else ""}" }}] " +
                "polarity=${calibration.polarity(BpChannel.PWA_GREEN)}/${calibration.polarity(BpChannel.PWA_IR)} valid=${calibration.isValid(now())}",
        )
        dao.insert(BpCalibrationEntity(calibration.id, calibration.createdAtMs, Protocol.json.encodeToString(calibration)))
        sender.sendCalibration(calibration)
    }

    /**
     * Re-sends the active calibration (after the watch says hello); [orNull] also tells the watch to
     * drop its copy when there is none. An older calibration is first upgraded to the current
     * features from its stored raw PPG.
     */
    suspend fun resendCalibration(orNull: Boolean = false) = calibrationLock.withLock {
        val current = calibration.first()?.let { upgradeIfNeededLocked(it) }
        if (current != null || orNull) sender.sendCalibration(current)
    }

    /** Stores the health profile with the current calibration and sends it to the watch. */
    suspend fun saveProfile(profile: BpProfile) = calibrationLock.withLock {
        val cal = calibration.first() ?: return@withLock
        if (cal.profile != profile) saveCalibrationLocked(cal.copy(profile = profile))
    }

    private suspend fun upgradeIfNeededLocked(cal: BpCalibration): BpCalibration {
        val up = withContext(Dispatchers.Default) { cal.upgraded().aligned() }
        if (up != cal) dao.insert(BpCalibrationEntity(up.id, up.createdAtMs, Protocol.json.encodeToString(up)))
        return up
    }

    val validations: Flow<List<BpValidation>> = dao.validations().map { list -> list.map { it.toModel() } }

    suspend fun deleteDemo(): Int = dao.deleteDemo()

    /** Cuff readings for sessions that haven't arrived yet (the log follows its round by a few seconds). */
    private val pendingCuff = java.util.concurrent.ConcurrentHashMap<String, Triple<Int, Int, Int?>>()

    private fun sessionFile(dir: File, id: String): File? {
        if (!Protocol.isSafeId(id)) {
            HLog.w(TAG, "rejected invalid session id: $id")
            return null
        }
        val base = dir.canonicalFile.toPath()
        val target = File(dir, "$id.hlbp").canonicalFile
        return target.takeIf { it.toPath().startsWith(base) && it.toPath() != base }
    }

    private fun writeSessionAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    /** Keeps a raw session log from the watch (every sensor of one measurement or calibration round). */
    suspend fun saveSession(id: String, bytes: ByteArray) {
        val dir = sessionsDir ?: return
        val file = sessionFile(dir, id) ?: return
        // Only a well-formed log is kept.
        val log = runCatching { BpSessionLog.decode(bytes) }.onFailure { HLog.w(TAG, "bad session $id", it) }.getOrNull() ?: return
        withContext(Dispatchers.IO) {
            sessionLock.withLock {
                val cuff = pendingCuff.remove(id)
                val out = if (cuff == null) bytes else log.withCuff(cuff).encode()
                writeSessionAtomically(file, out)
            }
        }
    }

    /**
     * Writes the cuff reading taken with session [id] (a calibration round or a cuff check) into
     * its header, so each raw session is complete on its own; kept until the session arrives.
     */
    suspend fun annotateSession(id: String, systolic: Int, diastolic: Int, pulse: Int?) {
        if (!Protocol.isSafeId(id)) {
            HLog.w(TAG, "rejected invalid session id: $id")
            return
        }
        withContext(Dispatchers.IO) {
            sessionLock.withLock {
                val file = sessionsDir?.let { sessionFile(it, id) }
                val log = file?.takeIf { it.exists() }?.let { runCatching { BpSessionLog.decode(it.readBytes()) }.getOrNull() }
                if (file == null || log == null) {
                    pendingCuff[id] = Triple(systolic, diastolic, pulse)
                    return@withLock
                }
                writeSessionAtomically(file, log.withCuff(Triple(systolic, diastolic, pulse)).encode())
            }
        }
    }

    private fun BpSessionLog.withCuff(cuff: Triple<Int, Int, Int?>) =
        copy(header = header.copy(cuffSystolic = cuff.first, cuffDiastolic = cuff.second, cuffPulse = cuff.third))

    suspend fun session(id: String): BpSessionLog? = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            sessionsDir?.let { sessionFile(it, id) }?.takeIf { it.exists() }?.let { runCatching { BpSessionLog.decode(it.readBytes()) }.getOrNull() }
        }
    }

    internal suspend fun calibrationJsonElements(): List<JsonElement> =
        dao.all().map { Protocol.json.parseToJsonElement(it.json) }

    internal fun sessionFiles(): List<File> =
        sessionsDir?.listFiles { f -> f.extension == "hlbp" }?.sortedBy { it.name }.orEmpty()

    /**
     * Stores a cuff check and adds it to the calibration as a point (features from the reading's
     * pulse wave, when the watch sent it), then sends the updated calibration to the watch.
     */
    suspend fun addValidation(validation: BpValidation) {
        dao.insertValidation(
            BpValidationEntity(
                validation.id,
                validation.readingId,
                validation.atMs,
                validation.watchSystolic,
                validation.watchDiastolic,
                validation.cuffSystolic,
                validation.cuffDiastolic,
            ),
        )
        val record = records.get(validation.readingId) ?: return
        val sessionId = (record.summary as? RecordSummary.BloodPressure)?.sessionId
        sessionId?.let { annotateSession(it, validation.cuffSystolic, validation.cuffDiastolic, null) }
        calibrationLock.withLock {
            val cal = calibration.first()?.takeIf { it.isValid(now()) } ?: return@withLock
            val at = record.startedAtMs
            // With the reading's raw session, the cuff check teaches every channel (IR, BCG, ECG transit times).
            val fromSession = sessionId?.let { session(it) }?.let { log ->
                val input = BpSessionReplay.input(log)
                val sessionFs = input.precise?.fs ?: input.fs
                withContext(Dispatchers.Default) {
                    BpPipeline.capture(input, cal.polarity(BpChannel.PWA_GREEN, sessionFs), cal.polarity(BpChannel.PWA_IR, sessionFs))
                }
            }?.let { CalibrationPoint.of(it, validation.cuffSystolic, validation.cuffDiastolic, null, at, sessionId = sessionId) }
            val point = fromSession ?: run {
                val rawWave = records.wave(record) ?: return@withLock
                val fs = record.sampleRateHz.takeIf { it > 0 } ?: BpCalibration.PPG_FS
                val features = withContext(Dispatchers.Default) {
                    val clean = com.heartline.shared.dsp.PpgRepair.repair(rawWave) ?: rawWave
                    PpgFeatures.extract(clean, fs, cal.polarity(BpChannel.PWA_GREEN, fs))
                } ?: return@withLock
                // The reading was compared with this calibration, so its wave is the calibration's.
                CalibrationPoint(
                    features,
                    validation.cuffSystolic,
                    validation.cuffDiastolic,
                    null,
                    null,
                    at,
                    ppgFs = fs,
                    sessionId = sessionId,
                    wave = cal.wave(),
                )
            }
            saveCalibrationLocked(upgradeIfNeededLocked(cal).withExtraPoint(point))
        }
    }

    suspend fun deleteAll() {
        calibrationLock.withLock {
            sessionLock.withLock {
                dao.deleteAll()
                dao.deleteValidations()
                pendingCuff.clear()
                withContext(Dispatchers.IO) { sessionsDir?.deleteRecursively() }
            }
        }
        sender.sendCalibration(null)
    }

    private fun BpValidationEntity.toModel() =
        BpValidation(id, readingId, atMs, watchSystolic, watchDiastolic, cuffSystolic, cuffDiastolic)

    private companion object {
        const val TAG = "Heartline/BP"
    }
}
