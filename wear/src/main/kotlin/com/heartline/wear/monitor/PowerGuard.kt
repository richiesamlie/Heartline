// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.heartline.shared.vitals.Spo2Schedule

/**
 * Battery and Power Saver policy for optional active background work on the watch.
 *
 * Passive heart-rate batching and immediate user measurements remain active; active periodic
 * sensor windows, stand probes, daytime vitals, and uncharged log offloading step back under
 * Power Saver or below [LOW_BATTERY].
 */
object PowerGuard {
    const val LOW_BATTERY = Spo2Schedule.LOW_BATTERY

    /**
     * Current battery percentage `0..100`, or `null` when unknown (such as in unseeded unit tests
     * where `BatteryManager.BATTERY_PROPERTY_CAPACITY` defaults to 0 with no sticky broadcast).
     */
    fun batteryPercent(context: Context): Int? {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) return ((level * 100f) / scale).toInt().coerceIn(0, 100)
        }
        return context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 1..100 }
    }

    fun isLowBattery(context: Context): Boolean = (batteryPercent(context) ?: 100) < LOW_BATTERY

    fun isPowerSaveMode(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    fun shouldThrottleActiveMonitoring(context: Context): Boolean =
        isPowerSaveMode(context) || isLowBattery(context)
}
