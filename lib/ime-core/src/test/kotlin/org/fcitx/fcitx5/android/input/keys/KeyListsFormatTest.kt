/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// contract: key_lists' stored @2 format, which a backup, a later version and a downgrade read back
class KeyListsFormatTest {
    private fun parse(vararg lines: String) = KeyLists.parse((listOf("@2") + lines).joinToString("\n"))!!

    @Test
    fun theThreeBodies() {
        val l = parse("full/q\tset\t*\t1\t!\tQ", "t9/1\tset\t:.\t…\t.", "fn/comma\toff", "full/w\tset\t!")
        assertEquals(UserList(false, listOf("1", "!", "Q"), First.Default), l[KeyId("full/q")])
        assertEquals(UserList(false, listOf("…", "."), First.Literal(".")), l[KeyId("t9/1")])
        assertEquals(UserList.Off, l[KeyId("fn/comma")])
        // a set of no items is off, as KeyLists.with stores it (FB4 of R24)
        assertEquals(UserList.Off, l[KeyId("full/w")])
    }

    @Test
    fun noHeaderOrALaterOneIsNotThisFormat() {
        assertNull(KeyLists.parse(null))
        assertNull(KeyLists.parse(""))
        assertNull(KeyLists.parse("q 1 Q"))
        // @1's set held only the symbols above a row: upgraded, never read as @2's
        assertNull(KeyLists.parse("@1\nfull/q\toff"))
        assertNull(KeyLists.parse("@3\nfull/q\toff"))
        assertEquals(KeyLists.Empty, KeyLists.parse("@2"))
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
        assertEquals(listOf("a\tb", long.take(16), ".com"), l[KeyId("full/q")]!!.items)
        val many = (1..60).joinToString("\t") { "s$it" }
        assertEquals(16, parse("full/q\tset\t*\t$many")[KeyId("full/q")]!!.items.size)
    }

    // a row's letters and digit stored as any item, its digit first by name
    @Test
    fun aRowIsStoredAsAnyItems() {
        assertEquals("@2\nt9/2\tset\t:2\tA\tB\tC\t2\ta\tb\tc\t@", KeyLists.of(mapOf(KeyId("t9/2") to UserList(false, listOf("A", "B", "C", "2", "a", "b", "c", "@"), First.Literal("2")))).serialize())
    }

    @Test
    fun aLaterLineWins() {
        assertEquals(UserList.Off, parse("full/q\tset\t*\t1", "full/q\toff")[KeyId("full/q")])
    }

    @Test
    fun writtenInOrderOfTheNamesAndReadBack() {
        val l = parse("t9/2\tset\t*\t@", "full/q\tset\t:Q\t1\tQ\tx\\\\y", "fn/comma\toff")
        val text = l.serialize()
        assertEquals("@2\nfn/comma\toff\nfull/q\tset\t:Q\t1\tQ\tx\\\\y\nt9/2\tset\t*\t@", text)
        assertEquals(l, KeyLists.parse(text))
        assertEquals("@2", KeyLists.Empty.serialize())
    }

    @Test
    fun aKeysOwnNameMayHoldASlash() {
        val l = parse("zyfull//\tset\t*\tㄥ")
        assertEquals("/", l.lists.keys.single().key)
        assertEquals(l, KeyLists.parse(l.serialize()))
    }

    // what an editor or a transfer adds around the header is not another format; only none of any version's migrates
    @Test
    fun theHeaderAroundBlanksAndAByteOrderMarkAndWhatMigrates() {
        assertEquals(UserList.Off, KeyLists.parse("\uFEFF @2 \r\nfull/q\toff")!![KeyId("full/q")])
        assertEquals(UserList.Off, KeyLists.parse("\n@2\nfull/q\toff")!![KeyId("full/q")])
        // a bare or garbled `@` header is no version's: migrated, never kept read only as a later one's
        for (old in listOf(null, "", "  ", "q ! 1", "t9/2 # @", "@", "@x\nfull/q\toff", "@1", "\uFEFF@1\n")) assertTrue("$old", KeyLists.needsMigration(old))
        for (kept in listOf("@2", "\uFEFF@2\n", "@3\nfull/q\toff")) assertFalse(kept, KeyLists.needsMigration(kept))
    }
}
