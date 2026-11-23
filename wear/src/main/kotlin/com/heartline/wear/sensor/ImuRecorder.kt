// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.heartline.logging.HLog
import com.heartline.shared.bp.BpSessionRecorder
import com.heartline.shared.bp.ImuStreams
import com.heartline.wear.diag.RawCapture
import kotlin.math.sqrt

/**
 * The watch's motion sensors during a blood-pressure session, recorded in full: accelerometer
 * (as fast as the watch allows, for the wrist ballistocardiogram), gyroscope and game rotation
 * vector (forearm angle, for the hydrostatic correction). Timestamps are the sensor's own,
 * moved to wall-clock nanoseconds so they line up with the PPG and ECG points.
 */
interface ImuRecorder : MotionMeter {
    /** The streams of the last recording (empty ones when a sensor is missing). */
    fun streams(): ImuStreams

    companion object {
        val NONE = object : ImuRecorder {
            override fun start() = Unit

            override fun stop(): Double? = null

            override fun streams() = ImuStreams.EMPTY
        }
    }
}

class AndroidImuRecorder(context: Context) : ImuRecorder, SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val accel = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotation = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private var accelW = writer(ImuStreams.ACCEL, "x", "y", "z")
    private var gyroW = writer(ImuStreams.GYRO, "x", "y", "z")
    private var rotationW = writer(ImuStreams.ROTATION, "x", "y", "z", "w")

    /** Sensor clock (elapsed realtime) → wall clock, ns. */
    @Volatile private var offsetNs = 0L

    @Volatile private var listening = false
    private var handlerThread: HandlerThread? = null

    private fun writer(name: String, vararg columns: String) = BpSessionRecorder.StreamWriter(name, columns.toList(), MAX_SAMPLES)

    @Synchronized
    override fun start() {
        // A recording still listening (never stopped) is ended first, never stacked.
        if (listening) {
            HLog.w(TAG, "IMU start while still recording: the earlier recording is stopped")
            stopListeningLocked()
        }
        HLog.i(TAG, "IMU start")
        listening = true
        accelW = writer(ImuStreams.ACCEL, "x", "y", "z")
        gyroW = writer(ImuStreams.GYRO, "x", "y", "z")
        rotationW = writer(ImuStreams.ROTATION, "x", "y", "z", "w")
        offsetNs = System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos()
        val thread = HandlerThread("heartline-imu").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        // As fast as the hardware goes: the ballistocardiogram needs ≥ 100 Hz, off the Main looper.
        listOfNotNull(accel, gyro, rotation).forEach { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
    }

    override fun stop(): Double? {
        synchronized(this) {
            if (listening) HLog.i(TAG, "IMU stop")
            stopListeningLocked()
        }
        val a = streams().accel ?: return null
        if (a.size < 20) return null
        val mags = DoubleArray(a.size) { i -> sqrt((0 until 3).sumOf { c -> a.values[i * 3 + c].toDouble().let { it * it } }) }
        val mean = mags.average()
        return sqrt(mags.sumOf { (it - mean) * (it - mean) } / mags.size)
    }

    private fun stopListeningLocked() {
        manager?.unregisterListener(this)
        handlerThread?.quitSafely()
        handlerThread = null
        listening = false
    }

    override fun gravity(): List<Double>? = streams().meanGravity()

    @Synchronized
    override fun streams() = ImuStreams(
        accelW.snapshot().takeIf { it.size > 0 },
        gyroW.snapshot().takeIf { it.size > 0 },
        rotationW.snapshot().takeIf { it.size > 0 },
    )

    override fun onSensorChanged(event: SensorEvent) {
        RawCapture.android(event)
        val t = event.timestamp + offsetNs
        val v = event.values
        synchronized(this) {
            if (!listening) return
            val stored = when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> accelW.append(t, v[0], v[1], v[2])
                Sensor.TYPE_GYROSCOPE -> gyroW.append(t, v[0], v[1], v[2])
                Sensor.TYPE_GAME_ROTATION_VECTOR -> rotationW.append(t, v[0], v[1], v[2], if (v.size > 3) v[3] else Float.NaN)
                else -> true
            }
            // Far longer than any session: something forgot to stop it. Stop listening rather than
            // grow until the watch runs out of memory (as a real one did).
            if (!stored) {
                stopListeningLocked()
                HLog.w(TAG, "IMU auto-stopped after $MAX_SAMPLES samples: the recording was never stopped")
            }
        }
    }

    private companion object {
        const val TAG = "Heartline/Sensor"

        /** About 3 minutes at the fastest rate (200 Hz): longer than any blood-pressure session. */
        const val MAX_SAMPLES = 40_000
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
