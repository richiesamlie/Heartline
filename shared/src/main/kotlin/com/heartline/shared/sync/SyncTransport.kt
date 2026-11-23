// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import java.io.InputStream
import kotlinx.coroutines.flow.Flow

/** An inbound message or stream from the paired device. */
data class Envelope(val path: String, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Envelope && path == other.path && data.contentEquals(other.data))

    override fun hashCode(): Int = 31 * path.hashCode() + data.contentHashCode()
}

/**
 * Transport between watch and phone. Implemented with the Wearable Data Layer on devices and
 * `InMemoryTransport` (in test fixtures) in tests. [send] is for small messages; [sendLarge] for
 * waveforms (ChannelClient on device).
 */
interface SyncTransport {
    val incoming: Flow<Envelope>

    /** @return true if a peer node received it. */
    suspend fun send(path: String, data: ByteArray): Boolean

    /** Sends immediately without routine peer-backoff throttling (interactive remote sessions). */
    suspend fun sendImmediate(path: String, data: ByteArray): Boolean = send(path, data)

    suspend fun sendLarge(path: String, data: ByteArray): Boolean = send(path, data)

    /** Streams [input] (closed afterwards) without holding it in memory, where the transport can. */
    suspend fun sendStream(path: String, input: InputStream): Boolean = sendLarge(path, input.use { it.readBytes() })
}
