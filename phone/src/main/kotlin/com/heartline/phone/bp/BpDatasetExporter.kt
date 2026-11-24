// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.bp

import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpDataset
import com.heartline.shared.bp.BpDatasetEntry
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds diagnostic and offline-research exports of blood-pressure calibrations, cuff checks,
 * waveforms, and raw `.hlbp` session logs.
 */
class BpDatasetExporter(
    private val bp: BpRepository,
    private val records: RecordRepository,
) {
    /** Every saved calibration and cuff check (with session id) for diagnostic archives. */
    suspend fun diagnosticsFiles(): Map<String, ByteArray> {
        val calibrations = bp.calibrationJsonElements()
        val checks = StringBuilder("atMs,readingId,sessionId,watchSystolic,watchDiastolic,cuffSystolic,cuffDiastolic\n")
        bp.validations.first().sortedBy { it.atMs }.forEach { v ->
            val sessionId = (records.get(v.readingId)?.summary as? RecordSummary.BloodPressure)?.sessionId.orEmpty()
            checks.append("${v.atMs},${v.readingId},$sessionId,${v.watchSystolic},${v.watchDiastolic},${v.cuffSystolic},${v.cuffDiastolic}\n")
        }
        return mapOf(
            "bp/calibrations.json" to Protocol.json.encodeToString(JsonArray(calibrations)).encodeToByteArray(),
            "bp/cuff-checks.csv" to checks.toString().encodeToByteArray(),
        )
    }

    /** The user's BP data for offline analysis (`tools/ml/bp-ml`): calibration and cuff-checked readings with raw PPG. */
    suspend fun dataset(): BpDataset? {
        val cal = bp.calibration.first() ?: return null
        val rates = mutableListOf<Int>()
        val entries = bp.validations.first().mapNotNull { v ->
            val record = records.get(v.readingId) ?: return@mapNotNull null
            val wave = records.wave(record) ?: return@mapNotNull null
            val s = record.summary as? RecordSummary.BloodPressure
            val fs = record.sampleRateHz.takeIf { it > 0 }
            fs?.let { rates += it }
            BpDatasetEntry(
                record.startedAtMs,
                wave.toList(),
                v.cuffSystolic,
                v.cuffDiastolic,
                s?.watchSystolic ?: s?.systolic,
                s?.watchDiastolic ?: s?.diastolic,
                s?.sessionId,
                fs,
            )
        }
        val dominantFs = rates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: BpCalibration.PPG_FS
        return BpDataset(sampleRateHz = dominantFs, calibration = cal, entries = entries)
    }

    /** Every raw session log, calibration, and cuff-checked dataset bundled into one zip at [target]. */
    suspend fun exportSessions(target: File): File = withContext(Dispatchers.IO) {
        val data = dataset()
        val files = bp.sessionFiles()
        ZipOutputStream(target.apply { parentFile?.mkdirs() }.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            data?.let { put("dataset.json", Protocol.json.encodeToString(it).encodeToByteArray()) }
            files.forEach { put("sessions/${it.name}", it.readBytes()) }
            put("README.txt", SESSIONS_README.encodeToByteArray())
        }
        target
    }

    private companion object {
        const val SESSIONS_README = """Heartline blood-pressure export
dataset.json: the calibration (every round with its channels) and every cuff check (cuff reading, the
watch's reading and its session id).
sessions/<id>.hlbp: one raw session each (gzip; format in shared/.../bp/BpSessionLog.kt): every sample
of every sensor with its timestamp, events, intermediate values and the result.
Read with tools/ml/bp-ml/read_session.py.
"""
    }
}
