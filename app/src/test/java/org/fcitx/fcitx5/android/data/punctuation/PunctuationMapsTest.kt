/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PunctuationMapsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val builtIn = ". 。\n, ，\n\" “ ”\n"
    private fun maps() = PunctuationMaps(tmp.root) { lang -> if (lang == "zh_CN") builtIn.byteInputStream() else null }

    @Test
    fun `no user file, the built-in table`() {
        assertEquals(
            listOf(PunctuationMapEntry(".", "。", ""), PunctuationMapEntry(",", "，", ""), PunctuationMapEntry("\"", "“", "”")),
            maps().load("zh_CN"),
        )
        assertEquals(emptyList<PunctuationMapEntry>(), maps().load("zh_TW"))
    }

    @Test
    fun `a user file replaces the built-in table whole`() {
        // punctuation.cpp:410-430: the user's file is loaded over the system one, which load() clears
        File(tmp.root, "punctuation").mkdirs()
        File(tmp.root, "punctuation/punc.mb.zh_CN").writeText(", 、\n")
        assertEquals(listOf(PunctuationMapEntry(",", "、", "")), maps().load("zh_CN"))
        assertEquals(3, maps().builtIn("zh_CN").size)
    }

    @Test
    fun `the editor saves the whole table, as fcitx writes it`() {
        maps().save("zh_CN", listOf(PunctuationMapEntry(",", "、", ""), PunctuationMapEntry("\"", "「", "」"), PunctuationMapEntry("", "x", "")))
        val file = File(tmp.root, "punctuation/punc.mb.zh_CN")
        assertEquals(", 、\n\" 「 」\n", file.readText())
        assertFalse(File(file.path + ".new").exists())
        assertEquals(2, maps().load("zh_CN").size)
    }

    @Test
    fun `a user file that cannot be read leaves the built-in table`() {
        // F3903: an unreadable file is not "no punctuation"
        val file = File(tmp.root, "punctuation/punc.mb.zh_CN").apply { parentFile!!.mkdirs(); writeText(", 、\n") }
        file.setReadable(false)
        assumeFalse("root reads it anyway", file.canRead())
        assertEquals(3, maps().load("zh_CN").size)
    }

    @Test
    fun `a save that fails throws, leaves no new file and keeps the built-in table`() {
        assertRows(
            listOf<Pair<String, (File) -> Unit>>(
                // F3903: punctuation/ is a file, so the .new cannot be opened
                "the .new cannot be opened (F3903)" to { root -> File(root, "punctuation").writeText("") },
                // F4163/F4282: the .new is written, the rename onto a non-empty directory fails; the .new
                // must not stay behind (the row above fails before any .new exists, so it cannot see this)
                "the rename onto a non-empty directory fails (F4163/F4282)" to { root ->
                    File(root, "punctuation/punc.mb.zh_CN").mkdirs()
                    File(root, "punctuation/punc.mb.zh_CN/x").writeText("")
                },
            ),
            { it.first },
        ) { (_, setUp) ->
            val root = TemporaryFolder().apply { create() }
            try {
                setUp(root.root)
                val maps = PunctuationMaps(root.root) { lang -> if (lang == "zh_CN") builtIn.byteInputStream() else null }
                assertThrows(IOException::class.java) { maps.save("zh_CN", listOf(PunctuationMapEntry(",", "、", ""))) }
                assertFalse(File(root.root, "punctuation/punc.mb.zh_CN.new").exists())
                assertEquals(3, maps.load("zh_CN").size)
            } finally {
                root.delete()
            }
        }
    }
}
