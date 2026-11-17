// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Two connected in-memory endpoints; set [connected] false to simulate the peer being away. */
class InMemoryTransport private constructor() : SyncTransport {
    private val inbox = MutableSharedFlow<Envelope>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
    private lateinit var peer: InMemoryTransport
    var connected = true

    override val incoming: Flow<Envelope> = inbox.asSharedFlow()

    override suspend fun send(path: String, data: ByteArray): Boolean {
        if (!connected || !peer.connected) return false
        peer.inbox.emit(Envelope(path, data))
        return true
    }

    companion object {
        fun pair(): Pair<InMemoryTransport, InMemoryTransport> {
            val a = InMemoryTransport()
            val b = InMemoryTransport()
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}
