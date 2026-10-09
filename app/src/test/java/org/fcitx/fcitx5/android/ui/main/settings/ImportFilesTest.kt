/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class ImportFilesTest {

    @Test
    fun aStreamIsReadNoFurtherThanAsked() {
        assertArrayEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3).inputStream().readAtMost(10))
        assertArrayEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3).inputStream().readAtMost(2))
        val endless = object : InputStream() {
            override fun read() = 'a'.code
        }
        assertEquals(100_000, endless.readAtMost(100_000).size)
    }

    @Test
    fun aFileLargerThanTheLimitIsAnErrorNotReadWhole() {
        assertArrayEquals(ByteArray(8), ByteArray(8).inputStream().readImported(max = 8))
        assertThrows(IOException::class.java) { ByteArray(9).inputStream().readImported(max = 8) }
    }
}
