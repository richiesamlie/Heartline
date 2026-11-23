// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.bp

import com.heartline.logging.HLog
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.heartline.phone.ml.OnnxRuntimeLock
import com.heartline.phone.ml.createOrtSessionOptions
import com.heartline.phone.ml.extractModelAsset
import com.heartline.shared.bp.PapageiInput
import com.heartline.shared.bp.PpgEmbedder
import com.heartline.shared.bp.PpgFeatureVector
import java.nio.FloatBuffer

/**
 * PaPaGei-S (Nokia Bell Labs, BSD-3-Clause), a PPG foundation model pretrained on 57,000 hours
 * of PPG, run on the phone with ONNX Runtime (int8, exported by tools/ml/bp-ml). It turns a
 * recording into a 512-d embedding; [com.heartline.shared.bp.HybridBpModel] learns this
 * user's correction on top of it, and only if that beats the other embedders on the user's own
 * cuff checks.
 */
class PapageiEmbedder private constructor(private val session: OrtSession) :
    PpgEmbedder,
    AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()

    @Volatile private var closed = false

    override fun embed(ppg: FloatArray?, fs: Int, features: PpgFeatureVector): DoubleArray? {
        if (closed) return null
        val segments = ppg?.let { PapageiInput.segments(it, fs) }.orEmpty()
        if (segments.isEmpty()) return null
        val input = FloatBuffer.allocate(segments.size * PapageiInput.SEGMENT)
        segments.forEach { input.put(it) }
        input.rewind()
        return runCatching {
            synchronized(OnnxRuntimeLock) {
                if (closed) return@runCatching null
                OnnxTensor.createTensor(env, input, longArrayOf(segments.size.toLong(), 1, PapageiInput.SEGMENT.toLong())).use { tensor ->
                    session.run(mapOf(INPUT to tensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val out = result[0].value as Array<FloatArray>
                        DoubleArray(out[0].size) { j -> out.sumOf { it[j].toDouble() } / out.size }
                    }
                }
            }
        }.onFailure { HLog.w(TAG, "PaPaGei failed", it) }.getOrNull()
    }

    override fun close() {
        synchronized(OnnxRuntimeLock) {
            if (!closed) {
                closed = true
                runCatching { session.close() }
            }
        }
    }

    companion object {
        const val ASSET = "papagei_s_int8.onnx"
        private const val INPUT = "ppg"
        private const val TAG = "Heartline/BP"

        /** Null when the model isn't bundled or can't be loaded (the app then uses its own pulse-shape embedding). */
        fun fromAssets(context: Context): PapageiEmbedder? = runCatching {
            val file = extractModelAsset(context, ASSET)
            createOrtSessionOptions().use { opts ->
                PapageiEmbedder(OrtEnvironment.getEnvironment().createSession(file.path, opts))
            }
        }.onFailure { HLog.i(TAG, "PaPaGei not available: ${it.message}") }.getOrNull()
    }
}
