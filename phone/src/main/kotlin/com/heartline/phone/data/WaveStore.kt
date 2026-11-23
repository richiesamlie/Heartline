// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.WaveCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Waveforms are kept as files (DB holds only the path) to keep the database small. */
class WaveStore(private val root: File) {
    private fun resolveUnderWaves(relativePath: String): File? {
        val base = File(root, "waves").canonicalFile.toPath()
        val target = File(root, relativePath).canonicalFile
        return target.takeIf { it.toPath().startsWith(base) && it.toPath() != base }
    }

    fun write(id: String, kindOrdinal: Int, sampleRateHz: Int, samples: FloatArray): String {
        val safeId = Protocol.requireSafeId(id)
        val relative = "waves/$safeId.bin"
        val file = resolveUnderWaves(relative) ?: throw IllegalArgumentException("Invalid wave path: $relative")
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        try {
            tmp.writeBytes(WaveCodec.encode(kindOrdinal, sampleRateHz, samples))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
        return relative
    }

    fun read(relativePath: String): FloatArray? {
        val file = resolveUnderWaves(relativePath) ?: return null
        if (!file.exists()) return null
        return runCatching { WaveCodec.decode(file.readBytes()).samples }.getOrNull()
    }

    fun delete(relativePath: String) {
        resolveUnderWaves(relativePath)?.delete()
    }

    fun deleteAll() {
        File(root, "waves").deleteRecursively()
    }
}
