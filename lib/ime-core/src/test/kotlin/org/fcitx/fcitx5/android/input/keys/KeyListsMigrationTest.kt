/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.assertRows
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

    private fun lists(o: String? = null, named: String? = null, t9: String? = null) = v1(migrated(o, named, t9).text)

    // `@1` read as it was: the same lines, a key's symbols above its row
    private fun v1(text: String) = KeyLists.parse(text.replaceFirst("@1", KeyLists.HEADER))!!

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
        assertEquals(listOf("。", "？"), l[KeyId("fn/comma")]?.items)
        assertEquals(listOf("，"), l[KeyId("fn/period")]?.items)
        assertEquals(listOf("¿"), l[KeyId("zy9/question")]?.items)
        assertEquals(listOf("¡", "‼"), l[KeyId("zy9/exclam")]?.items)
        assertEquals(listOf("～"), l[KeyId("zy9/tilde")]?.items)
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
        assertEquals(listOf("！", "？"), l[KeyId("fn/comma")]?.items)
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
        assertEquals(UserList(false, listOf("#", "@"), First.Literal("#")), v1(m.text)[KeyId("t9/2")])
        assertEquals(listOf("L4 t9/2 # @"), m.lost)
    }

    @Test
    fun aRowChangedIsLost() {
        val m = migrated(named = "t9/3 3 F E D d e f +")
        assertEquals(listOf("L4 t9/3 3 F E D d e f +"), m.lost)
        assertEquals(listOf("+"), v1(m.text)[KeyId("t9/3")]?.items)
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
        assertEquals(listOf("%"), l[KeyId("t9/5")]?.items)
        assertEquals(listOf("&", "~"), l[KeyId("t9/6")]?.items)
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
    private fun nine(t9: String, digit: Char) = lists(t9 = t9)[KeyId("t9/$digit")]?.items

    @Test
    fun nineSymbolsReadAs05ReadThem() = assertRows(
        "nineDigitsLettersAndRepeatsAreNotKeptAndTenAtMost" to {
            assertEquals(listOf("!", "ab", "。"), nine("8 ! 2 a A ab ! 。", '8'))
            assertEquals((1..10).map { "#$it" }, nine("2 " + (1..20).joinToString(" ") { "#$it" }, '2'))
            // as stored: a digit and a letter among the symbols are its row's, never the user's
            assertEquals(listOf("!"), nine("8 ! 8 u", '8'))
            // what is left its old default (8's ?): none set
            assertEquals(null, nine("8 ? 8 u", '8'))
        },
        "nineAKeyboardsCodeIsNoSymbol" to {
            // 九键双拼's abc code is a key, never a symbol; the code of an earlier build is text now
            assertEquals(listOf("@"), nine("2 ${nineSp("2")} @", '2'))
            assertEquals(listOf("⒉"), nine("2 ⒉", '2'))
        },
        "nineOnlyWhatIsRefusedLeavesTheKeyNoSymbols" to {
            // 0.5 read a line of nothing it keeps as the key set to none: its long press its row alone
            assertEquals(emptyList<String>(), nine("7 a 1", '7'))
            assertEquals(emptyList<String>(), nine("7", '7'))
        },
        "nineASpaceInsideASymbolIsNoSeparatorAsStored" to {
            // the editor split what was typed at a full-width space too, so none was ever stored inside one: one there is refused
            assertEquals(listOf("·"), nine("5 【\u3000】 ·", '5'))
        },
        "nineReadBackAsWritten" to {
            assertEquals((1..10).map { "#$it" }, nine("2 7 z " + (1..12).joinToString(" ") { "#$it" }, '2'))
            assertEquals(listOf("【", "】", "^_^"), nine("5 【 】 ^_^", '5'))
            // set back to its defaults: none set
            assertTrue(KeyId("t9/2") !in lists(t9 = "2 @ ! \"").lists)
        },
        "nineALineNotAKeysIsLeftOut" to {
            val l = lists(t9 = "0 !\nx ?\n\n3 %\n12 #")
            assertEquals(setOf(KeyId("t9/3")), l.lists.keys)
            assertEquals(listOf("%"), l[KeyId("t9/3")]?.items)
        },
    )

    @Test
    fun twiceIsTheSame() {
        val a = migrated("q ! 1\n, 。", "t9/2 2 A B C a b c #", "6 &")
        assertEquals(a, migrated("q ! 1\n, 。", "t9/2 2 A B C a b c #", "6 &"))
        assertEquals(a.text, "@1" + v1(a.text).serialize().removePrefix(KeyLists.HEADER))
    }

    // 0.5.2's, beta1's and beta2's settings as stored (src/test/resources/keys-migration, its README) to the lists expected
    // contract: those versions' stored settings, which an upgrade reads once and mutation cannot see
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

    private val standard = KeyboardVariant.STANDARD_NINE

    private fun upgraded(vararg lines: String) = KeyLists.parse(KeyListsMigration.upgrade((listOf("@1") + lines).joinToString("\n"), standard))!!

    // 060-row-unlock §5: a row's key's `@1` symbols under its row as the editor shows it; its first as `@1` resolved it
    @Test
    fun upgradeAsTheEditorShowsIt() = assertRows(
        "九键 2: its row, then its symbols; its first its digit, as its default's" to {
            assertEquals(UserList(false, listOf("A", "B", "C", "2", "a", "b", "c", "#", "@"), First.Literal("2")), upgraded("t9/2\tset\t*\t#\t@")[KeyId("t9/2")])
        },
        "九键 2 by the user's 按键字母" to {
            val own = KeyLists.parse(KeyListsMigration.upgrade("@1\nt9/2\tset\t*\t#", standard + ('2' to "abd")))!!
            assertEquals(listOf("A", "B", "D", "2", "a", "b", "d", "#"), own[KeyId("t9/2")]?.items)
        },
        "a first no longer in the list: its default's, as `*` was" to {
            assertEquals(First.Literal("2"), upgraded("t9/2\tset\t:x\t#")[KeyId("t9/2")]?.first)
        },
        "18 键 L as the editor shows it, `:` too" to {
            assertEquals(UserList(false, listOf("L", ":", "-", "l", ";", ")", "]"), First.Literal("-")), upgraded("g18/l\tset\t*\t)\t]")[KeyId("g18/l")])
        },
        "beta3's 18 键 L: its corner stored as first and among the symbols, the same" to {
            assertEquals(UserList(false, listOf("L", ":", "-", "l", ";", ")", "]"), First.Literal("-")), upgraded("g18/l\tset\t:-\t-\t)\t]")[KeyId("g18/l")])
        },
        "注音九键's group of no default first: none" to {
            assertEquals(First.None, upgraded("zy9/1\tset\t*\t@")[KeyId("zy9/1")]?.first)
        },
        "注音九键's function keys: no first" to {
            assertEquals(UserList(false, listOf("¿", "x"), First.None), upgraded("zy9/question\tset\t*\t¿\tx")[KeyId("zy9/question")])
            assertEquals(UserList(false, listOf("¡"), First.None), upgraded("zy9/exclam\tset\t*\t¡")[KeyId("zy9/exclam")])
        },
        "a key of no row as it was, off kept off" to {
            val l = upgraded("full/q\tset\t*\t!\t1", "full/w\tset\t:2\t!\t2", "fn/comma\toff", "full/e\tset\t!\t3")
            assertEquals(UserList(false, listOf("!", "1")), l[KeyId("full/q")])
            assertEquals(UserList(false, listOf("!", "2"), First.Literal("2")), l[KeyId("full/w")])
            assertEquals(UserList.Off, l[KeyId("fn/comma")])
            assertEquals(UserList(false, listOf("3"), First.None), l[KeyId("full/e")])
        },
        // `@1`'s items are only the symbols above a row: a set of none is the row alone (FB4 of R24, was
        // anEmptySetUpgradesToTheRowOrOff, with the next row)
        "a row's key with no symbols: its row alone" to {
            assertEquals(listOf("A", "B", "C", "2", "a", "b", "c"), upgraded("t9/2\tset\t*")[KeyId("t9/2")]?.items)
        },
        // nothing to show, stored off as the editor stores it (not an empty set, which only parse reads as off; FB4 of R24)
        "a key of no row with no symbols: off" to {
            assertEquals("@2\nfn/comma\toff", KeyListsMigration.upgrade("@1\nfn/comma\tset\t*", standard))
        },
        "over 16: the first 16 in list order, a row's first" to {
            val symbols = (1..32).map { "s$it" }
            assertEquals(listOf("P", "Q", "R", "S", "7", "p", "q", "r", "s") + symbols.take(7), upgraded("t9/7\tset\t*\t" + symbols.joinToString("\t"))[KeyId("t9/7")]?.items)
            assertEquals(symbols.take(16), upgraded("full/q\tset\t*\t" + symbols.joinToString("\t"))[KeyId("full/q")]?.items)
        },
    )

    @Test
    fun upgradeTwiceIsTheSameAndALaterVersionIsLeft() {
        val once = KeyListsMigration.upgrade("@1\nt9/2\tset\t*\t#\ng18/l\tset\t*\t)", standard)
        assertTrue(once.startsWith("@2"))
        assertEquals(once, KeyListsMigration.upgrade(once, standard))
        assertEquals("@3\nx", KeyListsMigration.upgrade("@3\nx", standard))
    }

    // 0.5's settings migrated and upgraded at once: its 九键 symbols under their row, its first the digit as 0.5 had it
    @Test
    fun aBackupOf05GoesStraightTo2() {
        val m = migrated("q ! 1", null, "5 #\n6 & ~")
        val l = KeyLists.parse(KeyListsMigration.upgrade(m.text, standard))!!
        assertEquals(UserList(false, listOf("J", "K", "L", "5", "j", "k", "l", "#"), First.Literal("5")), l[KeyId("t9/5")])
        assertEquals(UserList(false, listOf("!", "1")), l[KeyId("full/q")])
    }
}
