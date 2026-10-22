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

    // LearningRestore.from's read (F3155): a head not taken is all that is read; one taken, the whole file within the bound
    @Test
    fun aPickedFileIsReadWholeOnlyPastAHeadTaken() {
        val file = ByteArray(10) { it.toByte() }
        var read = 0
        val counted = object : InputStream() {
            override fun read(): Int = if (read < file.size) file[read++].toInt() else -1
        }
        assertEquals(null, counted.readPicked(head = 4, max = 20) { false })
        assertEquals(4, read)
        assertArrayEquals(file, file.inputStream().readPicked(head = 4, max = 10) { it.size == 4 })
        assertArrayEquals(file, file.inputStream().readPicked(head = 20, max = 20) { true })
        assertEquals(null, file.inputStream().readPicked(head = 4, max = 9) { true })
    }

    // F3199/F3239: a file accepted by its head but past the whole limit (head included) is none, as a non-backup
    @Test
    fun tooLargeIsNoBackup() {
        val head = 1 shl 20
        val max = 2 shl 20
        assertEquals(null, ByteArray(max + 1).inputStream().readPicked(head, max) { true })
        assertEquals(max, ByteArray(max).inputStream().readPicked(head, max) { true }?.size)
    }
}
