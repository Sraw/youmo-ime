/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.keyboard.NumberPad
import org.fcitx.fcitx5.android.input.theme.ThemeLookTest.Companion.slice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyNamesTest {

    @Test
    fun namesReadAndWrite() {
        assertEquals(KeyName("qwerty", "q"), KeyNames.parse("qwerty/q"))
        assertEquals("qwerty/q", KeyNames.parse("qwerty/q").toString())
        assertEquals(KeyName("*", "backspace"), KeyNames.parse("*/backspace"))
        assertEquals(KeyName("zhuyin-nine", "r4c2"), KeyNames.parse("zhuyin-nine/r4c2"))
        // 大千's ㄥ is on /: the key is what is after the first slash
        assertEquals(KeyName("zhuyin", "/"), KeyNames.parse("zhuyin//"))
        // a newer app's board reads, unknown
        assertFalse(KeyNames.isKnown(KeyNames.parse("k20/r1c1")))
        for (bad in listOf("qwerty/", "/q", "q", "Qwerty/q", "qw erty/q", "qwerty/a b", "-x/q", "*x/q")) {
            assertThrows(bad, IllegalArgumentException::class.java) { KeyNames.parse(bad) }
        }
    }

    @Test
    fun eachBoardsNamesAreTheDesignsAndApart() {
        assertEquals(setOf("qwerty", "zhuyin", "nine", "zhuyin-nine", "k14", "k18", "number", "symbols", "emoji", "handwriting"), KeyNames.BOARDS)
        assertEquals(26, KeyNames.POSITIONS.getValue("qwerty").size)
        assertEquals(41, KeyNames.POSITIONS.getValue("zhuyin").size)
        assertEquals(14, KeyNames.POSITIONS.getValue("k14").size)
        assertEquals(18, KeyNames.POSITIONS.getValue("k18").size)
        assertTrue("r4c2" in KeyNames.POSITIONS.getValue("zhuyin-nine"))
        for ((board, keys) in KeyNames.POSITIONS) {
            assertEquals(board, keys.size, keys.toSet().size)
            // a key has one name: a place is never a role
            assertEquals(board, emptySet<String>(), keys.toSet() intersect KeyNames.ROLES.toSet())
            assertTrue(KeyNames.on(board).containsAll(KeyNames.ROLES))
        }
        assertEquals(KeyNames.ROLES.size, KeyNames.ROLES.toSet().size)
    }

    @Test
    fun everySignOfEveryPadIsNamed() {
        val signs = NumberPad.entries.flatMap { it.left + it.corner + it.bottom }.filter { it.isNotEmpty() }
        assertTrue(KeyNames.POSITIONS.getValue("number").containsAll(signs))
    }

    @Test
    fun knownNames() {
        assertTrue(KeyNames.isKnown(KeyName("qwerty", "q")))
        assertTrue(KeyNames.isKnown(KeyName("nine", "backspace")))
        assertTrue(KeyNames.isKnown(KeyName("*", "backspace")))
        // every board's is a function key's alone
        assertFalse(KeyNames.isKnown(KeyName("*", "q")))
        assertFalse(KeyNames.isKnown(KeyName("qwerty", "1")))
        assertFalse(KeyNames.isKnown(KeyName("symbols", "q")))
    }

    private val keys = LookDefaults.KEYS.copy(
        function = KindStyle(KeyFill.Color, KeyPressed.Image(slice("fn-down.png"))),
        overrides = mapOf(
            "qwerty/q" to KeyOverride(KeyFill.Image(slice("q.png")), null, KeyLabel.Hide),
            "*/backspace" to KeyOverride(KeyFill.Image(slice("bs.png")), KeyPressed.Color(7), KeyLabel.Hide),
            "nine/backspace" to KeyOverride(null, KeyPressed.Color(8), KeyLabel.Show),
            "zhuyin/q" to KeyOverride(KeyFill.Image(slice("zq.png")), null, KeyLabel.Show),
            "k20/r1c1" to KeyOverride(KeyFill.Image(slice("new.png")), null, KeyLabel.Show),
        ),
    )

    @Test
    fun aKeysOwnLookCoversOnlyWhatItSets() {
        // Q's image, pressed as the letters
        assertEquals(
            KeyStyle(KeyFill.Image(slice("q.png")), KeyPressed.Darken, KeyLabel.Hide),
            KeyStyleResolver.resolve(keys, "qwerty", "q", KeyKind.Letter),
        )
        // the W has nothing of its own
        assertEquals(KeyStyle(KeyFill.Color, KeyPressed.Darken, KeyLabel.Show), KeyStyleResolver.resolve(keys, "qwerty", "w", KeyKind.Letter))
        // 大千's Q place is another key
        assertEquals(KeyFill.Image(slice("zq.png")), KeyStyleResolver.resolve(keys, "zhuyin", "q", KeyKind.Letter).fill)
        assertEquals(KeyFill.Color, KeyStyleResolver.resolve(keys, "nine", "q", KeyKind.Letter).fill)
    }

    @Test
    fun aBoardsOwnKeyComesBeforeEveryBoards() {
        assertEquals(
            KeyStyle(KeyFill.Image(slice("bs.png")), KeyPressed.Color(7), KeyLabel.Hide),
            KeyStyleResolver.resolve(keys, "qwerty", "backspace", KeyKind.Function),
        )
        // the nine keys' sets its pressed face and label; its face is every board's
        assertEquals(
            KeyStyle(KeyFill.Image(slice("bs.png")), KeyPressed.Color(8), KeyLabel.Show),
            KeyStyleResolver.resolve(keys, "nine", "backspace", KeyKind.Function),
        )
        // a key with no name, or no board: its kind
        assertEquals(KeyStyle(KeyFill.Color, KeyPressed.Image(slice("fn-down.png")), KeyLabel.Show), KeyStyleResolver.resolve(keys, "qwerty", null, KeyKind.Function))
        assertEquals(KeyFill.Image(slice("bs.png")), KeyStyleResolver.resolve(keys, null, "backspace", KeyKind.Function).fill)
    }

    @Test
    fun aBoardDecodesItsOwnImagesOnly() {
        assertEquals(
            setOf(slice("fn-down.png"), slice("bs.png"), slice("q.png")),
            KeyStyleResolver.imagesOn(keys, "qwerty", KeyNames.on("qwerty")),
        )
        assertEquals(setOf(slice("fn-down.png"), slice("zq.png")), KeyStyleResolver.imagesOn(keys, "zhuyin", listOf("q")))
        // a name no board has does nothing to the others
        assertEquals(setOf(slice("fn-down.png"), slice("bs.png")), KeyStyleResolver.imagesOn(keys, "nine", listOf("backspace", "2")))
    }

    @Test
    fun keyImagesOverBudgetAreDecodedSmaller() {
        val small = listOf(KeyImageBudget.Image("a", 100, 150), KeyImageBudget.Image("b", 100, 150))
        assertEquals(2L * 100 * 150 * 4, KeyImageBudget.bytes(small))
        assertEquals(1, KeyImageBudget.sampleSize(small))
        // the same file twice counts once
        assertEquals(100L * 150 * 4, KeyImageBudget.bytes(listOf(small[0], small[0])))
        // 52 of 300×300 (18.7 MB): halved, 4.7 MB
        val big = (1..52).map { KeyImageBudget.Image("k$it", 300, 300) }
        assertEquals(2, KeyImageBudget.sampleSize(big))
        assertEquals(52L * 150 * 150 * 4, KeyImageBudget.bytes(big, 2))
        assertEquals(4, KeyImageBudget.sampleSize(big, limit = 52L * 150 * 150 * 4 - 1))
        // never under a pixel a side
        assertEquals(4L, KeyImageBudget.bytes(listOf(KeyImageBudget.Image("t", 1, 1)), 8))
    }
}
