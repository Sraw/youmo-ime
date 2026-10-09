/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table

import org.fcitx.fcitx5.android.data.table.dict.Dictionary
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportedTablesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val source by lazy { folder.newFolder("source") }
    private val dir by lazy { folder.newFolder("table") }

    private fun names() = dir.list()!!.sorted()

    @Test
    fun aTextTableIsKeptAsItIs() {
        val text = "键码=ab\n码长=4\n[数据]\na 工\n"
        val table = Dictionary.new(File(source, "my.txt").apply { writeText(text) })!!
        val installed = ImportedTables.install(table, File(dir, "my.txt"))
        assertEquals(File(dir, "my.txt"), installed.file)
        assertEquals(text, installed.file.readText())
        assertEquals(listOf("my.txt"), names())
    }

    @Test
    fun libimesTableIsKeptAsText() {
        val bytes = javaClass.getResourceAsStream("/libime/db.main.dict")!!.readBytes()
        val table = Dictionary.new(File(source, "db.main.dict").apply { writeBytes(bytes) })!!
        val text = ImportedTables.install(table, File(dir, "db.txt")).file.readText()
        assertTrue(text.take(100), text.startsWith("KeyCode="))
        assertTrue(text.contains("\n[Data]\n"))
        assertEquals(listOf("db.txt"), names())
    }

    @Test
    fun aTableTheEngineCannotReadLeavesWhatWasThere() {
        val kept = "键码=a\n[数据]\na 工\n"
        val dest = File(dir, "my.txt").apply { writeText(kept) }
        for (text in listOf("[数据]\nabc\n", "键码=a\n[数据]\n")) {
            val table = Dictionary.new(File(source, "bad.txt").apply { writeText(text) })!!
            assertThrows(SourceException::class.java) { ImportedTables.install(table, dest) }
        }
        assertEquals(kept, dest.readText())
        assertEquals(listOf("my.txt"), names())
    }
}
