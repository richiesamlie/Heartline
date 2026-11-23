// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The diagnostic log, kept as gzip segments in [dir]: lines go to `current.log`; at
 * [segmentBytes] it's compressed into `seg-<number>.log.gz` (about a tenth of the size) and a new
 * `current.log` starts. The oldest segments are deleted when all of them together pass
 * [budgetBytes], or while the device has less than [minFreeBytes] free, so the log never fills
 * it. Segments are exported one at a time, straight from the files.
 *
 * Thread-safe: public operations synchronize on this instance, while [onSealed] is invoked
 * outside the lock so callbacks cannot deadlock.
 */
class SegmentedLog(
    private val dir: File,
    private val budgetBytes: Long,
    private val segmentBytes: Long = 1024L * 1024,
    private val minFreeBytes: Long = 500L * 1024 * 1024,
    private val freeBytes: () -> Long = { dir.usableSpace },
    /** Called with each new segment (the watch sends them to the phone). */
    private val onSealed: (File) -> Unit = {}
) {
    private val current = File(dir, CURRENT)

    /** Keeps what the earlier two-file log (current.log + previous.log) holds. Call once, off the main thread. */
    @Synchronized
    fun migrate() {
        val previous = File(dir, "previous.log")
        if (previous.exists()) {
            if (previous.length() > 0) compress(previous, nextSegment())
            previous.delete()
        }
    }

    fun append(text: String) {
        val sealed = synchronized(this) {
            dir.mkdirs()
            current.appendBytes(text.toByteArray(Charsets.UTF_8))
            if (current.length() >= segmentBytes) sealLocked() else null
        }
        sealed?.let(onSealed)
    }

    /** Closes `current.log` into a segment (before an export, so the segments hold everything). */
    fun seal() {
        val sealed = synchronized(this) { sealLocked() }
        sealed?.let(onSealed)
    }

    private fun sealLocked(): File? {
        if (!current.exists() || current.length() == 0L) return null
        val segment = nextSegment()
        compress(current, segment)
        current.delete()
        trim()
        return segment.takeIf { it.exists() }
    }

    /** The segments, oldest first. */
    @Synchronized
    fun segments(): List<File> = dir.listFiles { f -> SEGMENT.matches(f.name) }.orEmpty().sortedBy { number(it) }

    /** Everything kept, oldest first, as text: for tests and small logs only. */
    @Synchronized
    fun readAll(): String = buildString {
        segments().forEach { seg -> open(seg).use { append(it.readBytes().toString(Charsets.UTF_8)) } }
        if (current.exists()) append(current.readText(Charsets.UTF_8))
    }

    /** The last [maxBytes] of text (for a phone app from before segmented export). */
    @Synchronized
    fun readRecent(maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val parts = ArrayDeque<ByteArray>()
        var size = 0
        if (current.exists()) {
            current.readBytes().also {
                parts.addFirst(it)
                size += it.size
            }
        }
        for (seg in segments().asReversed()) {
            if (size >= maxBytes) break
            val bytes = open(seg).use { it.readBytes() }
            parts.addFirst(bytes)
            size += bytes.size
        }
        val all = ByteArray(size)
        var offset = 0
        for (part in parts) {
            part.copyInto(all, destinationOffset = offset)
            offset += part.size
        }
        return all.copyOfRange(maxOf(0, all.size - maxBytes), all.size).toString(Charsets.UTF_8)
    }

    @Synchronized
    fun sizeBytes(): Long = segments().sumOf { it.length() } + (if (current.exists()) current.length() else 0)

    @Synchronized
    fun clear() {
        segments().forEach { it.delete() }
        current.delete()
    }

    /**
     * The next segment number, never used before: segments that were moved to the phone and
     * deleted here must not be overwritten there, so the last number is kept in `seq`.
     */
    private fun nextSegment(): File {
        val seq = File(dir, SEQ)
        val last = maxOf(segments().lastOrNull()?.let(::number) ?: 0, seq.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0)
        dir.mkdirs()
        val tmp = File(dir, "$SEQ.tmp")
        try {
            tmp.writeText((last + 1).toString())
            Files.move(tmp.toPath(), seq.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
        return File(dir, String.format(Locale.ROOT, "seg-%08d.log.gz", last + 1))
    }

    private fun compress(from: File, to: File) {
        dir.mkdirs()
        val tmp = File(dir, to.name + ".tmp")
        try {
            GZIPOutputStream(tmp.outputStream().buffered()).use { out -> from.inputStream().use { it.copyTo(out) } }
            Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    private fun trim() {
        val kept = segments().toMutableList()
        var total = kept.sumOf { it.length() }
        while (kept.size > 1 && (total > budgetBytes || freeBytes() < minFreeBytes)) {
            val oldest = kept.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    companion object {
        const val CURRENT = "current.log"
        private const val SEQ = "seq"
        private val SEGMENT = Regex("seg-(\\d+)\\.log\\.gz")

        private fun number(f: File) = SEGMENT.matchEntire(f.name)?.groupValues?.get(1)?.toLong() ?: 0

        /** Whether [name] is a segment file name (the watch sends only those). */
        fun isSegment(name: String) = SEGMENT.matches(name)

        /** A segment's number (its order in the log), 0 for other names. */
        fun number(name: String): Long = SEGMENT.matchEntire(name)?.groupValues?.get(1)?.toLong() ?: 0

        fun open(segment: File): InputStream = GZIPInputStream(segment.inputStream().buffered())
    }
}
