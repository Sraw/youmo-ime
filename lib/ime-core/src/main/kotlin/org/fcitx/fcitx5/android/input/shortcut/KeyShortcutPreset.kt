/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.SlotDefaults

/**
 * The shortcuts a key has before the user sets any, as the scene's [SlotDefaults] has them (one table, 060-keys-unify):
 * the full keyboard's letters as Ctrl's on a computer (a select all, c copy, x cut, v paste, z undo, y redo), a key of
 * several (the nine keys' 2, the 14 keys' CV) the one used most; the space's up the edit menu, the backspace's up
 * clears the field and its down puts it back.
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

    /** [name]'s: a character key's (`full/a`), or a function key's one way (`fn/space.up`, [KeyShortcuts.functionKey]). */
    fun of(name: String): KeyShortcut {
        val command = if (KeyShortcuts.formOf(name) == KeyShortcuts.FUNCTION) {
            val up = name.endsWith(SwipeSlots.UP)
            if (!up && !name.endsWith(SwipeSlots.DOWN)) null
            else {
                val key = KeyId(name.substringBeforeLast('.'))
                (SlotDefaults.of(key, up, firstUp = true) as? SlotDefaults.Slot.Command)?.key
            }
        } else if ('/' in name) SlotDefaults.command(KeyId(name)) else null
        return command?.let(KeyCommand::ofKey)?.let(KeyShortcut::Command) ?: KeyShortcut.None
    }
}
