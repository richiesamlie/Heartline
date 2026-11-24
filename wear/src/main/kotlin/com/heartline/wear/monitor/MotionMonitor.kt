// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.heartline.wear.diag.RawCapture

/** Tracks recent steps so rhythm checks only use windows where the wearer is still. */
fun interface MotionMonitor {
    /** True if the wearer moved (took steps) since [sinceMs]. */
    fun movedSince(sinceMs: Long): Boolean

    /**
     * True if the wearer moved between [fromMs] and [toMs]. Heart-rate readings can arrive minutes
     * late, so a reading is judged by movement around its own time, not by any movement since.
     */
    fun movedBetween(fromMs: Long, toMs: Long): Boolean = movedSince(fromMs)
}

/**
 * When movements happened, by the sensor event's own time (events can arrive batched too).
 * Keeps the last [keepMs].
 */
internal class MoveTimes(private val keepMs: Long = 20 * 60_000L) {
    private val times = ArrayDeque<Long>()

    @Synchronized fun add(atMs: Long) {
        // One mark per half second is plenty.
        if (times.isNotEmpty() && atMs - times.last() < 500) return
        times.addLast(atMs)
        while (times.isNotEmpty() && atMs - times.first() > keepMs) times.removeFirst()
    }

    @Synchronized fun any(fromMs: Long, toMs: Long) = times.any { it in fromMs..toMs }

    @Synchronized fun latest() = times.lastOrNull() ?: 0L

    companion object {
        /** A sensor event's time (elapsed-realtime nanoseconds) as wall-clock milliseconds. */
        fun wallMs(event: SensorEvent) = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
    }
}

class StepMotionMonitor(context: Context) :
    MotionMonitor,
    SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val steps = MoveTimes()

    fun start() {
        manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() = manager?.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        RawCapture.android(event)
        steps.add(MoveTimes.wallMs(event))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun movedSince(sinceMs: Long) = steps.latest() >= sinceMs

    override fun movedBetween(fromMs: Long, toMs: Long) = steps.any(fromMs, toMs)
}

/**
 * Whether the watch is on the wrist (Android's off-body sensor, where the watch has one) and when
 * the arm last moved (accelerometer). Steps alone miss typing or gesturing, which spoil the
 * beat-to-beat intervals as much as walking does.
 *
 * With [turns], the gyroscope is watched too and smaller movements count: a slow turn of the
 * wrist barely shows on the accelerometer but spoils a blood-oxygen reading, and the sensor
 * reports "moved" for far less than our usual threshold. These finer marks only count for
 * [stillFor] (the oxygen wait), so the rhythm windows' movement marks stay as they were. When
 * [motion] is false (and [turns] is false), only the off-body sensor is registered.
 */
class WristState(
    context: Context,
    private val turns: Boolean = false,
    private val motion: Boolean = true
) :
    MotionMonitor,
    SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val offBodySensor = manager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
    private val accelerometer = if (motion || turns) manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
    private val gyroscope = if (turns) manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) else null

    @Volatile var offBody = false
        private set

    /** Whether the off-body sensor has reported at all since [start] (it can be late, or missing). */
    @Volatile var offBodyReported = false
        private set

    private val moves = MoveTimes()
    private val turnTimes = MoveTimes()
    private var gx = Double.NaN
    private var gy = Double.NaN
    private var gz = Double.NaN
    private var lastAccelNs = 0L

    fun start() {
        offBodySensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelerometer?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = manager?.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                offBody = event.values.firstOrNull() == 0f
                offBodyReported = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                val dtSec = if (lastAccelNs > 0L) (event.timestamp - lastAccelNs) / 1_000_000_000.0 else Double.NaN
                lastAccelNs = event.timestamp
                if (gx.isNaN() || !dtSec.isFinite() || dtSec < 0.0 || dtSec >= 5.0) {
                    gx = x.toDouble()
                    gy = y.toDouble()
                    gz = z.toDouble()
                } else {
                    val decay = kotlin.math.exp(-dtSec / GRAVITY_TAU_S)
                    gx = decay * gx + (1.0 - decay) * x
                    gy = decay * gy + (1.0 - decay) * y
                    gz = decay * gz + (1.0 - decay) * z
                }
                val dx = x - gx
                val dy = y - gy
                val dz = z - gz
                val change = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (change > MOVE_MS2) moves.add(MoveTimes.wallMs(event))
                if (turns && change > FINE_MOVE_MS2) turnTimes.add(MoveTimes.wallMs(event))
            }
            Sensor.TYPE_GYROSCOPE -> {
                val (x, y, z) = event.values
                if (kotlin.math.sqrt((x * x + y * y + z * z).toDouble()) > TURN_RAD_S) turnTimes.add(MoveTimes.wallMs(event))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun movedSince(sinceMs: Long) = moves.latest() >= sinceMs

    override fun movedBetween(fromMs: Long, toMs: Long) = moves.any(fromMs, toMs)

    /** How long the arm has been still at [nowMs] (moved or turned), counting from [sinceMs] at the most. */
    fun stillFor(nowMs: Long, sinceMs: Long): Long = nowMs - maxOf(sinceMs, moves.latest(), turnTimes.latest())

    private companion object {
        /** A resting arm stays within about 0.15 m/s² of its average; this is a clear movement. */
        const val MOVE_MS2 = 1.0

        /** For the oxygen wait: twice a resting arm's wobble (about 0.15 m/s²). */
        const val FINE_MOVE_MS2 = 0.3

        /** A still wrist turns under about 0.1 rad/s (sensor noise); more spoils an oxygen reading. */
        const val TURN_RAD_S = 0.2

        /** Time constant (seconds) for the 3-axis dt-aware gravity low-pass filter. */
        const val GRAVITY_TAU_S = 0.5
    }
}
