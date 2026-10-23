/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Reason
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Refused
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** Run alone in a 48 MB JVM (`boundedHeapTest`): a file of one line far past the heap is read without holding it. */
class DictionaryFormatBoundedHeapTest {
    // 100 MB of one JSON line, made as it is read: a line kept whole ran out of the heap (review of
    // the dictionary import, fixed in 77ca9604)
    private fun hugeLine(): InputStream = object : InputStream() {
        private val unit = "\"k\":\"v\",".toByteArray()
        private var left = 100L shl 20
        private var at = 0

        override fun read(): Int {
            if (left-- <= 0) return -1
            return unit[at++ % unit.size].toInt()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            for (i in 0 until n) b[off + i] = unit[at++ % unit.size]
            left -= n
            return n
        }
    }

    @Test
    fun aHugeLineIsReadInASmallHeap() {
        val result = DictionaryFormat.analyse(::hugeLine)
        assertTrue("$result", result is Refused && result.reason in setOf(Reason.UNREADABLE, Reason.NO_WORDS))
    }
}
