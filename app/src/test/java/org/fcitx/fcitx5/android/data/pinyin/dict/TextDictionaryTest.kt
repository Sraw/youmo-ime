/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

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
    }

    @Test
    fun namesOfEveryTypeMatchTheTextTheyImportAs() {
        assertEquals("my.words", PinyinDictionary.nameOf("my.words.scel"))
        assertEquals("my.words", PinyinDictionary.nameOf("my.words.dict"))
        assertEquals("my.words", PinyinDictionary.nameOf("my.words.txt.disable"))
    }

    @Test
    fun turningOffAndOnRenamesTheFile() {
        val words = dictionary("words.txt")
        assertTrue(words.disable())
        assertFalse(words.isEnabled)
        assertEquals(File(folder.root, "words.txt.disable"), words.file)
        assertTrue(words.disable())
        assertTrue(words.enable())
        assertTrue(words.isEnabled)
        assertEquals(listOf("words.txt"), folder.root.list()!!.toList())
    }

    @Test
    fun anotherFileWhereItWouldGoIsNotReplaced() {
        val words = dictionary("words.txt")
        val other = File(folder.root, "words.txt.disable").apply { writeText("other") }
        assertFalse(words.disable())
        assertTrue(words.isEnabled)
        assertEquals(File(folder.root, "words.txt"), words.file)
        assertEquals("other", other.readText())
    }
}
