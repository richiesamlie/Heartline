// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.wear.monitor.WindowAlarm
import com.heartline.wear.monitor.WindowAlarmReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WindowAlarmTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theAlarmEndsTheRunningWindowOnlyOnce() {
        val stop = CompletableDeferred<String>()
        WindowAlarm.begin(stop)
        assertTrue(WindowAlarm.fire())
        assertEquals("alarm", stop.getCompleted())
        // A second alarm, or one after the window ended, does nothing.
        assertFalse(WindowAlarm.fire())
        WindowAlarm.end(stop)
        assertFalse(WindowAlarm.fire())
    }

    @Test
    fun aWindowThatEndedByItselfIsNotChangedByALateAlarm() {
        val stop = CompletableDeferred<String>()
        WindowAlarm.begin(stop)
        stop.complete("covered")
        WindowAlarm.end(stop)
        assertFalse(WindowAlarm.fire())
        assertEquals("covered", stop.getCompleted())
    }

    @Test
    fun theAlarmIsSetForTheWindowAndCancelledAfter() {
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        WindowAlarm.schedule(context, 90_000)
        // peek: getNextScheduledAlarm also removes it.
        val alarm = alarms.peekNextScheduledAlarm()
        assertNotNull(alarm)
        assertTrue(alarm!!.isAllowWhileIdle)
        WindowAlarm.cancel(context)
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun `late or no-op alarm releases its wake lock immediately`() {
        val pm = context.getSystemService(PowerManager::class.java)!!
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:late-alarm").apply {
            setReferenceCounted(false)
            acquire(15_000L)
        }
        assertFalse(WindowAlarm.fire(wake))
        assertFalse(wake.isHeld)

        val stop = CompletableDeferred<String>()
        WindowAlarm.begin(stop)
        WindowAlarm.end(stop)
        WindowAlarmReceiver().onReceive(context, Intent(context, WindowAlarmReceiver::class.java))
        // When no window is running, fire(wake) inside onReceive must not leave a wake lock held.
        assertFalse(WindowAlarm.fire())
    }

    @Test
    fun `ending a window releases the alarm wake lock`() {
        val pm = context.getSystemService(PowerManager::class.java)!!
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:active-alarm").apply {
            setReferenceCounted(false)
            acquire(15_000L)
        }
        val stop = CompletableDeferred<String>()
        WindowAlarm.begin(stop)
        assertTrue(WindowAlarm.fire(wake))
        assertTrue(wake.isHeld)
        WindowAlarm.end(stop)
        assertFalse(wake.isHeld)
    }

    @Test
    fun `distinct owners do not overwrite or fire each other`() {
        val irn = CompletableDeferred<String>()
        val stand = CompletableDeferred<String>()
        WindowAlarm.begin(irn, owner = "irn")
        WindowAlarm.begin(stand, owner = "stand-wait")

        assertTrue(WindowAlarm.fire(owner = "irn"))
        assertEquals("alarm", irn.getCompleted())
        assertFalse(stand.isCompleted)
        WindowAlarm.end(irn, owner = "irn")

        WindowAlarmReceiver().onReceive(
            context,
            Intent(context, WindowAlarmReceiver::class.java).putExtra(WindowAlarm.EXTRA_OWNER, "stand-wait"),
        )
        assertEquals("alarm", stand.getCompleted())
        WindowAlarm.end(stand, owner = "stand-wait")
    }
}
