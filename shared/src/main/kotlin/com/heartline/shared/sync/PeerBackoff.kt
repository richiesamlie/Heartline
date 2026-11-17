// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

/**
 * After the other device wasn't reachable, waits before looking it up again: [firstMs], doubling
 * up to [maxMs]. A watch away from its phone otherwise asked Play services (and logged "no peer")
 * on every background heart-rate delivery, every few minutes for hours. Anything heard from the
 * other device, or a lookup the user asked for, starts over ([reset]).
 */
class PeerBackoff(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val firstMs: Long = 30_000L,
    private val maxMs: Long = 5 * 60_000L,
    initialWaitMs: Long = 0L,
    initialNextTryMs: Long = 0L,
    private val onChange: (waitMs: Long, nextTryMs: Long) -> Unit = { _, _ -> }
) {
    @Volatile private var waitMs = initialWaitMs.coerceAtLeast(0L)

    @Volatile private var nextTryMs = initialNextTryMs.coerceAtLeast(0L)

    /** A routine lookup may run now. */
    fun shouldTry(): Boolean = clockMs() >= nextTryMs

    /** How long until the next routine lookup, ms (0 when it may run now). */
    fun waitLeftMs(): Long = (nextTryMs - clockMs()).coerceAtLeast(0)

    @Synchronized
    fun onLookup(found: Boolean) {
        if (found) {
            reset()
        } else {
            waitMs = if (waitMs == 0L) firstMs else (waitMs * 2).coerceAtMost(maxMs)
            nextTryMs = clockMs() + waitMs
            onChange(waitMs, nextTryMs)
        }
    }

    @Synchronized
    fun reset() {
        if (waitMs == 0L && nextTryMs == 0L) return
        waitMs = 0
        nextTryMs = 0
        onChange(0L, 0L)
    }
}
