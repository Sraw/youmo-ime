/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyShortcutsTest {

    private val paste = KeyShortcut.Command(KeyCommand.Paste)

    @Test
    fun aKeyNotSetHasItsPreset() {
        assertEquals(KeyShortcut.Command(KeyCommand.SelectAll), KeyShortcuts.Empty.resolve("full/a"))
        assertEquals(KeyShortcut.None, KeyShortcuts.Empty.resolve("full/s"))
        assertFalse(KeyShortcuts.Empty.isSet("full/a"))
    }

    // contract: the stored line format (key, tab, command or text:), which a later version must read back
    @Test
    fun noneSetsAPresetToNothing() {
        val shortcuts = KeyShortcuts.parse("full/a\tnone")
        assertEquals(KeyShortcut.None, shortcuts.resolve("full/a"))
        assertTrue(shortcuts.isSet("full/a"))
    }

    @Test
    fun textRoundTripsItsTabsLineBreaksAndBackslashes() {
        val text = KeyShortcut.Text("a\tb\nc\\d @qq.com")
        val shortcuts = KeyShortcuts.Empty.with("full/q", text).with("fn/return.down", paste)
        val stored = shortcuts.serialize()
        assertEquals(2, stored.lines().size)
        assertEquals(shortcuts, KeyShortcuts.parse(stored))
        assertEquals(text, KeyShortcuts.parse(stored).resolve("full/q"))
    }

    // review S3: a text pasted from a PC, its lines ended by \r\n, stored and read back whole
    // contract: the stored line format (key, tab, command or text:), which a later version must read back
    @Test
    fun textRoundTripsItsCarriageReturns() {
        val text = KeyShortcut.Text("Best,\r\nMe\rand \\r")
        val shortcuts = KeyShortcuts.Empty.with("full/q", text).with("full/w", paste)
        val stored = shortcuts.serialize()
        assertFalse(stored.contains('\r'))
        assertEquals(shortcuts, KeyShortcuts.parse(stored))
        // a \r in a line stored by hand is the text's, not a line's end
        assertEquals(KeyShortcut.Text("a\rb"), KeyShortcuts.parse("full/q\ttext:a\rb").resolve("full/q"))
    }

    // a backup's lines ended by \r\n: a command or none still read; a text loses the line's \r (it
    // would otherwise be typed every time), its own \r stored escaped and kept
    @Test
    fun aLineEndedByCarriageReturnStillReads() {
        val shortcuts = KeyShortcuts.parse("a/b\tnone\r\nc/d\tcopy\r\n")
        assertEquals(KeyShortcut.None, shortcuts.resolve("a/b"))
        assertEquals(KeyShortcut.Command(KeyCommand.Copy), shortcuts.resolve("c/d"))
        assertEquals(KeyShortcut.Text("x"), KeyShortcuts.parse("a/b\ttext:x\r\n").resolve("a/b"))
        assertEquals(KeyShortcut.Text("x\r"), KeyShortcuts.parse("a/b\ttext:x\\r\r\n").resolve("a/b"))
    }

    // contract: the stored line format (key, tab, command or text:), which a later version must read back
    @Test
    fun aBackslashBeforeNothingEscapedIsKept() {
        assertEquals(KeyShortcut.Text("a\\b\\"), KeyShortcuts.parse("full/q\ttext:a\\b\\").resolve("full/q"))
    }

    // contract: the stored line format (key, tab, command or text:), which a later version must read back
    @Test
    fun badLinesAreSkippedALaterLineWins() {
        val shortcuts = KeyShortcuts.parse(
            listOf("", "no tab", "\tpaste", "noslash\tpaste", "full/q\tno_such_command", "full/w\ttext:", "full/e\tcopy", "full/e\tpaste")
                .joinToString("\n"),
        )
        assertEquals(KeyShortcut.None, shortcuts.resolve("full/q"))
        assertFalse(shortcuts.isSet("full/q"))
        assertFalse(shortcuts.isSet("full/w"))
        assertEquals(paste, shortcuts.resolve("full/e"))
        assertSame(KeyShortcuts.Empty, KeyShortcuts.parse("rubbish"))
        assertTrue(KeyShortcuts.parse("").isEmpty)
    }

    @Test
    fun aTextIsCutAtItsLongest() {
        val long = "x".repeat(KeyShortcut.MAX_TEXT + 5)
        assertEquals(KeyShortcut.MAX_TEXT, (KeyShortcuts.parse("full/q\ttext:$long").resolve("full/q") as KeyShortcut.Text).text.length)
        assertThrows(IllegalArgumentException::class.java) { KeyShortcut.Text("") }
        // an emoji across the end is left out whole, never half of it kept
        val emoji = "x".repeat(KeyShortcut.MAX_TEXT - 1) + "\uD83D\uDE00"
        assertEquals("x".repeat(KeyShortcut.MAX_TEXT - 1), (KeyShortcuts.parse("full/q\ttext:$emoji").resolve("full/q") as KeyShortcut.Text).text)
        assertEquals("ab\uD83D\uDE00", KeyShortcut.clip("ab\uD83D\uDE00", 4))
        assertEquals("ab", KeyShortcut.clip("ab\uD83D\uDE00c", 3))
    }

    @Test
    fun withoutPutsTheKeyBackToItsPreset() {
        val shortcuts = KeyShortcuts.Empty.with("full/a", paste)
        assertEquals(paste, shortcuts.resolve("full/a"))
        assertEquals(KeyShortcuts.Empty, shortcuts.without("full/a"))
        assertSame(shortcuts, shortcuts.without("full/b"))
    }

    @Test
    fun presetsAppliedToAPageLeaveItsOtherKeys() {
        val shortcuts = KeyShortcuts.Empty.with("full/a", paste).with("full/s", paste).with("t9/2", paste)
        val applied = shortcuts.withPresets("full")
        assertFalse(applied.isSet("full/a"))
        assertEquals(paste, applied.resolve("full/s"))
        assertEquals(paste, applied.resolve("t9/2"))
    }

    @Test
    fun aPageClearedHasNothingItsPresetsNeither() {
        val cleared = KeyShortcuts.Empty.cleared(listOf("full/a", "full/s"))
        assertEquals(KeyShortcut.None, cleared.resolve("full/a"))
        assertTrue(cleared.isSet("full/s"))
    }

    // contract: the keys' names in the stored lines
    @Test
    fun aKeyIsNamedByItsPageAndItself() {
        assertEquals("full", KeyShortcuts.formOf("full/a"))
        assertEquals("fn/space.up", KeyShortcuts.functionKey("space", up = true))
        assertEquals("fn/space.down", KeyShortcuts.functionKey("space", up = false))
        assertEquals("g14/cv", KeyShortcuts.charKey("g14", "cv"))
    }

    @Test
    fun aStoredCommandKeyNamesItsCommand() {
        KeyCommand.entries.forEach { assertEquals(it, KeyCommand.ofKey(it.key)) }
        assertNull(KeyCommand.ofKey("nothing"))
        assertEquals(setOf(KeyCommand.Copy, KeyCommand.Cut), KeyCommand.entries.filter { it.readsField }.toSet())
    }

    // the empty set and one parsed from nothing are equal, so their hashes must be too
    @Test
    fun equalSetsHashAlike() {
        assertEquals(KeyShortcuts.Empty, KeyShortcuts.parse(""))
        assertEquals(KeyShortcuts.Empty.hashCode(), KeyShortcuts.parse("").hashCode())
    }
}
