// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import kotlin.math.asin
import kotlin.math.sqrt

/** Motion sensor recordings of one session (see [BpSessionLog]); null when the sensor is missing. */
data class ImuStreams(val accel: SensorStream?, val gyro: SensorStream?, val rotation: SensorStream?) {
    fun all() = listOfNotNull(accel, gyro, rotation)

    /** Mean acceleration vector (gravity, i.e. the arm position) in the watch's frame. */
    fun meanGravity(fromNs: Long = Long.MIN_VALUE, toNs: Long = Long.MAX_VALUE): List<Double>? {
        val a = accel?.takeIf { it.columns.size >= 3 } ?: return null
        val cols = a.columns.size
        val idx = (0 until a.size).filter { i ->
            a.timestampsNs[i] in fromNs..toNs && (0 until 3).all { c -> a.values[i * cols + c].isFinite() }
        }
        if (idx.size < 20) return null
        return List(3) { c -> idx.sumOf { a.values[it * cols + c].toDouble() } / idx.size }
    }

    /** Standard deviation of the acceleration magnitude between two times, m/s² (movement), or null. */
    fun motionSd(fromNs: Long = Long.MIN_VALUE, toNs: Long = Long.MAX_VALUE): Double? {
        val a = accel?.takeIf { it.columns.size >= 3 } ?: return null
        val cols = a.columns.size
        val m = (0 until a.size).mapNotNull { i ->
            if (a.timestampsNs[i] !in fromNs..toNs) return@mapNotNull null
            val x = a.values[i * cols].toDouble()
            val y = a.values[i * cols + 1].toDouble()
            val z = a.values[i * cols + 2].toDouble()
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return@mapNotNull null
            sqrt(x * x + y * y + z * z)
        }
        if (m.size < 20) return null
        val mean = m.average()
        return sqrt(m.sumOf { (it - mean) * (it - mean) } / m.size)
    }

    /**
     * Longest contiguous same-sign stretch where |forearm pitch| exceeds [thresholdDeg] within
     * [fromNs]..[toNs] (the raised part of the maneuver), or null. Keeping runs same-sign avoids
     * merging an arm hang (< -45°) or pre-touch tilt with an overhead raise.
     */
    fun raisedWindow(thresholdDeg: Double = 45.0, fromNs: Long = Long.MIN_VALUE, toNs: Long = Long.MAX_VALUE): LongRange? {
        val a = accel?.takeIf { it.columns.size >= 3 } ?: return null
        val cols = a.columns.size
        var bestStart = 0L
        var bestEnd = 0L
        var bestCount = 0
        var runStart = 0L
        var runEnd = 0L
        var runCount = 0
        var runSign = 0
        for (i in 0 until a.size) {
            val ts = a.timestampsNs[i]
            if (ts !in fromNs..toNs) continue
            val g = List(3) { c -> a.values[i * cols + c].toDouble() }
            if (g.any { !it.isFinite() }) continue
            val p = pitchDeg(g) ?: continue
            val s = when {
                p > thresholdDeg -> 1
                p < -thresholdDeg -> -1
                else -> 0
            }
            if (s != 0 && (runCount == 0 || (s == runSign && ts - runEnd <= MAX_RAISED_GAP_NS))) {
                if (runCount == 0) runStart = ts
                runEnd = ts
                runCount++
                runSign = s
                if (runCount > bestCount) {
                    bestCount = runCount
                    bestStart = runStart
                    bestEnd = runEnd
                }
            } else if (runCount > 0 && (s == -runSign || ts - runEnd > MAX_RAISED_GAP_NS)) {
                runCount = if (s != 0) 1 else 0
                runSign = s
                runStart = ts
                runEnd = ts
            }
        }
        if (bestCount < 20) return null
        return bestStart..bestEnd
    }

    companion object {
        const val ACCEL = "accel"
        const val GYRO = "gyro"
        const val ROTATION = "rotation"
        val EMPTY = ImuStreams(null, null, null)
        private const val MAX_RAISED_GAP_NS = 500_000_000L

        /**
         * Elevation of the forearm above the horizontal, degrees, from a gravity vector:
         * sign × asin(g_x / |g|). The straps sit at 12 and 6 o'clock, so the forearm runs along the
         * watch's x axis (3 ↔ 9 o'clock); worn on the left wrist with the crown towards the hand,
         * +x points to the hand and [sign] +1 makes a raised hand positive. The arm-raise maneuver
         * measures the sign for other ways of wearing it ([HydrostaticCalibration]).
         */
        fun pitchDeg(gravity: List<Double>, sign: Double = 1.0): Double? {
            if (gravity.size != 3 || gravity.any { !it.isFinite() }) return null
            val n = sqrt(gravity.sumOf { it * it })
            if (!(n >= 5.0)) return null
            return sign * Math.toDegrees(asin((gravity[0] / n).coerceIn(-1.0, 1.0)))
        }
    }
}
