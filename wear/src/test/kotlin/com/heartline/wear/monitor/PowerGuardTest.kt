// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.heartline.wear.monitor.PowerGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PowerGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    @Suppress("DEPRECATION")
    fun tearDown() {
        shadowOf(context.getSystemService(PowerManager::class.java)!!).setIsPowerSaveMode(false)
        context.removeStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
    }

    @Test
    fun unknownBatteryDoesNotThrottleWhenPowerSaverIsOff() {
        assertNull(PowerGuard.batteryPercent(context))
        assertFalse(PowerGuard.isLowBattery(context))
        assertFalse(PowerGuard.isPowerSaveMode(context))
        assertFalse(PowerGuard.shouldThrottleActiveMonitoring(context))
    }

    @Test
    fun batteryThresholdAndPowerSaveModeDriveThrottling() {
        sendBattery(15)
        assertEquals(15, PowerGuard.batteryPercent(context))
        assertFalse(PowerGuard.isLowBattery(context))
        assertFalse(PowerGuard.shouldThrottleActiveMonitoring(context))

        sendBattery(14)
        assertEquals(14, PowerGuard.batteryPercent(context))
        assertTrue(PowerGuard.isLowBattery(context))
        assertTrue(PowerGuard.shouldThrottleActiveMonitoring(context))

        sendBattery(0)
        assertEquals(0, PowerGuard.batteryPercent(context))
        assertTrue(PowerGuard.isLowBattery(context))

        sendBattery(80)
        assertFalse(PowerGuard.shouldThrottleActiveMonitoring(context))
        shadowOf(context.getSystemService(PowerManager::class.java)!!).setIsPowerSaveMode(true)
        assertTrue(PowerGuard.isPowerSaveMode(context))
        assertTrue(PowerGuard.shouldThrottleActiveMonitoring(context))
    }

    @Suppress("DEPRECATION")
    private fun sendBattery(percent: Int) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, percent)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
        }
        context.sendStickyBroadcast(intent)
    }
}
