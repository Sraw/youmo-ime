/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.engine.pinyin.nineSp
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // beta2's list replaced the whole popup: its first the user's (the swipe and corner drew it), the row lost (L4)
    @Test
    fun beta2sSymbolsAloneKeepTheirFirst() {
        val m = migrated(named = "t9/2 # @")
        assertEquals(UserList(false, listOf("#", "@"), First.Literal("#")), KeyLists.parse(m.text)!![KeyId("t9/2")])
        assertEquals(listOf("L4 t9/2 # @"), m.lost)
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

    // 九键 1 (用户 2026-11-11): its default now the one list through the punctuation; 0.5's default (full width, as is)
    // is none of the user's, wherever it is stored
    @Test
    fun nineOneNoneButWhatTheUserSet() {
        val one = KeyId("t9/1")
        val old = "… ： ； ~ . - @ 、 “ ”"
        assertTrue(one !in lists(t9 = "").lists)
        assertTrue(one !in lists(t9 = "1 $old").lists)
        val set = lists(t9 = "1 . ,")
        assertEquals(UserList(false, listOf(".", ",")), set[one])
        // and typed as the punctuation maps it, as every key's symbols
        val popup = KeyResolver(KeyboardVariant(KeyboardVariant.Form.Nine), set, SwipeSlots.Default, emptyList())
            .resolve(one, KeyState()).popup as KeyPopup.Items
        assertEquals(listOf(".", ","), popup.items.map { it.text }.filter { it in setOf(".", ",") })
        assertTrue(popup.items.toString(), popup.items.filter { it.text in setOf(".", ",") }.all { it.commit == Commit.AsKey })
        // beta2's by its name as 0.5's default: none set, as t9_symbols' (its editor stored a key on OK, changed or not);
        // another list kept as the user's
        assertTrue(one !in lists(named = "t9/1 1 $old").lists)
        assertEquals(UserList(false, listOf(".", ",")), lists(named = "t9/1 1 . ,")[one])
    }

    // t9_symbols read as 0.5 read it (NineKeySymbols.parse, its cases ported here, 060-s6-review M4): a key's line its digit
    // then its symbols apart by spaces, each kept as 0.5 kept it
    private fun nine(t9: String, digit: Char) = lists(t9 = t9)[KeyId("t9/$digit")]?.symbols

    @Test
    fun nineDigitsLettersAndRepeatsAreNotKeptAndTenAtMost() {
        assertEquals(listOf("!", "ab", "。"), nine("8 ! 2 a A ab ! 。", '8'))
        assertEquals((1..10).map { "#$it" }, nine("2 " + (1..20).joinToString(" ") { "#$it" }, '2'))
        // as stored: a digit and a letter among the symbols are its row's, never the user's
        assertEquals(listOf("!"), nine("8 ! 8 u", '8'))
        // what is left its old default (8's ?): none set
        assertEquals(null, nine("8 ? 8 u", '8'))
    }

    @Test
    fun nineAKeyboardsCodeIsNoSymbol() {
        // 九键双拼's abc code is a key, never a symbol; the code of an earlier build is text now
        assertEquals(listOf("@"), nine("2 ${nineSp("2")} @", '2'))
        assertEquals(listOf("⒉"), nine("2 ⒉", '2'))
    }

    @Test
    fun nineOnlyWhatIsRefusedLeavesTheKeyNoSymbols() {
        // 0.5 read a line of nothing it keeps as the key set to none: its long press its row alone
        assertEquals(emptyList<String>(), nine("7 a 1", '7'))
        assertEquals(emptyList<String>(), nine("7", '7'))
    }

    @Test
    fun nineASpaceInsideASymbolIsNoSeparatorAsStored() {
        // the editor split what was typed at a full-width space too, so none was ever stored inside one: one there is refused
        assertEquals(listOf("·"), nine("5 【\u3000】 ·", '5'))
    }

    @Test
    fun nineReadBackAsWritten() {
        assertEquals((1..10).map { "#$it" }, nine("2 7 z " + (1..12).joinToString(" ") { "#$it" }, '2'))
        assertEquals(listOf("【", "】", "^_^"), nine("5 【 】 ^_^", '5'))
        // set back to its defaults: none set
        assertTrue(KeyId("t9/2") !in lists(t9 = "2 @ ! \"").lists)
    }

    @Test
    fun nineALineNotAKeysIsLeftOut() {
        val l = lists(t9 = "0 !\nx ?\n\n3 %\n12 #")
        assertEquals(setOf(KeyId("t9/3")), l.lists.keys)
        assertEquals(listOf("%"), l[KeyId("t9/3")]?.symbols)
    }

    @Test
    fun twiceIsTheSame() {
        val a = migrated("q ! 1\n, 。", "t9/2 2 A B C a b c #", "6 &")
        assertEquals(a, migrated("q ! 1\n, 。", "t9/2 2 A B C a b c #", "6 &"))
        assertEquals(a.text, KeyLists.parse(a.text)!!.serialize())
    }

    // 0.5.2's, beta1's and beta2's settings as stored (src/test/resources/keys-migration, its README) to the lists expected
    @Test
    fun theSamplesMigrate() {
        val versions = File("src/test/resources/keys-migration").listFiles(File::isDirectory)!!.sortedBy { it.name }
        assertEquals(listOf("dev-after-beta2", "v0.5.2", "v0.6.0-beta1", "v0.6.0-beta2"), versions.map { it.name })
        for (dir in versions) {
            fun read(name: String) = File(dir, name).takeIf { it.isFile }?.readText()
            val m = migrated(read("popup_overrides"), read("key_popups"), read("t9_symbols"))
            val lost = m.lost.joinToString("") { "$it\n" }
            if (System.getenv("KEYS_MIGRATION_RECORD") != null) {
                File(dir, "key_lists").writeText(m.text)
                File(dir, "lost").writeText(lost)
            }
            assertEquals(dir.name, read("key_lists"), m.text)
            assertEquals(dir.name, read("lost"), lost)
            assertEquals(dir.name, m, migrated(read("popup_overrides"), read("key_popups"), read("t9_symbols")))
        }
    }
}
