/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyListsWithTest {
    private val q = KeyId("full/q")
    private val one = KeyId("t9/1")
    private val two = KeyId("t9/2")
    private val nine = KeyboardVariant(KeyboardVariant.Form.Nine)
    private val pinyinOne = KeyDefaults.nine('1', nine)
    private val pinyinTwo = KeyDefaults.nine('2', nine)

    // compared by the first resolved, not as stored: a key's default stores `:2` or `!`, never `*`
    @Test
    fun aListTheSameAsTheDefaultIsNotKept() {
        val d = KeyDefaults.preset("q")
        val texts = pinyinTwo.items.map { it.text }
        val group = KeyId("zy9/1")
        val zhuyin = KeyDefaults.zhuyinGroup("ㄅㄉㄚ")!!
        assertRows(
            "q's default" to { assertNull(KeyLists.Empty.with(q, UserList(false, listOf("1", "Q")), d)[q]) },
            "q's default reordered: kept" to {
                assertEquals(UserList(false, listOf("Q", "1")), KeyLists.Empty.with(q, UserList(false, listOf("Q", "1")), d)[q])
            },
            "a first of its own is a setting, the list the same or not" to {
                assertEquals(First.None, KeyLists.Empty.with(q, UserList(false, listOf("1", "Q"), First.None), d)[q]?.first)
            },
            "a change taken back" to { assertNull(KeyLists.Empty.with(q, UserList(false, listOf("1")), d).with(q, null, d)[q]) },
            "九键 1's default, its first the list's" to {
                assertNull(KeyLists.Empty.with(one, UserList(false, pinyinOne.items.map { it.text }), pinyinOne)[one])
            },
            "九键 2's default stored by hand, its first `:2`" to {
                assertNull(KeyLists.Empty.with(two, UserList(false, texts, First.Literal("2")), pinyinTwo)[two])
            },
            "九键 2's list, its first item A another first: kept" to {
                assertEquals(First.Default, KeyLists.Empty.with(two, UserList(false, texts), pinyinTwo)[two]?.first)
            },
            "注音九键's group, its first `!`" to {
                assertNull(KeyLists.Empty.with(group, UserList(false, zhuyin.items.map { it.text }, First.None), zhuyin)[group])
            },
        )
    }

    @Test
    fun noItemsIsOffOnEveryKey() {
        assertEquals(UserList.Off, KeyLists.Empty.with(q, UserList(false, emptyList()), KeyDefaults.preset("q"))[q])
        // a row's key emptied: off too, its row gone with it
        assertEquals(UserList.Off, KeyLists.Empty.with(one, UserList(false, emptyList()), pinyinOne)[one])
        assertEquals(UserList.Off, KeyLists.Empty.with(two, UserList(false, emptyList(), First.None), pinyinTwo)[two])
        // off where there is none anyway: nothing kept
        val z = KeyId("zyfull/1")
        assertNull(KeyLists.Empty.with(z, UserList.Off, null)[z])
        assertEquals(UserList.Off, KeyLists.Empty.with(q, UserList.Off, KeyDefaults.preset("q"))[q])
    }

    @Test
    fun withoutTakesKeysBack() {
        val l = KeyLists.of(mapOf(q to UserList.Off, one to UserList.Off))
        assertEquals(setOf(one), l.without(listOf(q)).lists.keys)
    }
}
