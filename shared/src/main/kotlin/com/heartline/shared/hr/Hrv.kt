// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class HrvMetrics(val rmssdMs: Double, val sdnnMs: Double, val pnn50: Double, val count: Int)

/** Time-domain HRV from inter-beat intervals, with artefact rejection. */
object Hrv {
    /**
     * Drops out-of-range IBIs and beats more than 20 % from the local median (5 beats each side).
     * The median, unlike "the previous accepted beat", can't be captured by one early artefact
     * that would then reject every real beat after it.
     */
    fun clean(ibiMs: List<Int>): List<Int> {
        val inRange = ibiMs.filter { it in 300..2000 }
        return inRange.filterIndexed { i, ibi ->
            val window = inRange.subList(maxOf(0, i - 5), minOf(inRange.size, i + 6)).sorted()
            val median = window[window.size / 2]
            abs(ibi - median) <= median * 0.2
        }
    }

    fun compute(ibiMs: List<Int>): HrvMetrics? {
        val nn = clean(ibiMs)
        if (nn.size < 10) return null
        val mean = nn.average()
        val sdnn = sqrt(nn.sumOf { (it - mean) * (it - mean) } / (nn.size - 1))
        val diffs = nn.zipWithNext { a, b -> (b - a).toDouble() }
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        val pnn50 = diffs.count { abs(it) > 50 } * 100.0 / diffs.size
        return HrvMetrics(rmssd, sdnn, pnn50, nn.size)
    }

    /**
     * Time-domain HRV from [HrSample]s, taking differences only between true consecutive trusted
     * beats ([HrSample.rawIbiMs] and [HrSample.rawIbiOk]) without bridging across flagged, off-body
     * or missing intervals.
     */
    fun fromSamples(samples: List<HrSample>, minPairs: Int = 9): HrvMetrics? {
        val runs = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        var lastBeatTs: Long? = null
        for (s in samples) {
            if (!s.onBody || !s.reliable || s.moving) {
                if (current.isNotEmpty()) {
                    runs += current
                    current = mutableListOf()
                }
                lastBeatTs = null
                continue
            }
            if (s.rawIbiMs.isEmpty()) continue
            if (lastBeatTs != null && s.tsMs > lastBeatTs && s.tsMs - lastBeatTs > s.rawIbiMs.sum() + 2_000) {
                if (current.isNotEmpty()) {
                    runs += current
                    current = mutableListOf()
                }
            }
            lastBeatTs = s.tsMs
            s.rawIbiMs.forEachIndexed { k, ibi ->
                val ok = s.rawIbiOk.getOrElse(k) { true } && ibi in 300..2000
                if (!ok) {
                    if (current.isNotEmpty()) {
                        runs += current
                        current = mutableListOf()
                    }
                } else {
                    current += ibi
                }
            }
        }
        if (current.isNotEmpty()) runs += current
        val allTrusted = runs.flatten()
        if (allTrusted.size < minPairs + 1) return null
        val diffs = mutableListOf<Double>()
        val kept = mutableListOf<Int>()
        for (run in runs) {
            var prev: Int? = null
            for (i in run.indices) {
                val ibi = run[i]
                val window = run.subList(maxOf(0, i - 5), minOf(run.size, i + 6)).sorted()
                val median = window[window.size / 2]
                if (abs(ibi - median) > median * 0.2) {
                    prev = null
                    continue
                }
                kept += ibi
                prev?.let { p -> if (abs(ibi - p) <= p * 0.2) diffs += (ibi - p).toDouble() }
                prev = ibi
            }
        }
        if (kept.size < 10 || diffs.size < minPairs) return null
        val mean = kept.average()
        val sdnn = sqrt(kept.sumOf { (it - mean) * (it - mean) } / (kept.size - 1))
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        val pnn50 = diffs.count { abs(it) > 50 } * 100.0 / diffs.size
        return HrvMetrics(rmssd, sdnn, pnn50, kept.size)
    }
}

/** Groups 1 Hz samples into per-minute summaries (reliable on-body samples only). */
object MinuteAggregator {
    private const val MINUTE = 60_000L

    /**
     * [activity]: what the wearer was doing in the minute starting at the given time, if known
     * (sleep or exercise from the watch). Otherwise, and when that says rest, a minute with
     * movement counts as active. [steps]: the steps the watch counted in that minute, if known.
     */
    fun aggregate(
        samples: List<HrSample>,
        activity: (minuteStartMs: Long) -> HrContext? = { null },
        steps: (minuteStartMs: Long) -> Int? = { null }
    ): List<HrMinute> = samples
        .filter { it.onBody && it.reliable && it.bpm > 0 }
        .groupBy { it.tsMs / MINUTE * MINUTE }
        .toSortedMap()
        .map { (start, group) ->
            val moving = group.any { it.moving }
            val context = when (val known = activity(start)) {
                null, HrContext.REST -> if (moving) HrContext.ACTIVE else HrContext.REST
                else -> known
            }
            HrMinute(
                minuteStartMs = start,
                avgBpm = group.map { it.bpm }.average().roundToInt(),
                minBpm = group.minOf { it.bpm },
                maxBpm = group.maxOf { it.bpm },
                rmssdMs = Hrv.fromSamples(group)?.rmssdMs,
                resting = context == HrContext.REST,
                activity = context,
                steps = steps(start),
                sampleCount = group.size
            )
        }

    /** Context priority when combining partial views of one minute: EXERCISE > SLEEP > ACTIVE > REST. */
    fun HrContext.precedence(): Int = when (this) {
        HrContext.REST -> 0
        HrContext.ACTIVE -> 1
        HrContext.SLEEP -> 2
        HrContext.EXERCISE -> 3
    }

    /** Combines two summaries of the same minute (weighted average by [HrMinute.sampleCount]). */
    fun merge(a: HrMinute, b: HrMinute): HrMinute {
        val na = a.sampleCount.coerceAtLeast(1)
        val nb = b.sampleCount.coerceAtLeast(1)
        val total = na + nb
        val context = if (b.activity.precedence() > a.activity.precedence()) b.activity else a.activity
        return b.copy(
            avgBpm = ((a.avgBpm.toLong() * na + b.avgBpm.toLong() * nb + total / 2) / total).toInt(),
            minBpm = minOf(a.minBpm, b.minBpm),
            maxBpm = maxOf(a.maxBpm, b.maxBpm),
            rmssdMs = b.rmssdMs ?: a.rmssdMs,
            resting = context == HrContext.REST,
            activity = context,
            steps = maxSteps(a.steps, b.steps),
            sampleCount = total
        )
    }

    /** Steps of one minute seen twice: the later count can only have grown. */
    fun maxSteps(a: Int?, b: Int?): Int? = listOfNotNull(a, b).maxOrNull()
}
