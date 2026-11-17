// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.datalayer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.wearable.Node
import com.heartline.shared.sync.PeerBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataLayerTransportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun fakeNode(id: String = "phone-1") = object : Node {
        override fun getId() = id
        override fun getDisplayName() = "Phone"
        override fun isNearby() = true
    }

    @Test
    fun cancellingLookupRethrowsAndDoesNotTriggerPeerBackoff() = runBlocking {
        var now = 1_000L
        val backoff = PeerBackoff(clockMs = { now }, firstMs = 30_000L, maxMs = 300_000L)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val transport = DataLayerTransport(context, "heartline_phone", backoff).apply {
            findOverride = {
                started.complete(Unit)
                gate.await()
                emptySet()
            }
        }

        var caughtCancellation = false
        val job = launch {
            try {
                transport.send("/hl/v1/record/meta", byteArrayOf(1))
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue(caughtCancellation)
        assertTrue("cancellation must not poison PeerBackoff", backoff.shouldTry())
        assertEquals(0L, backoff.waitLeftMs())
    }

    @Test
    fun cancellingSendMessageRethrows() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val transport = DataLayerTransport(context, "heartline_phone").apply {
            findOverride = { setOf(fakeNode()) }
            sendOverride = { _, _, _ ->
                started.complete(Unit)
                gate.await()
            }
        }

        var caughtCancellation = false
        val job = launch {
            try {
                transport.send("/hl/v1/measure/result", byteArrayOf(1))
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue(caughtCancellation)
    }

    @Test
    fun sendImmediateBypassesActiveBackoffAndResetsItWhenPeerIsReachable() = runBlocking {
        var now = 1_000L
        val backoff = PeerBackoff(clockMs = { now }, firstMs = 30_000L, maxMs = 300_000L)
        backoff.onLookup(found = false)
        assertFalse(backoff.shouldTry())

        val sentPaths = mutableListOf<String>()
        var lookups = 0
        val transport = DataLayerTransport(context, "heartline_phone", backoff).apply {
            findOverride = {
                lookups++
                setOf(fakeNode())
            }
            sendOverride = { _, path, _ -> sentPaths += path }
        }

        // Routine send is blocked by backoff.
        assertFalse(transport.send("/hl/v1/hr/batch", byteArrayOf(1)))
        assertEquals(0, lookups)

        // Immediate interactive send bypasses backoff and resets it once the peer is found.
        assertTrue(transport.sendImmediate("/hl/v1/measure/result", byteArrayOf(2)))
        assertEquals(1, lookups)
        assertTrue(backoff.shouldTry())
        assertTrue(transport.send("/hl/v1/record/meta", byteArrayOf(3)))
        assertEquals(listOf("/hl/v1/measure/result", "/hl/v1/record/meta"), sentPaths)
    }
}
