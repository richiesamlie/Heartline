// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ml

import ai.onnxruntime.OrtSession
import android.content.Context
import com.heartline.phone.BuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers

/** Shared lock serializing native ONNX Runtime session execution and teardown. */
internal object OnnxRuntimeLock

/** Single-concurrency dispatcher so background ML inference never saturates [Dispatchers.Default]. */
internal val OnnxInferenceDispatcher = Dispatchers.Default.limitedParallelism(1)

private val extractLock = Any()

internal fun createOrtSessionOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 2))
    setInterOpNumThreads(1)
}

/**
 * Extracts [assetPath] into `noBackupFilesDir` atomically when missing, size-mismatched, or from
 * another app version ([versionStamp]).
 */
internal fun extractModelAsset(
    context: Context,
    assetPath: String,
    versionStamp: String = "${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}",
): File = synchronized(extractLock) {
    val dir = context.noBackupFilesDir.apply { mkdirs() }
    val file = File(dir, assetPath.substringAfterLast('/'))
    val stampFile = File(dir, "${file.name}.stamp")
    val fdSize = runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrElse { -1L }
    val expectedSize = if (fdSize >= 0L) fdSize else file.takeIf { it.exists() }?.length() ?: -1L
    val expectedStamp = "$versionStamp:$expectedSize"
    val currentStamp = runCatching { stampFile.readText() }.getOrNull()
    val needsExtract = !file.exists() || (fdSize >= 0L && file.length() != fdSize) || currentStamp != expectedStamp
    if (needsExtract) {
        val tmp = File(dir, "${file.name}.${UUID.randomUUID()}.tmp")
        val stampTmp = File(dir, "${stampFile.name}.${UUID.randomUUID()}.tmp")
        try {
            context.assets.open(assetPath).use { input ->
                tmp.outputStream().buffered().use { input.copyTo(it) }
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val writtenStamp = "$versionStamp:${if (fdSize >= 0L) fdSize else file.length()}"
            stampTmp.writeText(writtenStamp)
            Files.move(stampTmp.toPath(), stampFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
            stampTmp.delete()
        }
    }
    file
}

/**
 * Lazily loads a native or heavyweight resource, retrying transient load failures after
 * [minRetryIntervalMs] instead of pinning null for the process lifetime, and closes cleanly.
 */
internal class RetryableHolder<T : Any>(
    private val minRetryIntervalMs: Long = 60_000L,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val load: () -> T?,
) : AutoCloseable {
    private var value: T? = null
    private var lastFailureMs: Long = Long.MIN_VALUE

    @Synchronized
    fun get(): T? {
        value?.let { return it }
        if (lastFailureMs != Long.MIN_VALUE && clockMs() - lastFailureMs < minRetryIntervalMs) return null
        val loaded = load()
        if (loaded != null) {
            value = loaded
            lastFailureMs = Long.MIN_VALUE
        } else {
            lastFailureMs = clockMs()
        }
        return loaded
    }

    @Synchronized
    override fun close() {
        val current = value
        value = null
        lastFailureMs = Long.MIN_VALUE
        if (current is AutoCloseable) runCatching { current.close() }
    }
}
