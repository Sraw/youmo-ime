/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.scene.SceneData
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every key's name a layout gives, and names no layout gives yet, stored in `key_lists` and read back as they were (§6.4). */
class KeyIdRoundTripTest {
    private val builtIn = listOf(
        "qwerty", "nine", "k14", "k18", "zhuyin", "zhuyin-nine", "handwriting", "number", "symbols",
        "emoji", "emoticon", "emoji-search", "expanded-side", "number-row",
    ).map { SceneData.read("$it.xml") }

    // the theme maker's example of a theme's own groups
    private val themes = File("../../web/theme-maker/examples/k17-motion/layouts").listFiles { f -> f.extension == "xml" }.orEmpty().map { it.readText() }

    private val fragments by lazy { mapOf("grouped-bottom" to (LayoutReader.fragment(SceneData.read("grouped-bottom.xml")) as Parsed.Ok).value) }

    private val names: Set<String> by lazy {
        (builtIn + themes).flatMap { text -> DefaultMarks.nodes((LayoutReader.layout(text) as Parsed.Ok).value, fragments).map { it.first } }.toSet()
    }

    @Test
    fun theLayoutsNamesAreOneNameAKey() {
        assertTrue(themes.isNotEmpty())
        for (n in listOf("full/q", "fn/comma", "fn/space", "t9/1", "g18/we", "zyfull//", "zy9/11")) assertTrue(n, n in names)
        // read from a layout, a function key's name is never bare
        for (n in names) assertEquals(n, KeyId.of(n).name, n)
    }

    @Test
    fun everyNameIsStoredAndReadBack() {
        val orphans = listOf("full/Q", "full/'", "full/\"", "full/@", "full/a\tb", "zy9/question", "g14/x;")
        val ids = (names + orphans).map(::KeyId)
        val lists = KeyLists.of(ids.associateWith { UserList(false, listOf("§")) })
        val back = KeyLists.parse(lists.serialize())
        assertEquals(lists, back)
        assertEquals(ids.toSet(), back!!.lists.keys)
    }
}
