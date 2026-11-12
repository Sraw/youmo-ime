/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyListsWithTest {
    private val q = KeyId("full/q")
    private val one = KeyId("t9/1")
    private val pinyinOne = KeyDefaults.nine('1', KeyboardVariant(KeyboardVariant.Form.Nine))

    @Test
    fun aListTheSameAsTheDefaultIsNotKept() {
        val d = listOf(KeyDefaults.preset("q"))
        assertNull(KeyLists.Empty.with(q, UserList(false, listOf("1", "Q")), d)[q])
        assertEquals(UserList(false, listOf("Q", "1")), KeyLists.Empty.with(q, UserList(false, listOf("Q", "1")), d)[q])
        // a first of its own is a setting, the list the same or not
        assertEquals(First.None, KeyLists.Empty.with(q, UserList(false, listOf("1", "Q"), First.None), d)[q]?.first)
        assertNull(KeyLists.Empty.with(q, UserList(false, listOf("1")), d).with(q, null, d)[q])
    }

    @Test
    fun nineOneHasOneDefaultSoItsOwnIsNoSetting() {
        // English's 1 is pinyin's (用户 2026-11-11): set to it from either is no setting; anything else is one, shared (M3)
        val defaults = listOf(pinyinOne, pinyinOne)
        assertNull(KeyLists.Empty.with(one, UserList(false, pinyinOne.symbols.map { it.text }), defaults)[one])
        assertEquals(listOf(".", "@"), KeyLists.Empty.with(one, UserList(false, listOf(".", "@")), defaults)[one]?.symbols)
    }

    @Test
    fun noSymbolsAndNoRowIsOff() {
        val d = listOf(KeyDefaults.preset("q"))
        assertEquals(UserList.Off, KeyLists.Empty.with(q, UserList(false, emptyList()), d)[q])
        // a row stays: the symbols emptied only
        assertEquals(UserList(false, emptyList()), KeyLists.Empty.with(one, UserList(false, emptyList()), listOf(pinyinOne))[one])
        // off where there is none anyway: nothing kept
        val z = KeyId("zyfull/1")
        assertNull(KeyLists.Empty.with(z, UserList.Off, listOf(null))[z])
        assertEquals(UserList.Off, KeyLists.Empty.with(q, UserList.Off, d)[q])
    }

    @Test
    fun withoutTakesKeysBack() {
        val l = KeyLists.of(mapOf(q to UserList.Off, one to UserList.Off))
        assertEquals(setOf(one), l.without(listOf(q)).lists.keys)
    }
}
