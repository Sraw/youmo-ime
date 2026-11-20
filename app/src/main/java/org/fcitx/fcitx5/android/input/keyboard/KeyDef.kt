/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.annotation.DrawableRes

open class KeyDef(
    val behaviors: Set<Behavior>,
    val popup: Array<Popup>? = null,
    /** Its board's prefix left off: its place (`q`, `2`, `r1c3`) or its role (`backspace`); null for one a theme does not style by itself. */
    val name: String? = null,
    /** A character key's `form/key` (`full/a`), a function key's own name (`space`); null for none (the panels' keyboards). */
    val shortcut: String? = null,
) {
    /** The cursor sideways, the shortcuts up and down, each locked to its axis. */
    open val swipesBothWays: Boolean get() = false

    sealed class Behavior {
        class Press(
            val action: KeyAction
        ) : Behavior()

        class LongPress(
            val action: KeyAction
        ) : Behavior()

        class Repeat(
            val action: KeyAction
        ) : Behavior()

        class Swipe(
            val action: KeyAction
        ) : Behavior()

        class DoubleTap(
            val action: KeyAction
        ) : Behavior()
    }

    sealed class Popup {
        open class Preview(val content: String) : Popup()

        class AltPreview(content: String, val alternative: String) : Preview(content)

        sealed class Keyboard : Popup() {
            /** [caps]: Shift on, a letter's in capitals as its face. */
            data class Preset(val label: String, val transformPunctuation: Boolean = true, val caps: Boolean = false) :
                Keyboard()

            /**
             * Those from [symbolsFrom] are symbols set apart, rows of their own above, shown as Chinese punctuation
             * maps them. [asIs]: every item committed as it is, never a key. [inRow]: the items before the symbols in
             * one row, the one at [inRow] under the finger first; -1, the first. [actions]: where an item's own differs
             * from a key's (a keypad's digit); null, each as [asIs] says.
             */
            class Explicit(
                val items: Array<String>,
                val symbolsFrom: Int = items.size,
                val asIs: Boolean = false,
                val inRow: Int = -1,
                val actions: List<KeyAction>? = null,
            ) : Keyboard() {
                init {
                    require(!asIs || symbolsFrom >= items.size) { "symbols committed as they are are not mapped" }
                    require(inRow < minOf(symbolsFrom, items.size)) { "the key first under the finger is one of the row" }
                    require(actions == null || actions.size == items.size) { "an action for each item" }
                }
            }

            /** Made as it opens ([BaseKeyboard.onPopupAction]); wired as [Named]. */
            class Resolved(val actions: List<KeyAction>, val labels: List<String>, val split: Int, val inRow: Int, val focus: Int = -1) : Keyboard()

            data class Named(val name: String) : Keyboard()
        }

        class Menu(val items: Array<Item>) : Popup() {
            class Item(val label: String, @DrawableRes val icon: Int, val action: KeyAction)
        }
    }
}