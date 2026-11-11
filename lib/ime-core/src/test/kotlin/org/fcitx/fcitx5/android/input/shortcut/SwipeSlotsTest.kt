/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot.Command
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot.Empty
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot.First
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeSlotsTest {

    private val selectAll = Command(KeyCommand.SelectAll)
    private val copy = Command(KeyCommand.Copy)
    private val space = SwipeSlots.functionKey("space")
    private val backspace = SwipeSlots.functionKey("backspace")

    @Test
    fun byDefaultALetterHasItsFirstUpItsShortcutDown() {
        val s = SwipeSlots.Default
        assertEquals(First, s.resolve("full/a", up = true))
        assertEquals(selectAll, s.resolve("full/a", up = false))
        assertEquals("no shortcut preset", Empty, s.resolve("full/q", up = false))
        assertEquals(SwipeSlot.of(KeyShortcutPreset.of("t9/2")), s.resolve("t9/2", up = false))
        assertEquals(First, s.resolve("zy9/3", up = true))
    }

    @Test
    fun aFunctionKeyHasItsOwnPresetsEitherOrientation() {
        for (s in listOf(SwipeSlots.Default, SwipeSlots.Default.applied(SwipeOrientation.FirstDown))) {
            assertEquals(Command(KeyCommand.EditMenu), s.resolve(space, up = true))
            assertEquals(Empty, s.resolve(space, up = false))
            assertEquals(Command(KeyCommand.Clear), s.resolve(backspace, up = true))
            assertEquals(Command(KeyCommand.Restore), s.resolve(backspace, up = false))
        }
    }

    @Test
    fun firstDownSwapsTheCharacterKeysPresets() {
        val s = SwipeSlots.Default.applied(SwipeOrientation.FirstDown)
        assertEquals(selectAll, s.resolve("full/a", up = true))
        assertEquals(First, s.resolve("full/a", up = false))
    }

    @Test
    fun aSlotAtItsPresetIsNotKept() {
        val s = SwipeSlots.Default.with("full/a", up = true, Text("你好"))
        assertTrue(s.isSet("full/a"))
        assertEquals(Text("你好"), s.resolve("full/a", up = true))
        val back = s.with("full/a", up = true, First)
        assertFalse(back.isSet("full/a"))
        assertEquals(SwipeSlots.Default, back)
    }

    @Test
    fun roundTripsThroughText() {
        val s = SwipeSlots.Default.applied(SwipeOrientation.FirstDown)
            .with("full/q", up = false, Text("a\tb\nc\\d\re"))
            .with("full/a", up = true, Empty)
            .with("t9/5", up = false, copy)
            .with(space, up = false, First)
        val text = s.serialize()
        assertTrue(text, text.startsWith("@1\tfirst-down\n"))
        assertEquals(s, SwipeSlots.parse(text))
        assertEquals(s, SwipeSlots.parse(text.replace("\n", "\r\n")))
    }

    @Test
    fun parseSkipsWhatItCannotRead() {
        val s = SwipeSlots.parse("garbage\nfull/a.up\tno-such-command\nfull/b\tfirst\n@1\tsideways\nfull/c.up\ttext:\nfull/a.down\tnone\n\t\n")
        assertEquals(SwipeOrientation.FirstUp, s.orientation)
        assertEquals(setOf("full/a"), s.keys)
        assertEquals(Empty, s.resolve("full/a", up = false))
        assertEquals(SwipeSlots.Default, SwipeSlots.parse(""))
        // a line at its preset (a backup edited by hand) is none
        assertEquals(SwipeSlots.Default, SwipeSlots.parse("@1\tfirst-up\nfull/a.up\tfirst\nfull/a.down\tselect-all"))
    }

    @Test
    fun aLongTextIsClipped() {
        val s = SwipeSlots.parse("full/a.up\ttext:" + "字".repeat(KeyShortcut.MAX_TEXT + 5))
        assertEquals(KeyShortcut.MAX_TEXT, (s.resolve("full/a", up = true) as Text).text.length)
    }

    @Test
    fun flippingSelectedKeysSwapsTheirOwnSlots() {
        val s = SwipeSlots.Default.with("full/q", up = false, Text("Q")).flipped(listOf("full/a", "full/q", space))
        assertEquals(selectAll, s.resolve("full/a", up = true))
        assertEquals(First, s.resolve("full/a", up = false))
        assertEquals(Text("Q"), s.resolve("full/q", up = true))
        assertEquals(First, s.resolve("full/q", up = false))
        assertEquals(Command(KeyCommand.EditMenu), s.resolve(space, up = false))
        assertEquals(Empty, s.resolve(space, up = true))
        assertEquals("others as they were", First, s.resolve("full/s", up = true))
        assertEquals("flipped twice is as it was", SwipeSlots.Default.with("full/q", up = false, Text("Q")), s.flipped(listOf("full/a", "full/q", space)))
    }

    @Test
    fun flippingAllSwapsEveryKeyTheOrientationToo() {
        val fns = listOf(space, backspace)
        val before = SwipeSlots.Default.with("full/q", up = false, Text("Q")).with("t9/5", up = true, copy)
        val s = before.flippedAll(fns)
        assertEquals(SwipeOrientation.FirstDown, s.orientation)
        assertEquals(selectAll, s.resolve("full/a", up = true))
        assertEquals(First, s.resolve("full/a", up = false))
        assertEquals(Text("Q"), s.resolve("full/q", up = true))
        assertEquals(copy, s.resolve("t9/5", up = false))
        assertEquals(Command(KeyCommand.EditMenu), s.resolve(space, up = false))
        assertEquals(Command(KeyCommand.Restore), s.resolve(backspace, up = true))
        assertEquals(Command(KeyCommand.Clear), s.resolve(backspace, up = false))
        assertEquals("flipped twice is as it was", before, s.flippedAll(fns))
    }

    @Test
    fun batchSetsAndResets() {
        val keys = listOf("full/a", "full/b")
        val empty = SwipeSlots.Default.withAll(keys, up = null, Empty)
        keys.forEach {
            assertEquals(Empty, empty.resolve(it, up = true))
            assertEquals(Empty, empty.resolve(it, up = false))
        }
        val downFirst = SwipeSlots.Default.withAll(keys, up = false, First)
        assertEquals(First, downFirst.resolve("full/a", up = false))
        assertEquals(First, downFirst.resolve("full/a", up = true))
        assertEquals(SwipeSlots.Default, empty.without(keys))
        assertEquals("a preset applied keeps nothing", SwipeSlots.Default.applied(SwipeOrientation.FirstDown), empty.applied(SwipeOrientation.FirstDown))
    }

    @Test
    fun keyNamesFromLayouts() {
        assertEquals("fn/space", SwipeSlots.keyOf("space"))
        assertEquals("full/a", SwipeSlots.keyOf("full/a"))
        assertTrue(SwipeSlots.isFunction("fn/space"))
        assertFalse(SwipeSlots.isFunction("full/a"))
        assertEquals(KeyShortcut.Text("x"), Text("x").shortcut)
        assertEquals(KeyShortcut.None, First.shortcut)
    }

    @Test
    fun aMarkShowsTwoCharactersAsSeen() {
        assertEquals("你好", KeyMarkText.of("你好"))
        assertEquals("你好…", KeyMarkText.of("你好世界"))
        assertEquals("a", KeyMarkText.of("a"))
        assertEquals("🇨🇳🇯🇵…", KeyMarkText.of("🇨🇳🇯🇵🇺🇸"))
        assertEquals("a family and an accented letter, one each", "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67e\u0301…", KeyMarkText.of("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67e\u0301x"))
        assertEquals("a…", KeyMarkText.of("a\n  b c"))
        // nothing but spaces and breaks: signs of them, never a key that looks unset
        assertEquals("␣", KeyMarkText.of(" "))
        assertEquals("↵", KeyMarkText.of("\n"))
        assertEquals("⇥␣…", KeyMarkText.of("\t  "))
    }

    // a newer version's slots (a backup of it, a downgrade): its lines are not read, the presets stand
    @Test
    fun anotherVersionsSlotsAreThePresets() {
        assertEquals(SwipeSlots.Default, SwipeSlots.parse("@2\tfirst-down\nfull/a.up\tcopy"))
        assertEquals(SwipeSlots.Default, SwipeSlots.parse("@1\tfirst-down\n@2\nfull/a.up\tcopy"))
        assertEquals(SwipeOrientation.FirstDown, SwipeSlots.parse("@1\tfirst-down\nfull/a.up\tcopy").orientation)
    }

    @Test
    fun theCommaAndTheFullStopTypeACharacter() {
        assertEquals(true, SwipeSlots.typesAChar("full/a"))
        assertEquals(true, SwipeSlots.typesAChar("zy9/3"))
        assertEquals(true, SwipeSlots.typesAChar("fn/comma"))
        assertEquals(true, SwipeSlots.typesAChar("fn/period"))
        assertEquals(false, SwipeSlots.typesAChar("fn/space"))
        assertEquals(false, SwipeSlots.typesAChar("fn/backspace"))
    }
}
