// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.shared.model.Metric
import com.heartline.shared.profile.DayPart
import com.heartline.shared.model.Severity
import com.heartline.shared.sample.SyntheticEcg
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.wear.ui.launcher.LauncherEntry
import com.heartline.wear.ui.launcher.LauncherHeader

internal object WearSample {
    /** Fixed "now" for screenshots, so "2 h ago" labels never change. */
    const val NOW_MS = 1_790_000_000_000L
    private const val MIN = 60_000L

    val launcher = listOf(
        LauncherEntry(Metric.ECG, "68 bpm", NOW_MS - 2 * 60 * MIN, Severity.NORMAL, pinned = true),
        LauncherEntry(Metric.BLOOD_PRESSURE, "118/76", NOW_MS - 25 * MIN, Severity.NORMAL),
        LauncherEntry(Metric.HEART_RATE, "64 bpm", NOW_MS - 3 * MIN),
        LauncherEntry(Metric.SPO2, "97%", NOW_MS - 26 * 60 * MIN, Severity.NORMAL),
        LauncherEntry(Metric.SKIN_TEMPERATURE, "33.9 °C", NOW_MS - 3 * 24 * 60 * MIN),
        LauncherEntry(Metric.BODY_COMPOSITION, "21.4%", NOW_MS - 5 * 24 * 60 * MIN),
        LauncherEntry(Metric.STRESS, "38/100", NOW_MS - 4 * 60 * MIN, Severity.WARN),
    )
    val launcherHeader = LauncherHeader(DayPart.MORNING, "Sara", done = 1, total = 3, next = Metric.BLOOD_PRESSURE, streak = 6)
    val liveEcg: FloatArray by lazy { SyntheticEcg.generate(durationSec = 3.0, heartRateBpm = 72.0) }
    val livePpg: FloatArray by lazy { SyntheticPpg.generate(3.0, 68.0, 0.5) }
}
