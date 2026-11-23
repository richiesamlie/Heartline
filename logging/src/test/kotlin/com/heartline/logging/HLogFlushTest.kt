// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.logging

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HLogFlushTest {
    @After
    fun tearDown() {
        HLog.configure(false)
        HLog.clear()
        HLog.flush()
    }

    @Test
    fun `buffered info log flushes via on-demand timer without periodic polling`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        HLog.init(context, HLog.WATCH_BUDGET_BYTES)
        HLog.configure(true)
        HLog.clear()
        HLog.flush()

        val marker = "hlog-on-demand-flush-marker"
        HLog.i("Heartline/Test", marker)

        val current = File(File(context.filesDir, "logs"), "current.log")
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && (!current.exists() || !current.readText().contains(marker))) {
            Thread.sleep(100L)
        }

        assertTrue("Expected on-demand timer to flush info line to current.log", current.exists() && current.readText().contains(marker))
    }

    @Test
    fun `calling flush and sealedSegments from writer thread via onSegment does not deadlock`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        HLog.init(context, HLog.WATCH_BUDGET_BYTES)
        HLog.configure(true)
        HLog.i("Heartline/Test", "reentrant-flush-check")

        val done = java.util.concurrent.CountDownLatch(1)
        val segs = HLog.sealedSegments()
        // Dispatching onto HLog's own writer thread and calling flush/readRecent/sealedSegments must return immediately.
        val field = HLog::class.java.getDeclaredField("writer").apply { isAccessible = true }
        val exec = field.get(HLog) as java.util.concurrent.ScheduledExecutorService
        var recent = ""
        exec.submit {
            HLog.i("Heartline/Test", "inside-writer-thread")
            HLog.flush()
            recent = HLog.readRecent(1024)
            HLog.sealedSegments()
            done.countDown()
        }.get(2, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(segs.isNotEmpty() || recent.contains("reentrant-flush-check"))
    }
}
