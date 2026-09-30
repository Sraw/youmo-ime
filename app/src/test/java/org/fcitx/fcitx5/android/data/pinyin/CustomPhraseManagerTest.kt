/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
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

    @Test
    fun whatTheKeyboardChangedMeanwhileIsKeptWhenTheEditorSaves() {
        val file = File(folder.root, "customphrase")
        val a = PinyinCustomPhrase("yx", 1, "邮箱")
        val b = PinyinCustomPhrase("dh", 1, "电话")
        val pinned = PinyinCustomPhrase("nh", 1, "你好")
        CustomPhraseManager.save(listOf(a, b), file)
        val base = CustomPhraseManager.load(file)
        // the editor put b first: the file unchanged, its list is saved as it is
        var saved = CustomPhraseManager.saveOver(base, listOf(b, a), file)
        assertEquals(listOf(b, a), saved)
        // saved twice, 信箱 put before 邮箱 of the same key: what was saved is the file, nothing merged
        val a2 = PinyinCustomPhrase("yx", 1, "信箱")
        saved = CustomPhraseManager.saveOver(saved, listOf(b, a, a2), file)
        saved = CustomPhraseManager.saveOver(saved, listOf(b, a2, a), file)
        assertEquals(listOf(b, a2, a), saved)
        saved = CustomPhraseManager.saveOver(saved, listOf(b, a), file)
        // pinned from the keyboard while the editor deleted a and added c
        CustomPhraseManager.write(listOf(a, b, pinned).map { CustomPhrases.Phrase(it.key, it.order, it.value) }, file)
        val c = PinyinCustomPhrase("sj", 1, "手机")
        assertEquals(listOf(b, pinned, c), CustomPhraseManager.saveOver(saved, listOf(b, c), file))
    }
}
