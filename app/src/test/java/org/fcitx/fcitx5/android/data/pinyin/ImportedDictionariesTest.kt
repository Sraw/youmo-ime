/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImportedDictionariesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { folder.newFolder("dictionaries") }

    // what libime kept of a dictionary imported from text (see ime-core's resources/libime)
    private val extra = javaClass.getResourceAsStream("/libime/extra.dict")!!.readBytes()

    private fun names() = dir.list()!!.sorted()

    private fun aside() = File(dir, ImportedDictionaries.LIBIME).list()?.sorted().orEmpty()

    @Test
    fun aDictionaryIsKeptAsTheEngineReadsIt() {
        val dest = File(dir, "a.txt")
        val words = ImportedDictionaries.write(sequenceOf("﻿你好\tni'hao", "junk", "绿 lü -1"), dest)
        assertEquals(2, words)
        assertEquals("你好 ni'hao 0.0\n绿 lü -1.0\n", dest.readText())
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun aDictionaryWithNothingToReadIsNotWritten() {
        val dest = File(dir, "a.txt").apply { writeText("你 ni 0.0\n") }
        assertEquals(0, ImportedDictionaries.write(sequenceOf("junk", ""), dest))
        assertEquals("你 ni 0.0\n", dest.readText())
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun libimesDictionariesTurnIntoText() {
        File(dir, "on.dict").writeBytes(extra)
        File(dir, "off.dict.disable").writeBytes(extra)
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime", "off.txt.disable", "on.txt"), names())
        assertEquals("骁骎 xiao'qin 0.0\n琮璟 cong'jing 0.0\n", File(dir, "on.txt").readText())
        assertEquals(listOf("off.dict.disable", "on.dict"), aside())
        // and again, nothing left to do
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime", "off.txt.disable", "on.txt"), names())
        assertEquals(listOf("off.dict.disable", "on.dict"), aside())
    }

    @Test
    fun whatCannotBeTurnedIsPutAside() {
        File(dir, "corrupt.dict").writeBytes(extra.copyOf(30))
        File(dir, "taken.dict").writeBytes(extra)
        File(dir, "taken.txt.disable").writeText("你 ni 0.0\n")
        File(dir, "other.dat").writeText("?")
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime", "other.dat", "taken.txt.disable"), names())
        assertEquals(listOf("corrupt.dict", "taken.dict"), aside())
        assertEquals("你 ni 0.0\n", File(dir, "taken.txt.disable").readText())
    }

    @Test
    fun anEmptyDictionaryIsOnlyPutAside() {
        // libime's format, version 1: magic, version, a trie with no node
        File(dir, "empty.dict").writeBytes(byteArrayOf(0, 0x0f, 0xc6.toByte(), 0x13, 0, 0, 0, 1) + ByteArray(20))
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime"), names())
        assertFalse(File(dir, "empty.txt").exists())
    }

    @Test
    fun aDictionaryOfANameAlreadyAsideKeepsBoth() {
        File(dir, "a.dict").writeBytes(extra)
        ImportedDictionaries.migrate(dir)
        File(dir, "a.txt").delete()
        File(dir, "a.dict").writeBytes(extra.copyOf(30))
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf("a.dict", "a.dict.1"), aside())
        assertEquals(extra.size.toLong(), File(dir, ".libime/a.dict").length())
    }

    @Test
    fun aDictionaryThatCannotBeWrittenFails() {
        // a directory where the text should go: the rename fails
        val dest = File(dir, "a.txt").apply { mkdir() }
        assertThrows(IOException::class.java) { ImportedDictionaries.write(sequenceOf("你 ni 0.0"), dest) }
        assertEquals(listOf("a.txt"), names())
    }
}
