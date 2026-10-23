/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A dictionary the user imported: named by its file, turned off by renaming it. */
class TextDictionaryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun dictionary(name: String) = TextDictionary(folder.newFile(name))

    @Test
    fun theNameIsWhatTheExtensionLeaves() {
        assertEquals("words", dictionary("words.txt").name)
        assertEquals("my.words", dictionary("my.words.txt").name)
        assertEquals("a.txt.b", dictionary("a.txt.b.txt").name)
        assertEquals("off", dictionary("off.txt.disable").name)
        // kept gzip'd: `.txt.gz` stripped whole (F3607)
        assertEquals("words", dictionary("words.txt.gz").name)
        assertEquals("off", dictionary("off.txt.gz.disable").name)
        assertEquals("a.txt.b", dictionary("a.txt.b.txt.gz").name)
    }

    @Test
    fun namesOfEveryTypeMatchTheTextTheyImportAs() {
        assertEquals("my.words", PinyinDictionary.nameOf("my.words.scel"))
        assertEquals("my.words", PinyinDictionary.nameOf("my.words.dict"))
        assertEquals("my.words", PinyinDictionary.nameOf("my.words.txt.disable"))
    }

    // an older build's text and one kept gzip'd alike, each staying of its kind (F3607). Each row: the file, its name off
    @Test
    fun turningOffAndOnRenamesTheFile() {
        assertRows(listOf("words.txt" to "words.txt.disable", "words.txt.gz" to "words.txt.gz.disable"), { it.first }) { (on, off) ->
            folder.root.listFiles()!!.forEach { it.delete() }
            val words = dictionary(on)
            assertTrue(words.disable())
            assertFalse(words.isEnabled)
            assertEquals(File(folder.root, off), words.file)
            assertTrue(words.disable())
            assertTrue(words.enable())
            assertTrue(words.isEnabled)
            assertEquals(listOf(on), folder.root.list()!!.toList())
        }
    }

    @Test
    fun anotherFileWhereItWouldGoIsNotReplaced() {
        assertRows(listOf("words.txt" to "words.txt.disable", "words.txt.gz" to "words.txt.gz.disable"), { it.first }) { (on, off) ->
            folder.root.listFiles()!!.forEach { it.delete() }
            val words = dictionary(on)
            val other = File(folder.root, off).apply { writeText("other") }
            assertFalse(words.disable())
            assertTrue(words.isEnabled)
            assertEquals(File(folder.root, on), words.file)
            assertEquals("other", other.readText())
        }
    }
}
