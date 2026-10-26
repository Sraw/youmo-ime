/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.host.PunctuationTable.Entry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ca/punctuation/punctuation.cpp:107-173`.
 * contract: fcitx's punctuation data files, which mutation cannot see.
 */
class PunctuationTableTest {

    private fun resource(name: String) = javaClass.getResourceAsStream(
        if (name == BUILT_IN) "/pkgdata/punctuation/$name" else "/host/punctuation/$name",
    )!!.readBytes()

    @Test
    fun `reads the built-in table and a device's edited one, and writes them back as they were`() {
        for (name in listOf(BUILT_IN, "device-v0.3.2.zh_CN")) {
            val bytes = resource(name)
            val entries = PunctuationTable.parse(bytes)
            assertEquals(name, bytes.decodeToString().lines().count { it.isNotBlank() }, entries.size)
            assertArrayEquals(name, bytes, PunctuationTable.write(entries))
        }
        // the v0.3.2 fixture's edit (uat/fixtures/upgrade/v0.3.2/README.md step 6)
        assertEquals(Entry(",", "、"), PunctuationTable.parse(resource("device-v0.3.2.zh_CN")).first { it.key == "," })
        assertEquals(Entry("\"", "“", "”"), PunctuationTable.parse(resource(BUILT_IN)).first { it.key == "\"" })
    }

    @Test
    fun `a line is kept with two or three pieces and a key of one character`() {
        val text = "  .\t。  \n# ＃\nab x\n, 、 ， extra\nx\n\n\u000B; ；\n😀 smile\n"
        assertEquals(
            listOf(Entry(".", "。"), Entry("#", "＃"), Entry(";", "；"), Entry("😀", "smile")),
            PunctuationTable.parse(text.encodeToByteArray()),
        )
    }

    @Test
    fun `a key not UTF-8 is left out`() {
        val bytes = byteArrayOf(0xFF.toByte(), ' '.code.toByte(), 'x'.code.toByte())
        assertEquals(emptyList<Entry>(), PunctuationTable.parse(bytes))
    }

    @Test
    fun `the editor's table drops entries with no key, no result or a longer key`() {
        val entries = listOf(Entry(",", "，"), Entry("", "x"), Entry(".", ""), Entry("ab", "c"), Entry(",", "、"))
        assertEquals(listOf(Entry(",", "，"), Entry(",", "、")), PunctuationTable.accepted(entries))
    }

    private companion object {
        // the app's built-in table (app/src/main/host-assets)
        const val BUILT_IN = "punc.mb.zh_CN"
    }
}
