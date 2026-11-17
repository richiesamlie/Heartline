// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.diag

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.Build
import android.os.SystemClock
import com.heartline.logging.HLog
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.diag.RawSessions
import java.io.File

/**
 * The watch's raw sensor recorder: every value from every Samsung tracker and Android sensor the
 * app listens to, sample by sample, in [RawSessions] (one file per measurement, plus `other` for
 * background monitoring). Sensor listeners call `SdkRawCapture.sdk` or [android]; measurement
 * screens call [begin] and [end]. Only records while diagnostic logs are on ([setEnabled]).
 */
object RawCapture {
    /** Raw sessions on the watch (the text log has its own 20 MB); older ones move to the phone. */
    const val BUDGET_BYTES = 30L * 1024 * 1024

    @Volatile private var sessions: RawSessions? = null

    fun init(context: Context, version: String, onSaved: (File) -> Unit) {
        if (sessions != null) return
        sessions = RawSessions(
            File(context.filesDir, "raw"),
            BUDGET_BYTES,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            appVersion = version,
            onSaved = onSaved,
        )
    }

    fun setEnabled(on: Boolean) {
        val s = sessions ?: return
        if (!on && s.enabled) s.clear()
        s.enabled = on
    }

    /**
     * Starts a measurement session ("ecg", "spo2", "bia", …); returns its id for [end]. A
     * [background] one (a monitoring window) doesn't interrupt a measurement that is running.
     */
    fun begin(kind: String, notes: Map<String, String> = emptyMap(), background: Boolean = false): String =
        sessions?.begin(kind, notes.redacted(), background).orEmpty()

    fun end(id: String, values: Map<String, Double?> = emptyMap(), notes: Map<String, String> = emptyMap()) {
        sessions?.end(id, values, notes.redacted())
    }

    /** Notes are free text (a state's toString()): the user's names and birth date never reach a session file. */
    private fun Map<String, String>.redacted() = mapValues { (_, v) -> HLog.redact(v) }

    /** Ends measurement [id] without keeping it (the blood-pressure flow keeps its own, complete log). */
    fun discard(id: String) {
        sessions?.discard(id)
    }

    fun event(type: String, detail: String = "") {
        sessions?.event(type, HLog.redact(detail))
    }

    /** The blood-pressure flow records its own complete session; a copy is kept with the others. */
    fun saveBpSession(log: BpSessionLog) {
        sessions?.saveLog("bp", log)
    }

    fun files(): List<File> = sessions?.files().orEmpty()

    fun sizeBytes(): Long = sessions?.sizeBytes() ?: 0

    /** Closes the background session so an export has everything. */
    fun flush() {
        sessions?.flush()
        sessions?.awaitWritten()
    }

    fun clear() {
        sessions?.clear()
    }

    internal fun isRecording(): Boolean = sessions?.enabled == true

    internal fun writeRows(stream: String, columns: List<String>, timestampsNs: LongArray, values: List<FloatArray>) {
        val s = sessions ?: return
        if (!s.enabled || values.isEmpty()) return
        s.rows(stream, columns, timestampsNs, values)
    }

    /** Values that come from elsewhere (Health Services): one row per (wall-clock ms, values). */
    fun values(stream: String, columns: List<String>, rows: List<Pair<Long, FloatArray>>) {
        val s = sessions ?: return
        if (!s.enabled || rows.isEmpty()) return
        s.rows(stream, columns, LongArray(rows.size) { rows[it].first * 1_000_000 }, rows.map { it.second })
    }

    /** Every value of an Android sensor event (accelerometer, gyroscope, rotation, …). */
    fun android(event: SensorEvent) {
        val s = sessions ?: return
        if (!s.enabled) return
        // Sensor time is elapsed-realtime ns; the sessions use the wall clock.
        val wallNs = event.timestamp + (System.currentTimeMillis() * 1_000_000 - SystemClock.elapsedRealtimeNanos())
        val n = event.values.size
        val columns = List(n) { "v$it" } + "accuracy"
        s.rows(
            "android.${sensorName(event.sensor)}",
            columns,
            longArrayOf(wallNs),
            listOf(FloatArray(n + 1) { if (it < n) event.values[it] else event.accuracy.toFloat() }),
        )
    }

    private fun sensorName(sensor: Sensor): String = sensor.stringType.substringAfterLast('.').ifEmpty { "type${sensor.type}" }

    const val TAG = "Heartline/Raw"
}
