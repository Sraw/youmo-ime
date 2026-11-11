/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyListsFormatTest {
    private fun parse(vararg lines: String) = KeyLists.parse((listOf("@1") + lines).joinToString("\n"))!!

    @Test
    fun theThreeBodies() {
        val l = parse("full/q\tset\t*\t1\t!\tQ", "t9/1\tset\t:.\t…\t.", "fn/comma\toff", "full/w\tset\t!")
        assertEquals(UserList(false, listOf("1", "!", "Q"), First.Default), l[KeyId("full/q")])
        assertEquals(UserList(false, listOf("…", "."), First.Literal(".")), l[KeyId("t9/1")])
        assertEquals(UserList.Off, l[KeyId("fn/comma")])
        assertEquals(UserList(false, emptyList(), First.None), l[KeyId("full/w")])
    }

    @Test
    fun noHeaderOrALaterOneIsNotThisFormat() {
        assertNull(KeyLists.parse(null))
        assertNull(KeyLists.parse(""))
        assertNull(KeyLists.parse("q 1 Q"))
        assertNull(KeyLists.parse("@2\nfull/q\toff"))
        assertEquals(KeyLists.Empty, KeyLists.parse("@1"))
    }

    @Test
    fun aBadLineIsSkippedTheRestKept() {
        val l = parse("full/q", "full/w\tmaybe", "FULL/e\toff", "/x\toff", "full/\toff", "full/r\tset\t?\ta", "full/t\toff")
        assertEquals(setOf(KeyId("full/t")), l.lists.keys)
    }

    @Test
    fun itemsAreEscapedClippedAndCleaned() {
        val long = "一二三四五六七八九十一二三四五六七八"
        val l = parse("full/q\tset\t*\ta\\tb\t \t\t$long\ta\\tb\t\t.com")
        assertEquals(listOf("a\tb", long.take(16), ".com"), l[KeyId("full/q")]!!.symbols)
        val many = (1..40).joinToString("\t") { "s$it" }
        assertEquals(32, parse("full/q\tset\t*\t$many")[KeyId("full/q")]!!.symbols.size)
    }

    @Test
    fun aLaterLineWins() {
        assertEquals(UserList.Off, parse("full/q\tset\t*\t1", "full/q\toff")[KeyId("full/q")])
    }

    @Test
    fun writtenInOrderOfTheNamesAndReadBack() {
        val l = parse("t9/2\tset\t*\t@", "full/q\tset\t:Q\t1\tQ\tx\\\\y", "fn/comma\toff")
        val text = l.serialize()
        assertEquals("@1\nfn/comma\toff\nfull/q\tset\t:Q\t1\tQ\tx\\\\y\nt9/2\tset\t*\t@", text)
        assertEquals(l, KeyLists.parse(text))
        assertEquals("@1", KeyLists.Empty.serialize())
    }

    @Test
    fun aKeysOwnNameMayHoldASlash() {
        val l = parse("zyfull//\tset\t*\tㄥ")
        assertEquals("/", l.lists.keys.single().key)
        assertEquals(l, KeyLists.parse(l.serialize()))
    }
}
