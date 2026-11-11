/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyListsMigrationTest {
    private fun migrated(o: String? = null, named: String? = null, t9: String? = null) = KeyListsMigration.migrate(o, named, t9)

    private fun lists(o: String? = null, named: String? = null, t9: String? = null) = KeyLists.parse(migrated(o, named, t9).text)!!

    @Test
    fun nothingSetIsAnEmptyList() {
        assertEquals("@1", migrated().text)
        assertEquals("@1", migrated("", "", "").text)
    }

    @Test
    fun theFullKeyboardsLettersByTheirCharacter() {
        val l = lists("q ! 1 Q\nw")
        assertEquals(UserList(false, listOf("!", "1", "Q")), l[KeyId("full/q")])
        assertEquals(UserList.Off, l[KeyId("full/w")])
        // the same as built in: none kept
        assertEquals(emptyMap<KeyId, UserList>(), lists("q 1 Q").lists)
    }

    @Test
    fun aCapitalsListIsTheLowerCasesOrLost() {
        val same = migrated("q ! 1 Q\nQ ! 1 Q")
        assertEquals(emptyList<String>(), same.lost)
        val own = migrated("q ! 1\nQ ? 1")
        assertEquals(listOf("L2 Q ? 1"), own.lost)
        assertEquals(listOf("L2 W 2"), migrated("W 2").lost)
    }

    @Test
    fun commaPeriodAndZhuyinsMarksMoveToTheirNames() {
        val l = lists(", 。 ？\n. ，\n? ¿\n! ¡ ‼\n~ ～")
        assertEquals(listOf("。", "？"), l[KeyId("fn/comma")]?.symbols)
        assertEquals(listOf("，"), l[KeyId("fn/period")]?.symbols)
        assertEquals(listOf("¿"), l[KeyId("zy9/question")]?.symbols)
        assertEquals(listOf("¡", "‼"), l[KeyId("zy9/exclam")]?.symbols)
        assertEquals(listOf("～"), l[KeyId("zy9/tilde")]?.symbols)
    }

    @Test
    fun anyOtherCharactersListIsLost() {
        val m = migrated("$ € £\n5 ⑤")
        assertEquals("@1", m.text)
        assertEquals(listOf("L1 $ € £", "L1 5 ⑤"), m.lost)
    }

    @Test
    fun aNamedListIsLaterThanACharacters() {
        val l = lists(", 。", "comma ！ ？")
        assertEquals(listOf("！", "？"), l[KeyId("fn/comma")]?.symbols)
    }

    @Test
    fun aNamedKeysRowIsLeftToItsKeyboard() {
        // beta2's editor: the first in front, then the row, then the symbols
        val l = lists(named = "t9/2 2 A B C a b c # @\ng14/qw ~ Q W 1 q w")
        assertEquals(UserList(false, listOf("#", "@")), l[KeyId("t9/2")])
        assertEquals(UserList(false, listOf("~"), First.Literal("~")), l[KeyId("g14/qw")])
        assertEquals(emptyList<String>(), migrated(named = "t9/2 2 A B C a b c # @").lost)
    }

    @Test
    fun aRowChangedIsLost() {
        val m = migrated(named = "t9/3 3 F E D d e f +")
        assertEquals(listOf("L4 t9/3 3 F E D d e f +"), m.lost)
        assertEquals(listOf("+"), KeyLists.parse(m.text)!![KeyId("t9/3")]?.symbols)
    }

    @Test
    fun aNamedFunctionKeysListIsItsSymbols() {
        val l = lists(named = "space ␣ ·\nperiod")
        assertEquals(UserList(false, listOf("␣", "·")), l[KeyId("fn/space")])
        assertEquals(UserList.Off, l[KeyId("fn/period")])
    }

    @Test
    fun nineSymbolsUnlessTheKeyIsNamed() {
        val l = lists(named = "t9/5 5 J K L j k l %", t9 = "5 #\n6 & ~\n7 *")
        assertEquals(listOf("%"), l[KeyId("t9/5")]?.symbols)
        assertEquals(listOf("&", "~"), l[KeyId("t9/6")]?.symbols)
        // 7's as built in
        assertTrue(KeyId("t9/7") !in l.lists)
    }

    @Test
    fun twiceIsTheSame() {
        val a = migrated("q ! 1\n, 。", "t9/2 2 A B C a b c #", "6 &")
        assertEquals(a, migrated("q ! 1\n, 。", "t9/2 2 A B C a b c #", "6 &"))
        assertEquals(a.text, KeyLists.parse(a.text)!!.serialize())
    }
}
