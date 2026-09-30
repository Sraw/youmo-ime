/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomPhraseManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun noFileIsNoPhrase() {
        assertEquals(emptyList<PinyinCustomPhrase>(), CustomPhraseManager.load(File(folder.root, "customphrase")))
    }

    @Test
    fun whatIsSavedLoadsBack() {
        val file = File(folder.root, "pinyin/customphrase")
        val items = listOf(
            PinyinCustomPhrase("sj", 1, "#\$year"),
            PinyinCustomPhrase("dz", -2, "line one\nline two"),
            PinyinCustomPhrase("sj", 2, "a b"),
        )
        CustomPhraseManager.save(items, file)
        assertEquals("dz,-2=\"line one\\nline two\"\nsj,1=#\$year\nsj,2=\"a b\"\n", file.readText())
        assertEquals(listOf(items[1], items[0], items[2]), CustomPhraseManager.load(file))
        assertEquals(listOf("pinyin"), folder.root.list()!!.toList())
        assertEquals(listOf("customphrase"), file.parentFile!!.list()!!.toList())
    }
}
