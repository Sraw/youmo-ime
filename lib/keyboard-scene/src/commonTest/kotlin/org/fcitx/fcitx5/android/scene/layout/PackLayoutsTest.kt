/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.testData
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

// the boards a pack is drawn on, the one rule the app's import and the theme maker's page ask the blank-key check on:
// the pack's board only where the app would draw it, else the app's
class PackLayoutsTest {
    private val app = BlankKeys.Board(Fixtures.qwerty)
    private val bottom = testData("grouped-bottom.xml")

    // the app's fragments: grouped-bottom alone
    private fun appText(id: String) = bottom.takeIf { id == "grouped-bottom" }

    // qwerty with an include of [src] at its end
    private fun including(src: String) = Fixtures.qwertySource.replace("</keyboard>", "  <include src=\"$src\"/>\n</keyboard>")

    @Test
    fun aPacksBoardIsDrawnOnlyWhereTheAppWouldDrawIt() {
        // the pack's files, whether its board is the one asked (else the app's), why
        val rows = listOf(
            Triple(emptyMap<String, String>(), false, "no file of the board's id: the app's"),
            Triple(mapOf("qwerty" to "<keyboard"), false, "a file that does not read: the app's"),
            Triple(mapOf("qwerty" to Fixtures.qwertySource), true, "a file that reads and keeps what the app's must have"),
            Triple(mapOf("qwerty" to including("grouped-bottom")), true, "an include the pack has not, read from the app's"),
            Triple(mapOf("qwerty" to including("mine"), "mine" to bottom.replace("id=\"grouped-bottom\"", "id=\"mine\"")), true, "an include of the pack's own"),
            Triple(mapOf("qwerty" to including("nowhere")), false, "an include neither the pack nor the app has: the app's"),
            Triple(mapOf("qwerty" to including("mine"), "mine" to "<fragment"), false, "an include that does not read: the app's"),
            Triple(mapOf("qwerty" to Fixtures.qwertySource.replace("family=\"letters\"", "family=\"nine\"")), false, "a board the rule refuses (another family): the app's"),
            Triple(mapOf("qwerty" to Fixtures.qwertySource.replace(Regex("<key id=\"backspace\"[^>]*>")) { "" }), false, "a board without Backspace: the app's"),
        )
        for ((own, packs, why) in rows) {
            val drawn = PackLayouts.drawn(listOf(app), own, ::appText).single()
            if (packs) assertNotSame(app, drawn, why) else assertSame(app, drawn, why)
        }
    }

    @Test
    fun onlyTheFilesTheAppReadsAreThePacks() {
        val has = setOf("qwerty", "grouped-bottom")
        val big = "x".repeat(PackLayouts.MAX_BYTES.toInt() + 1)
        val files = mapOf(
            // an id the app has, its byte-order mark gone
            "qwerty" to "\uFEFF<keyboard/>",
            // a fragment of the app's id: read as the app's would be
            "grouped-bottom" to "<fragment/>",
            // a name the app has none of: the app never reads it
            "my-bottom" to "<fragment/>",
        )
        assertEquals(mapOf("qwerty" to "<keyboard/>", "grouped-bottom" to "<fragment/>"), PackLayouts.readable(files) { it in has })
        // past the limit, counted in UTF-8 bytes as the file's length is
        assertEquals(emptyMap<String, String>(), PackLayouts.readable(mapOf("qwerty" to big)) { it in has })
        val wide = "字".repeat((PackLayouts.MAX_BYTES / 3).toInt() + 1)
        assertEquals(emptyMap<String, String>(), PackLayouts.readable(mapOf("qwerty" to wide)) { it in has })
    }
}
