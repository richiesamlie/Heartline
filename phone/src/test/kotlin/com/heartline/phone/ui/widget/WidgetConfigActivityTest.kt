// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WidgetConfigActivityTest {
    @Test
    fun rejectsUnownedOrMissingAppWidgetId() {
        val intent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 9999)
        val activity = Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).create().get()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
}
