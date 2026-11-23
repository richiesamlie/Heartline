// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.nav

import com.heartline.shared.measure.RemoteMeasureLink
import com.heartline.shared.model.Metric

/** Centralized Wear app route constants shared across navigation, tiles, complications, and deep links. */
object WatchRoutes {
    const val LAUNCHER = "launcher"
    const val ECG = "ecg"
    const val HISTORY = "history"
    const val HEART_RATE = "heart_rate"
    const val BLOOD_PRESSURE = "blood_pressure"
    const val BP_CALIBRATION = "bp_calibration"
    const val SETUP = "setup"
    const val BREATHE = "breathe"
    const val UPDATES = "updates"
    const val DEV_MODE = "dev_mode"
    const val DIAGNOSTICS = "diagnostics"
    const val SETTINGS = "settings"
    const val QUICK = "quick/{metric}"
    const val OPTIONS = "options/{metric}"

    /** A measurement the phone started: `remote/<METRIC>?session=<id>[&round=<n>]`. */
    const val REMOTE = "remote/{metric}?session={session}&round={round}"
    const val EXTRA_ROUTE = "route"

    fun options(metric: Metric) = "options/${metric.name}"

    fun quick(metric: Metric) = "quick/${metric.name}"

    fun measure(metric: Metric) = when (metric) {
        Metric.ECG -> ECG
        Metric.HEART_RATE -> HEART_RATE
        Metric.BLOOD_PRESSURE -> BLOOD_PRESSURE
        else -> quick(metric)
    }

    /** Screens the phone, notifications, tiles and complications may open. */
    fun isExternal(route: String) = route in setOf(ECG, HEART_RATE, BLOOD_PRESSURE, BP_CALIBRATION, HISTORY, UPDATES, BREATHE) ||
        (route.startsWith("quick/") && Metric.entries.any { route == quick(it) }) ||
        RemoteMeasureLink.parse(route) != null
}
