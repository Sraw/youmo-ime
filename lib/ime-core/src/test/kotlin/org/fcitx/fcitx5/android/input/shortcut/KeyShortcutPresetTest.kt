/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyShortcutPresetTest {

    private fun command(name: String) = (KeyShortcutPreset.of(name) as? KeyShortcut.Command)?.command

    private fun assertRows(vararg rows: Pair<String, KeyCommand?>) = rows.forEach { (name, command) ->
        assertEquals(name, command, command(name))
    }

    @Test
    fun theFullKeyboardsLettersAreCtrls() = assertRows(
        "full/a" to KeyCommand.SelectAll,
        "full/c" to KeyCommand.Copy,
        "full/x" to KeyCommand.Cut,
        "full/v" to KeyCommand.Paste,
        "full/z" to KeyCommand.Undo,
        "full/y" to KeyCommand.Redo,
        "full/q" to null,
        "zyfull/v" to KeyCommand.Paste,
    )

    @Test
    fun aGroupTakesItsMostUsedLetters() = assertRows(
        "g14/as" to KeyCommand.SelectAll,
        "g14/zx" to KeyCommand.Undo,
        "g14/cv" to KeyCommand.Paste,
        "g14/ty" to KeyCommand.Redo,
        "g14/qw" to null,
        "g18/a" to KeyCommand.SelectAll,
        "g18/xc" to KeyCommand.Copy,
        "g18/v" to KeyCommand.Paste,
        "g18/z" to KeyCommand.Undo,
        "g18/y" to KeyCommand.Redo,
        "t9/2" to KeyCommand.Copy,
        "t9/8" to KeyCommand.Paste,
        "t9/9" to KeyCommand.Undo,
        "t9/3" to null,
        "t9/1" to null,
    )

    @Test
    fun functionKeysTheSpaceAndBackspaceOnly() = assertRows(
        "fn/space.up" to KeyCommand.EditMenu,
        "fn/space.down" to null,
        "fn/backspace.up" to KeyCommand.Clear,
        "fn/backspace.down" to KeyCommand.Restore,
        "fn/return.up" to null,
        "num/5" to null,
        "zy9/2" to null,
        "nothing" to null,
    )
}
