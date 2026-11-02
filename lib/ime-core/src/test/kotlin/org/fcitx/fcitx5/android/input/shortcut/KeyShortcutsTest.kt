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

    @Test
    fun aBackslashBeforeNothingEscapedIsKept() {
        assertEquals(KeyShortcut.Text("a\\b\\"), KeyShortcuts.parse("full/q\ttext:a\\b\\").resolve("full/q"))
    }

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
        assertEquals(KeyShortcuts.Empty.hashCode(), KeyShortcuts.parse("").hashCode())
        assertTrue(KeyShortcuts.Empty.with("full/a", paste).toString().contains("full/a"))
    }
}
