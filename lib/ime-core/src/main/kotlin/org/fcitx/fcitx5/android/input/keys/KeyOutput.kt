/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.scene.SceneAction
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.Item

/**
 * What a long press's item, a swipe's first, types (060-keys-unify §3.1, M1): the one way, whatever popup or
 * swipe chose it, so how an item is committed is the item's, never the popup's.
 */
sealed interface KeyOutput {
    /** One code point typed as a key would (through the keyboard's own reading: 九键's letters, 注音's symbols). */
    data class Press(val text: String) : KeyOutput

    /** Committed as it is: an as-is item, or one of more code points. */
    data class Text(val text: String) : KeyOutput

    /** A keypad's digit: its candidate picked while typing, else the digit (D7). */
    data class Keypad(val digit: Char) : KeyOutput

    /** A layout's `<item send=…>`: the action it names. */
    data class Action(val action: SceneAction) : KeyOutput

    companion object {
        /** [item]'s; null for an action no build reads. */
        fun of(item: Item): KeyOutput? = when (val c = item.commit) {
            Commit.AsKey -> if (item.text.codePointCount(0, item.text.length) == 1) Press(item.text) else Text(item.text)
            Commit.AsIs -> Text(item.text)
            is Commit.Keypad -> Keypad(c.digit)
            is Commit.Action -> SceneAction.of(c.send)?.let(::Action)
        }
    }
}
