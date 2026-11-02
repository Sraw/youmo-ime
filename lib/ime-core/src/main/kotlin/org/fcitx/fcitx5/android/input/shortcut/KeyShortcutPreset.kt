/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

/**
 * The shortcuts a key has before the user sets any: the full keyboard's letters as Ctrl's on a
 * computer (a select all, c copy, x cut, v paste, z undo, y redo); a key of several of those
 * letters (the nine keys' 2, the 14 keys' CV) takes the one used most ([PRIORITY]); the space's
 * up is the edit menu, the backspace's up clears the field and its down puts it back.
 */
object KeyShortcutPreset {

    /** The forms whose keys are letters, their names the letters: a group's preset is its letters'. */
    const val FULL = "full"
    const val NINE = "t9"
    const val GROUPED14 = "g14"
    const val GROUPED18 = "g18"
    const val NUMBER = "num"

    /** 注音's two keyboards: the 大千 keys named by where they are on QWERTY, the nine keys by their digit. */
    const val ZHUYIN_FULL = "zyfull"
    const val ZHUYIN_NINE = "zy9"

    private val LETTERS = mapOf(
        'a' to KeyCommand.SelectAll,
        'c' to KeyCommand.Copy,
        'x' to KeyCommand.Cut,
        'v' to KeyCommand.Paste,
        'z' to KeyCommand.Undo,
        'y' to KeyCommand.Redo,
    )

    private val PRIORITY = listOf(KeyCommand.Paste, KeyCommand.Copy, KeyCommand.Undo, KeyCommand.SelectAll, KeyCommand.Cut, KeyCommand.Redo)

    // the nine keys' digits' letters, as on a phone
    private val NINE_LETTERS = mapOf('2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl", '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz")

    private val FUNCTION = mapOf(
        KeyShortcuts.functionKey("space", up = true) to KeyCommand.EditMenu,
        KeyShortcuts.functionKey("backspace", up = true) to KeyCommand.Clear,
        KeyShortcuts.functionKey("backspace", up = false) to KeyCommand.Restore,
    )

    /** The most used of [letters]' commands, if any has one. */
    fun ofLetters(letters: String): KeyCommand? {
        val commands = letters.lowercase().mapNotNull { LETTERS[it] }
        return PRIORITY.firstOrNull { it in commands }
    }

    fun of(name: String): KeyShortcut {
        val form = KeyShortcuts.formOf(name)
        val key = name.substringAfter('/', "")
        val command = when (form) {
            KeyShortcuts.FUNCTION -> FUNCTION[name]
            FULL, GROUPED14, GROUPED18, ZHUYIN_FULL -> ofLetters(key)
            NINE -> key.singleOrNull()?.let(NINE_LETTERS::get)?.let(::ofLetters)
            // 注音九键's keys are no letters, the number pad's digits none either
            else -> null
        }
        return command?.let(KeyShortcut::Command) ?: KeyShortcut.None
    }
}
