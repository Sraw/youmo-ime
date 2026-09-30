/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.nio.ByteBuffer

/**
 * Strings stored as one run of UTF-16 code units ([chars]) cut up by [offsets]: string i is
 * units offsets[i] until offsets[i + 1]. Read in place, so comparing against a key allocates
 * nothing.
 */
internal class StringTable(private val offsets: BitPacked, private val chars: ByteBuffer) {

    init {
        ensureFormat(chars.capacity() % 2 == 0) { "string table of ${chars.capacity()} bytes" }
        offsets.checkRuns("string offsets", 0, chars.capacity() / 2)
    }

    val size: Int get() = offsets.size - 1

    fun length(i: Int) = offsets[i + 1] - offsets[i]

    fun char(i: Int, at: Int) = chars.getChar((offsets[i] + at) * 2)

    operator fun get(i: Int): String {
        // each offset read unpacks bits: read the start once
        val start = offsets[i]
        return String(CharArray(offsets[i + 1] - start) { chars.getChar((start + it) * 2) })
    }

    fun compare(i: Int, key: String): Int {
        val n = length(i)
        for (k in 0 until minOf(n, key.length)) {
            val d = char(i, k).compareTo(key[k])
            if (d != 0) return d
        }
        return n.compareTo(key.length)
    }

    fun startsWith(i: Int, prefix: String): Boolean =
        length(i) >= prefix.length && prefix.indices.all { char(i, it) == prefix[it] }

    companion object {
        fun write(out: DataFile.Writer, offsetsId: Int, charsId: Int, strings: List<String>) {
            val offsets = IntArray(strings.size + 1)
            strings.forEachIndexed { i, s -> offsets[i + 1] = offsets[i] + s.length }
            val chars = LittleEndianOutput(offsets.last() * 2)
            strings.forEach { s -> s.forEach { chars.writeShort(it.code) } }
            out.add(offsetsId, BitPacked.encode(offsets))
            out.add(charsId, chars.toByteArray())
        }
    }
}
