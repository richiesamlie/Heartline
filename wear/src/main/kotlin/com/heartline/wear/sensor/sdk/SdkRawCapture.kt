// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor.sdk

import com.heartline.logging.HLog
import com.heartline.wear.diag.RawCapture
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import java.lang.reflect.Modifier

/** Samsung SDK tracker capture helper: extracts scalar and list keys from [DataPoint] into [RawCapture]. */
object SdkRawCapture {
    /** Every value of [points] from Samsung tracker [tracker] (a TrackerKind / HealthTrackerType name). */
    fun sdk(tracker: String, points: List<DataPoint>) {
        if (!RawCapture.isRecording() || points.isEmpty()) return
        runCatching {
            val keys = SdkKeys.forTracker(tracker)
            val t = LongArray(points.size) { points[it].timestamp * 1_000_000 }
            RawCapture.writeRows(tracker, keys.scalarNames, t, points.map { p -> FloatArray(keys.scalars.size) { i -> number(p.value(keys.scalars[i])) } })
            // List values (the heart-rate tracker's IBIs and their statuses): one row per element.
            for ((i, key) in keys.lists.withIndex()) {
                val times = ArrayList<Long>()
                val rows = ArrayList<FloatArray>()
                points.forEach { p ->
                    (p.value(key) as? List<*>)?.forEachIndexed { index, v ->
                        times += p.timestamp * 1_000_000
                        rows += floatArrayOf(number(v), index.toFloat())
                    }
                }
                if (times.isNotEmpty()) RawCapture.writeRows("$tracker.${keys.listNames[i]}", listOf("value", "index"), times.toLongArray(), rows)
            }
        }.onFailure { HLog.w(RawCapture.TAG, "raw $tracker: ${it.message}") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun DataPoint.value(key: ValueKey<*>): Any? = runCatching { getValue(key as ValueKey<Any?>) }.getOrNull()

    private fun number(v: Any?): Float = when (v) {
        is Number -> v.toFloat()
        is Boolean -> if (v) 1f else 0f
        else -> Float.NaN
    }
}

/**
 * The Samsung SDK's value keys by tracker, found from the public `ValueKey.<Type>Set` fields, so a
 * key the SDK adds later is recorded too. Scalars become columns; list values (IBIs) their own stream.
 */
internal class SdkKeys(val scalarNames: List<String>, val scalars: List<ValueKey<*>>, val listNames: List<String>, val lists: List<ValueKey<*>>) {
    companion object {
        private val SETS = mapOf(
            "ECG_ON_DEMAND" to listOf("EcgSet"),
            "PPG_ON_DEMAND" to listOf("PpgSet"),
            "PPG_CONTINUOUS" to listOf("PpgSet", "PpgGreenSet", "PpgIrSet", "PpgRedSet"),
            "HEART_RATE_CONTINUOUS" to listOf("HeartRateSet"),
            "SPO2_ON_DEMAND" to listOf("SpO2Set"),
            "SKIN_TEMPERATURE_ON_DEMAND" to listOf("SkinTemperatureSet"),
            "SKIN_TEMPERATURE_CONTINUOUS" to listOf("SkinTemperatureSet"),
            "BIA_ON_DEMAND" to listOf("BiaSet"),
            "MF_BIA_ON_DEMAND" to listOf("MfBiaSet"),
            "EDA_CONTINUOUS" to listOf("EdaSet"),
            "ACCELEROMETER_CONTINUOUS" to listOf("AccelerometerSet"),
            "SWEAT_LOSS" to listOf("SweatLossSet"),
        )
        private val cache = mutableMapOf<String, SdkKeys>()

        @Synchronized
        fun forTracker(tracker: String): SdkKeys = cache.getOrPut(tracker) {
            val sets = SETS[tracker] ?: SETS.values.flatten().distinct()
            val scalars = linkedMapOf<ValueKey<*>, String>()
            val lists = linkedMapOf<ValueKey<*>, String>()
            for (set in sets) {
                val type = runCatching { Class.forName("${ValueKey::class.java.name}\$$set") }.getOrNull() ?: continue
                for (field in type.fields) {
                    if (!Modifier.isStatic(field.modifiers) || !ValueKey::class.java.isAssignableFrom(field.type)) continue
                    val key = runCatching { field.get(null) as? ValueKey<*> }.getOrNull() ?: continue
                    if (key in scalars || key in lists) continue
                    val name = if (sets.size > 1) "$set.${field.name}" else field.name
                    if (field.genericType.typeName.contains("java.util.List")) lists[key] = name else scalars[key] = name
                }
            }
            SdkKeys(scalars.values.toList(), scalars.keys.toList(), lists.values.toList(), lists.keys.toList())
        }
    }
}
