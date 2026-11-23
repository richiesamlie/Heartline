// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.model.RecordSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class PhoneNotifierPrivacyTest {
    @Test
    fun alertsBpSafetyAndWeeklySummaryHideDetailsOnLockScreen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(manager)
        val notifier = PhoneNotifier(app)

        notifier.alert(HealthAlert("alert-1", AlertKind.HIGH_HEART_RATE, 1_000L, bpm = 148))
        val alertNotif = notifications.getNotification("alert-1".hashCode())
        assertEquals(Notification.VISIBILITY_PRIVATE, alertNotif.visibility)
        assertNotNull(alertNotif.publicVersion)
        assertFalse(shadowOf(alertNotif.publicVersion).contentTitle.toString().contains("148"))

        notifier.bpSafety(RecordSummary.BloodPressure(186, 106, pulse = 90, uncertainty = 14, confirmed = true))
        val bpNotif = notifications.getNotification(PhoneNotifier.BP_SAFETY_ID)
        assertEquals(Notification.VISIBILITY_PRIVATE, bpNotif.visibility)
        assertNotNull(bpNotif.publicVersion)
        assertFalse(shadowOf(bpNotif.publicVersion).contentTitle.toString().contains("186"))

        notifier.weeklySummary("Your week, Sara", "Average blood pressure 119/77")
        val weeklyNotif = notifications.getNotification(PhoneNotifier.WEEKLY_ID)
        assertEquals(Notification.VISIBILITY_PRIVATE, weeklyNotif.visibility)
        assertNotNull(weeklyNotif.publicVersion)
        val weeklyPublicTitle = shadowOf(weeklyNotif.publicVersion).contentTitle.toString()
        assertFalse(weeklyPublicTitle.contains("Sara"))
        assertFalse(weeklyPublicTitle.contains("119"))
    }
}
