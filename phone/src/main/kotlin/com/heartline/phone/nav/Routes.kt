// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.nav

import com.heartline.shared.model.Metric
import com.heartline.shared.sync.SetupTarget

/** Navigation routes and intent extras shared across notifications, widgets, deep links, and UI. */
object PhoneNav {
    const val POSTURAL_ROUTE = "heart_rate/after_standing"
    const val UPDATES_ROUTE = "updates"
    const val UPDATES_INSTALL_ROUTE = "updates?install=true"
    const val EXTRA_OPEN_ALERTS = "open_alerts"
}

/** Deep-link routes (heartline://phone/<route>) and where they land in the phone app. */
object PhoneRoutes {
    val HOME = SetupTarget.HOME.phoneRoute
    val PROFILE = SetupTarget.PROFILE.phoneRoute
    val BP_CALIBRATION = SetupTarget.BP_CALIBRATION.phoneRoute
    val DEV_MODE_HELP = SetupTarget.DEV_MODE_HELP.phoneRoute
    const val SETTINGS = "settings"
}

/** Watch navigation routes the phone can open (heartline://watch/<route>). Must match the watch's nav graph. */
object WatchRoutes {
    const val ECG = "ecg"
    const val HEART_RATE = "heart_rate"
    const val BLOOD_PRESSURE = "blood_pressure"
    const val BP_CALIBRATION = "bp_calibration"
    const val SETUP = "setup"

    fun quick(metric: Metric) = "quick/${metric.name}"
}
