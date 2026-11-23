// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import com.heartline.shared.sync.PeerBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerBackoffTest {
    @Test
    fun waitsLongerAfterEachMissUpToTheLimitAndStartsOverWhenFound() {
        var now = 0L
        val b = PeerBackoff(clockMs = { now }, firstMs = 30_000, maxMs = 300_000)
        assertTrue(b.shouldTry())
        val waits = (1..6).map {
            b.onLookup(found = false)
            b.waitLeftMs().also { now += it }
        }
        // 30 s, 60 s, 2 min, 4 min, then 5 min at most: about 15 lookups an hour at worst instead of one per heart-rate delivery.
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), waits)
        b.onLookup(found = false)
        now += 1_000
        assertFalse(b.shouldTry())
        b.onLookup(found = true)
        assertTrue(b.shouldTry())
        b.onLookup(found = false)
        assertEquals(30_000L, b.waitLeftMs())
        // Heard from the phone: ask again at once.
        b.reset()
        assertTrue(b.shouldTry())
    }

    @Test
    fun persistsAndRestoresBackoffStateAcrossRestarts() {
        var now = 1_000L
        var savedWait = 0L
        var savedNext = 0L
        var writes = 0
        val first = PeerBackoff(
            clockMs = { now },
            firstMs = 30_000,
            maxMs = 300_000,
            onChange = { wait, next ->
                savedWait = wait
                savedNext = next
                writes += 1
            }
        )
        first.onLookup(found = false)
        assertEquals(30_000L, savedWait)
        assertEquals(31_000L, savedNext)
        assertEquals(1, writes)

        // Simulates process restart: restores prior wait/nextTryMs instead of resetting to 0.
        now = 10_000L
        val restarted = PeerBackoff(
            clockMs = { now },
            firstMs = 30_000,
            maxMs = 300_000,
            initialWaitMs = savedWait,
            initialNextTryMs = savedNext,
            onChange = { wait, next ->
                savedWait = wait
                savedNext = next
                writes += 1
            }
        )
        assertFalse(restarted.shouldTry())
        assertEquals(21_000L, restarted.waitLeftMs())

        now = 31_000L
        assertTrue(restarted.shouldTry())
        restarted.onLookup(found = false)
        assertEquals(60_000L, savedWait)
        assertEquals(91_000L, savedNext)

        restarted.reset()
        assertEquals(0L, savedWait)
        assertEquals(0L, savedNext)
        val beforeNoOpReset = writes
        restarted.reset()
        assertEquals(beforeNoOpReset, writes)
    }

    @Test
    fun backwardClockJumpResetsBackoffAndInitialWaitIsClamped() {
        var now = 1_000_000L
        var savedWait = -1L
        val backoff = PeerBackoff(
            clockMs = { now },
            firstMs = 30_000L,
            maxMs = 300_000L,
            initialWaitMs = 999_999L,
            initialNextTryMs = 1_100_000L,
            onChange = { wait, _ -> savedWait = wait }
        )
        // Wall clock jumped backwards by more than maxMs: resets instead of wedging lookup.
        now = 100_000L
        assertTrue(backoff.shouldTry())
        assertEquals(0L, backoff.waitLeftMs())
        assertEquals(0L, savedWait)

        // Clamp check without clock jump: initialWaitMs above maxMs clamps to maxMs on next miss.
        val clamped = PeerBackoff(
            clockMs = { now },
            firstMs = 30_000L,
            maxMs = 300_000L,
            initialWaitMs = 999_999L,
            initialNextTryMs = now,
            onChange = { wait, _ -> savedWait = wait }
        )
        clamped.onLookup(found = false)
        assertEquals(300_000L, savedWait)
    }
}
