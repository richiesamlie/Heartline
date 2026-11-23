// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.heartline.logging.HLog
import kotlinx.coroutines.CompletableDeferred

/**
 * Wakes the watch when a rhythm window has listened long enough. A real log (10/09) had windows
 * of 6–12 minutes that were awake for only 3–48 s: the processor slept through the wake lock, the
 * window's timers stood still, and the heart-rate sensor stayed on until the tracker's next batch.
 * An allow-while-idle alarm (no permission; at most about one per 9 minutes in deep sleep, and
 * windows are 15 minutes apart) wakes the watch at the window's end, so it can stop the sensor.
 */
object WindowAlarm {
    private const val TAG = "Heartline/Monitor"
    const val DEFAULT_OWNER = "default"
    const val EXTRA_OWNER = "owner"

    private class Entry(var stop: CompletableDeferred<String>? = null, var wakeLock: PowerManager.WakeLock? = null)

    /** Active stop signals and wake locks keyed by owner. */
    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun begin(stop: CompletableDeferred<String>, owner: String = DEFAULT_OWNER) {
        val entry = entries.getOrPut(owner) { Entry() }
        releaseWakeLockLocked(entry)
        entry.stop = stop
    }

    /** Clears the window (it ended); a late alarm then does nothing. */
    @Synchronized
    fun end(stop: CompletableDeferred<String>, owner: String = DEFAULT_OWNER) {
        val entry = entries[owner] ?: return
        if (entry.stop === stop) {
            entry.stop = null
            releaseWakeLockLocked(entry)
            entries.remove(owner)
        }
    }

    /** The alarm went off: ends the running window. @return whether there was one to end. */
    @Synchronized
    fun fire(wake: PowerManager.WakeLock? = null, owner: String = DEFAULT_OWNER): Boolean {
        val entry = entries[owner]
        val ended = if (entry != null && entry.stop?.complete("alarm") == true) {
            releaseWakeLockLocked(entry)
            entry.wakeLock = wake
            true
        } else {
            wake?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
            false
        }
        return ended
    }

    private fun releaseWakeLockLocked(entry: Entry) {
        entry.wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        entry.wakeLock = null
    }

    fun schedule(context: Context, afterMs: Long, owner: String = DEFAULT_OWNER) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + afterMs, intent(context, owner))
        }.onFailure { HLog.w(TAG, "window alarm not set", it) }
    }

    fun cancel(context: Context, owner: String = DEFAULT_OWNER) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context, owner))
    }

    private fun intent(context: Context, owner: String) = PendingIntent.getBroadcast(
        context,
        owner.hashCode(),
        Intent(context, WindowAlarmReceiver::class.java).putExtra(EXTRA_OWNER, owner),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Ends the running rhythm window, keeping the watch awake long enough to stop the sensor. */
class WindowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Held through the flush and the hand-over that follow (released by WindowAlarm.end or on timeout).
        val wake = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heartline:window-alarm")
            ?.apply {
                setReferenceCounted(false)
                acquire(AWAKE_MS)
            }
        val owner = intent.getStringExtra(WindowAlarm.EXTRA_OWNER) ?: WindowAlarm.DEFAULT_OWNER
        val ended = WindowAlarm.fire(wake, owner)
        HLog.i("Heartline/Monitor", "window alarm ($owner): ${if (ended) "ending the window" else "no window running"}")
    }

    private companion object {
        const val AWAKE_MS = 15_000L
    }
}
