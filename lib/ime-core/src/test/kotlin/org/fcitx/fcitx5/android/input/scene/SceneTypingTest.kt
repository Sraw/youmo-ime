/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.editing.EditorTraits
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTypingTest {

    @Test
    fun eachInputMethodByItsKindATableAlsoByItsName() {
        assertEquals(listOf("pinyin"), SceneTyping.im(InputMethodCatalog.PINYIN))
        // a pinyin session's own keyboard (the 18 keys, 注音) is pinyin still; the nine keys' T9 too
        assertEquals(listOf("pinyin"), SceneTyping.im(InputMethodCatalog.PINYIN_KEYS18))
        assertEquals(listOf("pinyin"), SceneTyping.im(InputMethodCatalog.ZHUYIN))
        assertEquals(listOf("pinyin"), SceneTyping.im(InputMethodCatalog.T9))
        assertEquals(listOf("shuangpin"), SceneTyping.im(InputMethodCatalog.SHUANGPIN_NINE))
        assertEquals(listOf("table", "wubi"), SceneTyping.im(InputMethodCatalog.WUBI))
        assertEquals(listOf("table", "wubipinyin"), SceneTyping.im(InputMethodCatalog.WUBI_PINYIN))
        assertEquals(listOf("handwriting"), SceneTyping.im(InputMethodCatalog.HANDWRITING))
        assertEquals(listOf("english"), SceneTyping.im(InputMethodCatalog.ENGLISH))
        assertEquals(emptyList<String>(), SceneTyping.im("keyboard-de"))
        assertEquals(emptyList<String>(), SceneTyping.im(null))
        // every table has a name of its own
        val names = InputMethodCatalog.TABLES.keys.map { SceneTyping.im(it).last() }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.startsWith("engine") })
    }

    @Test
    fun aThemeKnowsEachWordOfAnInputMethod() {
        // what a theme's reader warns of as no input method (Selector.IMS): none of these, and each of them one
        val all = listOf(
            InputMethodCatalog.PINYIN, InputMethodCatalog.T9, InputMethodCatalog.ZHUYIN, InputMethodCatalog.SHUANGPIN,
            InputMethodCatalog.HANDWRITING, InputMethodCatalog.ENGLISH,
        ) + InputMethodCatalog.TABLES.keys
        assertEquals(Selector.IMS, all.flatMap { SceneTyping.im(it) }.toSet())
    }

    @Test
    fun theFieldAPasswordFirst() {
        assertEquals("password", SceneTyping.field(EditorTraits(isNumber = true), password = true))
        assertEquals("url", SceneTyping.field(EditorTraits(isUri = true), password = false))
        assertEquals("email", SceneTyping.field(EditorTraits(isEmail = true), password = false))
        assertEquals("number", SceneTyping.field(EditorTraits(isNumber = true), password = false))
        assertNull(SceneTyping.field(EditorTraits(), password = false))
        // each a field the theme's reader knows
        for (traits in listOf(EditorTraits(isUri = true), EditorTraits(isEmail = true), EditorTraits(isNumber = true))) {
            assertTrue(SceneTyping.field(traits, false) in Selector.FIELDS)
        }
        assertTrue("password" in Selector.FIELDS)
    }

    @Test
    fun theScenesOfBoth() {
        assertEquals(setOf("im=table", "im=cangjie", "field=password"), SceneTyping.scenes(InputMethodCatalog.CANGJIE, "password"))
        assertEquals(setOf("im=english"), SceneTyping.scenes(InputMethodCatalog.ENGLISH, null))
        assertEquals(emptySet<String>(), SceneTyping.scenes(null, null))
    }
}
