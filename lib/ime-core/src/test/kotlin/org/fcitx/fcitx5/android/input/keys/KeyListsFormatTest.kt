/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.scene.keys.DefaultList
import org.fcitx.fcitx5.android.scene.keys.Item
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

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
        assertNull(KeyLists.parse("@4\nfull/q\toff"))
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
        for (kept in listOf("@2", "\uFEFF@2\n", "@3\nfull/q\toff", "@4\nfull/q\toff")) assertFalse(kept, KeyLists.needsMigration(kept))
    }

    // 061-text-variables §4.1 (M1–M3): items marked, `@3`, each item `<marks>|<escaped>`

    private fun marked(vararg items: Pair<String, ItemMark?>, first: First = First.Default) =
        UserList(false, items.map { it.first }, first, items.mapNotNull { (t, m) -> m?.let { t to it } }.toMap())

    private fun roundTrip(list: UserList): UserList? {
        val text = KeyLists.of(mapOf(KeyId("full/q") to list)).serialize()
        return KeyLists.parse(text)!![KeyId("full/q")]
    }

    @Test
    fun markedItemsWriteThree() {
        val l = KeyLists.of(mapOf(KeyId("full/q") to marked("\$year年" to ItemMark.Variables, "," to ItemMark.AsIs, "。" to null, "a|b" to null, first = First.Literal("\$year年"))))
        assertEquals("@3\nfull/q\tset\t:\$year年\tv|\$year年\tr|,\t|。\t|a|b", l.serialize())
        assertEquals(l, KeyLists.parse(l.serialize()))
    }

    @Test
    fun itemsReadAtTheirFirstBar() {
        val l = KeyLists.parse("@3\nfull/q\tset\t*\t|a|b\t|\tv|\tv|v|x\t|a\\tb\t|x\\\\y\t|,\tnobar\tV|bad\t1|bad\tvz|kept\tzq|plain")!![KeyId("full/q")]!!
        // `|` alone, `v|` alone: no text, left out as any blank item
        assertEquals(listOf("a|b", "v|x", "a\tb", "x\\y", ",", "kept", "plain"), l.items)
        assertEquals(mapOf("v|x" to ItemMark.Variables, "kept" to ItemMark.Variables), l.marks)
    }

    @Test
    fun anyTextAnyMarkReadsBackAsWritten() {
        val random = Random(61)
        val alphabet = listOf("a", "|", "\\", "\t", "\n", "\r", "v", "r", ":", "*", "!", " x", "😀", "年", "\$", "{", "}", "@")
        repeat(500) {
            val n = 1 + random.nextInt(6)
            val items = (0 until n).map { i -> "$i" + (0 until random.nextInt(8)).joinToString("") { alphabet[random.nextInt(alphabet.size)] } }
            val marks = listOf(null, ItemMark.Variables, ItemMark.AsIs)
            val list = marked(*items.map { it to marks[random.nextInt(3)] }.toTypedArray(), first = First.Literal(items[random.nextInt(n)]))
            val clean = KeyLists.of(mapOf(KeyId("full/q") to list))[KeyId("full/q")]!!
            assertEquals("$items", clean, roundTrip(clean))
        }
    }

    @Test
    fun aLongFirstOfVariablesStaysTheFirst() {
        val forty = "\${year}年\${month}月\${day}日 星期\$weekday_cn |"
        assertEquals(40, forty.codePointCount(0, forty.length))
        val back = roundTrip(marked("a" to null, forty to ItemMark.Variables, first = First.Literal(forty)))!!
        assertEquals(First.Literal(forty), back.first)
        assertEquals(forty, back.firstOf(back.items))
        // 64 for one of variables, 16 for the others
        val long = "x".repeat(70)
        val cut = roundTrip(marked(long to ItemMark.Variables, "y".repeat(20) to null))!!
        assertEquals(listOf("x".repeat(64), "y".repeat(16)), cut.items)
        assertEquals(mapOf("x".repeat(64) to ItemMark.Variables), cut.marks)
    }

    @Test
    fun aMarkKeepsADefaultListAndTheLastOneGoneWritesTwo() {
        val default = DefaultList(emptyList(), listOf(Item(","), Item("。")), Item(","))
        val id = KeyId("fn/comma")
        val asIs = KeyLists.Empty.with(id, marked("," to ItemMark.AsIs, "。" to null), default)
        assertEquals(mapOf("," to ItemMark.AsIs), asIs[id]!!.marks)
        assertTrue(asIs.serialize().startsWith("@3\n"))
        // the same with no mark is the default's: nothing kept, `@2`
        val none = asIs.with(id, marked("," to null, "。" to null), default)
        assertNull(none[id])
        assertEquals("@2", none.serialize())
        // another key kept, marked no more: `@2`
        val other = KeyLists.Empty.with(KeyId("full/q"), marked("1" to null), null).with(id, marked("," to ItemMark.AsIs), null)
        assertTrue(other.serialize().startsWith("@3\n"))
        assertEquals("@2\nfull/q\tset\t*\t1", other.with(id, null, null).serialize())
    }

    @Test
    fun aMarkOfAnItemGoneIsGoneAndVariablesWriteNoAsIs() {
        val l = KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("a"), marks = mapOf("a" to ItemMark.Variables, "gone" to ItemMark.AsIs))))
        assertEquals(mapOf("a" to ItemMark.Variables), l[KeyId("full/q")]!!.marks)
        // both letters stored (by hand): of variables alone
        assertEquals(mapOf("x" to ItemMark.Variables), KeyLists.parse("@3\nfull/q\tset\t*\trv|x")!![KeyId("full/q")]!!.marks)
        assertEquals("@3\nfull/q\tset\t*\tv|x", KeyLists.parse("@3\nfull/q\tset\t*\trv|x")!!.serialize())
    }
}
