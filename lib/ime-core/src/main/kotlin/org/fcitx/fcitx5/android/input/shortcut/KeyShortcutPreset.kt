/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.SlotDefaults

/** Shortcuts before the user sets any, as the scene's [SlotDefaults]: letters as Ctrl's (a c x v z y), several-letter keys the most used, space up the edit menu, backspace up clears and down restores. */
object KeyShortcutPreset {

    /** Forms whose keys are letters: a group's preset is its letters'. */
    const val FULL = "full"
    const val NINE = "t9"
    const val GROUPED14 = "g14"
    const val GROUPED18 = "g18"
    const val NUMBER = "num"

    /** 大千 keys named by their QWERTY place, the nine keys by digit. */
    const val ZHUYIN_FULL = "zyfull"
    const val ZHUYIN_NINE = "zy9"

    /** `full/a`, or a function key's one way (`fn/space.up`). */
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
