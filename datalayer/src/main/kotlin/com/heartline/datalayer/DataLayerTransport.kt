// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.datalayer

import com.heartline.logging.HLog
import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.heartline.shared.diag.IdleTimeoutInputStream
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.PeerBackoff
import com.heartline.shared.sync.PeerDirectory
import com.heartline.shared.sync.PeerProbe
import com.heartline.shared.sync.SyncTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * [SyncTransport] over the Wearable Data Layer. Small payloads use MessageClient; waveforms use
 * ChannelClient streams. Inbound data is pushed in by the app's WearableListenerService through
 * [deliver], so it arrives even when no UI is running.
 *
 * Everything is logged under [TAG] (successes too) so a device log shows exactly where a link breaks.
 *
 * @param peerCapability capability advertised by the other app (see Protocol.CAPABILITY_*).
 * @param backoff when set, routine sends stop looking the other app up for a while after it
 *   wasn't reachable (the watch: it sends on every background heart-rate delivery).
 */
class DataLayerTransport(context: Context, private val peerCapability: String, private val backoff: PeerBackoff? = null) :
    SyncTransport,
    PeerDirectory {
    private val appContext = context.applicationContext
    private val messages = Wearable.getMessageClient(appContext)
    private val channels = Wearable.getChannelClient(appContext)
    private val capabilities = Wearable.getCapabilityClient(appContext)
    private val nodes = Wearable.getNodeClient(appContext)
    private val inbox = MutableSharedFlow<Envelope>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    internal var findOverride: (suspend () -> Set<Node>)? = null
    internal var sendOverride: (suspend (nodeId: String, path: String, data: ByteArray) -> Unit)? = null

    override val incoming: Flow<Envelope> = inbox.asSharedFlow()

    suspend fun deliver(envelope: Envelope) {
        backoff?.reset()
        HLog.i(TAG, "received ${envelope.path} (${envelope.data.size} B)")
        inbox.tryEmit(envelope)
    }

    /** Nodes running the other Heartline app (looked up now, whatever the [backoff]). */
    suspend fun peers(): Set<Node> = lookup(routine = false).orEmpty()

    /** null: a [routine] lookup while the [backoff] waits (nothing asked, nothing logged). */
    private suspend fun lookup(routine: Boolean): Set<Node>? {
        if (routine && backoff?.shouldTry() == false) return null
        return find().also { backoff?.onLookup(it.isNotEmpty()) }
    }

    private suspend fun find(): Set<Node> = safeDataLayer(
        emptySet(),
        onError = { HLog.w(TAG, "capability lookup '$peerCapability' failed", it) },
    ) {
        findOverride?.invoke() ?: capabilities.getCapability(peerCapability, CapabilityClient.FILTER_REACHABLE).await().nodes
    }.also { found ->
        // Looked up for every message: logged only when the answer changes (it was most of the log).
        val text = found.joinToString { it.id }.ifEmpty { "none" }
        if (text != lastPeers) HLog.i(TAG, "capability '$peerCapability' -> $text")
        lastPeers = text
    }

    @Volatile private var lastPeers: String? = null

    /** Every device connected over Bluetooth/Wi-Fi, Heartline or not. */
    suspend fun connectedNodes(): List<Node> = safeDataLayer(
        emptyList(),
        onError = { HLog.w(TAG, "connectedNodes failed", it) },
    ) {
        nodes.connectedNodes.await()
    }.also { found -> HLog.i(TAG, "connected nodes -> ${found.joinToString { "${it.id}(nearby=${it.isNearby})" }.ifEmpty { "none" }}") }

    override suspend fun probe(): PeerProbe = when {
        peers().isNotEmpty() -> PeerProbe.REACHABLE
        connectedNodes().isNotEmpty() -> PeerProbe.APP_MISSING
        else -> PeerProbe.NO_DEVICE
    }

    /** First peer running Heartline, for remote activity launches. */
    suspend fun peerNodeId(): String? = peers().firstOrNull()?.id

    override suspend fun send(path: String, data: ByteArray): Boolean = sendMessage(path, data, routine = true)

    override suspend fun sendImmediate(path: String, data: ByteArray): Boolean = sendMessage(path, data, routine = false)

    private suspend fun sendMessage(path: String, data: ByteArray, routine: Boolean): Boolean {
        val targets = lookup(routine = routine) ?: return false
        if (targets.isEmpty()) {
            HLog.w(TAG, "send $path: no peer${nextLookup()}")
            return false
        }
        return targets.map { node ->
            safeDataLayer(
                false,
                onError = { HLog.w(TAG, "sendMessage $path to ${node.id} failed", it) },
            ) {
                sendOverride?.invoke(node.id, path, data) ?: messages.sendMessage(node.id, path, data).await()
                HLog.i(TAG, "sent $path (${data.size} B) to ${node.id}")
                true
            }
        }.any { it }
    }

    override suspend fun sendLarge(path: String, data: ByteArray): Boolean {
        val node = (lookup(routine = true) ?: return false).firstOrNull() ?: return false.also { HLog.w(TAG, "sendLarge $path: no peer${nextLookup()}") }
        return safeDataLayer(
            false,
            onError = { HLog.w(TAG, "channel $path failed", it) },
        ) {
            val channel = channels.openChannel(node.id, path).await()
            try {
                withContext(Dispatchers.IO) {
                    channels.getOutputStream(channel).await().use { it.write(data) }
                }
            } finally {
                channels.close(channel)
            }
            HLog.i(TAG, "streamed $path (${data.size} B)")
            true
        }
    }

    /** Streams [input] on a channel without holding it in memory (the watch's log segments). */
    override suspend fun sendStream(path: String, input: InputStream): Boolean = input.use { from ->
        val found = lookup(routine = true) ?: return@use false
        val node = found.firstOrNull() ?: return@use false.also { HLog.w(TAG, "sendStream $path: no peer${nextLookup()}") }
        var sent = 0L
        safeDataLayer(
            false,
            onError = { HLog.w(TAG, "stream $path failed after $sent B", it) },
        ) {
            val channel = channels.openChannel(node.id, path).await()
            try {
                withContext(Dispatchers.IO) {
                    channels.getOutputStream(channel).await().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = from.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            sent += n
                        }
                    }
                }
            } finally {
                channels.close(channel)
            }
            HLog.i(TAG, "streamed $path ($sent B)")
            true
        }
    }

    /**
     * Runs a Play Services Data Layer call, returning [fallback] on transport failure while
     * propagating [CancellationException] so coroutine cancellation never triggers [PeerBackoff].
     */
    private inline fun <T> safeDataLayer(fallback: T, onError: (Throwable) -> Unit, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onError(e)
        fallback
    }

    /**
     * Hands an inbound channel's stream to [read] (called from WearableListenerService
     * .onChannelOpened for large transfers that shouldn't be held in memory), then closes it.
     */
    suspend fun readChannelStream(channel: ChannelClient.Channel, read: suspend (InputStream) -> Unit) = withContext(Dispatchers.IO) {
        backoff?.reset()
        try {
            val input = channels.getInputStream(channel).await()
            HLog.i(TAG, "receiving stream ${channel.path}")
            input.use { read(it) }
        } finally {
            runCatching { channels.close(channel) }
        }
    }

    /** Reads a whole inbound channel (called from WearableListenerService.onChannelOpened). */
    suspend fun readChannel(channel: ChannelClient.Channel): ByteArray = withContext(Dispatchers.IO) {
        backoff?.reset()
        try {
            val raw = channels.getInputStream(channel).await()
            IdleTimeoutInputStream(raw, CHANNEL_IDLE_MS).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_CHANNEL_BYTES) throw IOException("Channel ${channel.path} exceeded $MAX_CHANNEL_BYTES B")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }.also {
                HLog.i(TAG, "received stream ${channel.path} (${it.size} B)")
            }
        } finally {
            runCatching { channels.close(channel) }
        }
    }

    private fun nextLookup(): String = backoff?.waitLeftMs()?.takeIf { it > 0 }?.let { ", next look in ${it / 1000} s" }.orEmpty()

    companion object {
        const val TAG = "Heartline/Sync"
        private const val CHANNEL_IDLE_MS = 60_000L
        private const val MAX_CHANNEL_BYTES = 32 * 1024 * 1024
    }
}
