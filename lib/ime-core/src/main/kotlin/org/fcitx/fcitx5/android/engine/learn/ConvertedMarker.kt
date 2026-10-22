/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.store.replaceFile
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/**
 * `learn.converted`, beside the log ([LearnLog.MARKER]): that the older builds' files were
 * converted, how far each of their logs was read then ([seen]), and the user's own words the
 * older pinyin model had ([mine]). An older build run again (a downgrade, then this one again;
 * review-1a-app S4) appends to its logs and never writes this: the next open reads what was
 * appended past [seen] (review-1a-r2 M1), and the words its model has that [mine] has not.
 *
 * Written when converting and after each catch-up, never at close: what this build appends to
 * the logs it still writes (the tables', English's) is read again at the next open whether the
 * process was closed or killed, and taking it in changes nothing ([LegacyConversion.since]).
 *
 * Lines of text: `FXLR 2 converted`, one per log (`length modified crc name`), an empty line,
 * then the words, one a line. One that does not read so is none: what the old files have is
 * taken in where the log lacks it, nothing forgotten by it.
 */
class ConvertedMarker(val seen: Map<String, Seen>, val mine: Set<String>) {

    /** A log as read: its length, when it was written, and the CRC-32 of its bytes to that length. */
    data class Seen(val length: Int, val modified: Long, val crc: Long) {
        /** Whether [file] is still as seen, by its length and time written alone. */
        fun same(file: File) = file.length() == length.toLong() && file.lastModified() == modified

        /** What of [bytes] was appended since: null when they do not start with what was seen (rewritten, compacted). */
        fun appended(bytes: ByteArray): ByteArray? =
            if (bytes.size >= length && crc(bytes, length) == crc) bytes.copyOfRange(length, bytes.size) else null

        companion object {
            fun of(file: File, bytes: ByteArray) = Seen(bytes.size, file.lastModified(), crc(bytes, bytes.size))

            private fun crc(bytes: ByteArray, length: Int) = CRC32().apply { update(bytes, 0, length) }.value
        }
    }

    fun text(): String = buildString {
        append(TEXT)
        for ((name, s) in seen.entries.sortedBy { it.key }) append("${s.length} ${s.modified} ${s.crc} $name\n")
        append('\n')
        for (text in mine) append(text).append('\n')
    }

    companion object {
        private const val TEXT = "FXLR 2 converted\n"
        private const val FIELDS = 4

        /** Of nothing converted: what the old files have is taken in where the log lacks it. */
        val NONE = ConvertedMarker(emptyMap(), emptySet())

        /** The marker [file] holds; null if it does not read as one (an older format, damaged: review-1a-r2 S2). */
        @Throws(IOException::class)
        fun read(file: File): ConvertedMarker? {
            val text = file.readText()
            if (!text.startsWith(TEXT)) return null
            val lines = text.substring(TEXT.length).split('\n')
            val blank = lines.indexOf("")
            if (blank < 0) return null
            val seen = HashMap<String, Seen>()
            for (line in lines.subList(0, blank)) {
                val f = line.split(' ', limit = FIELDS)
                if (f.size < FIELDS) return null
                seen[f[3]] = Seen(f[0].toIntOrNull() ?: return null, f[1].toLongOrNull() ?: return null, f[2].toLongOrNull() ?: return null)
            }
            return ConvertedMarker(seen, lines.subList(blank + 1, lines.size).filterTo(LinkedHashSet()) { it.isNotEmpty() })
        }

        @Throws(IOException::class)
        fun write(file: File, marker: ConvertedMarker) = replaceFile(file, File(file.path + ".new")) { it.writeText(marker.text()) }
    }
}
