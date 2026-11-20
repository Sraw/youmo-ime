/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.scene.SceneAction
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.Item

/** What a long press's item or a swipe's first types: one way, whatever popup chose it. */
sealed interface KeyOutput {
    /** One code point typed as a key, through the keyboard's own reading (九键 letters, 注音 symbols). */
    data class Press(val text: String) : KeyOutput

    /** An as-is item, or one of several code points. */
    data class Text(val text: String) : KeyOutput

    /** Its candidate picked while typing, else the digit. */
    data class Keypad(val digit: Char) : KeyOutput

    /** A layout's `<item send=…>`. */
    data class Action(val action: SceneAction) : KeyOutput

    companion object {
        /** Null for an action no build reads. */
        fun of(item: Item): KeyOutput? = when (val c = item.commit) {
            Commit.AsKey -> if (item.text.codePointCount(0, item.text.length) == 1) Press(item.text) else Text(item.text)
            Commit.AsIs -> Text(item.text)
            is Commit.Keypad -> Keypad(c.digit)
            is Commit.Action -> SceneAction.of(c.send)?.let(::Action)
        }
    }
}
