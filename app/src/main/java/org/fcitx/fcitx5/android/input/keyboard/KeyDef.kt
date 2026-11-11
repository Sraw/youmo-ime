/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keys.KeyOutput
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.data.InputFeedbacks

open class KeyDef(
    val appearance: Appearance,
    val behaviors: Set<Behavior>,
    val popup: Array<Popup>? = null,
    /**
     * The key's name in a theme, its board's prefix left off ([KeyNames][org.fcitx.fcitx5.android.input.theme.KeyNames]):
     * its place (`q`, `2`, `r1c3`) or its role (`backspace`); null for a key a theme does not style by itself.
     */
    val name: String? = null,
    /**
     * What the key's swipe shortcuts are stored under ([org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts]):
     * a character key's `form/key` (`full/a`), a function key's own name (`space`); null for a key
     * with none, as on the panels' keyboards.
     */
    val shortcut: String? = null,
) {
    /** Whether the key's swipes go both ways (the cursor sideways, the shortcuts up and down), each locked to its axis. */
    open val swipesBothWays: Boolean get() = false

    sealed class Appearance(
        val percentWidth: Float,
        val variant: Variant,
        val border: Border,
        val margin: Boolean,
        val viewId: Int,
        val soundEffect: InputFeedbacks.SoundEffect,
        /**
         * Accessibility label for this key, or `0` for none.
         *
         * Keys that show text are already announced by their [Text.displayText]; this is for
         * icon-only keys, which are otherwise anonymous both to screen readers and to any
         * tooling that walks the accessibility tree.
         */
        @StringRes val contentDescription: Int
    ) {
        enum class Variant {
            Normal, AltForeground, Alternative, Accent
        }

        enum class Border {
            Default, On, Off, Special
        }

        open class Text(
            val displayText: String,
            val textSize: Float,
            /**
             * `Int` constants in [Typeface].
             * Can be `NORMAL`(default), `BOLD`, `ITALIC` or `BOLD_ITALIC`
             */
            val textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard,
            @StringRes contentDescription: Int = 0
        ) : Appearance(
            percentWidth, variant, border, margin, viewId, soundEffect, contentDescription
        )

        class AltText(
            displayText: String,
            val altText: String,
            textSize: Float,
            /**
             * `Int` constants in [Typeface].
             * Can be `NORMAL`(default), `BOLD`, `ITALIC` or `BOLD_ITALIC`
             */
            textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            @StringRes contentDescription: Int = 0
        ) : Text(
            displayText, textSize, textStyle, percentWidth, variant, border, margin, viewId,
            contentDescription = contentDescription
        )

        class Image(
            @DrawableRes
            val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard,
            @StringRes contentDescription: Int = 0
        ) : Appearance(
            percentWidth, variant, border, margin, viewId, soundEffect, contentDescription
        )

        class ImageText(
            displayText: String,
            textSize: Float,
            /**
             * `Int` constants in [Typeface].
             * Can be `NORMAL`(default), `BOLD`, `ITALIC` or `BOLD_ITALIC`
             */
            textStyle: Int = Typeface.NORMAL,
            @DrawableRes
            val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            @StringRes contentDescription: Int = 0
        ) : Text(
            displayText, textSize, textStyle, percentWidth, variant, border, margin, viewId,
            contentDescription = contentDescription
        )
    }

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
            data class Preset(val label: String, val transformPunctuation: Boolean = true) :
                Keyboard()

            /**
             * [items], typed as keys (one character) or committed (longer); those from [symbolsFrom]
             * are symbols set apart: rows of their own above the rest, each shown as Chinese
             * punctuation maps it, typed as a key the engine maps. [asIs]: every item is committed as
             * it is, never a key (none set apart then: nothing would map them). [inRow]: the items
             * before the symbols in one row, the one at [inRow] (the key's own) under the finger first
             * and the rest round it as listed, as the full keyboard's (060-edge-popup); -1, the first.
             */
            class Explicit(
                val items: Array<String>,
                val symbolsFrom: Int = items.size,
                val asIs: Boolean = false,
                val inRow: Int = -1,
            ) : Keyboard() {
                init {
                    require(!asIs || symbolsFrom >= items.size) { "symbols committed as they are are not mapped" }
                    require(inRow < minOf(symbolsFrom, items.size)) { "the key first under the finger is one of the row" }
                }
            }

            /**
             * A named key's long press as the resolver has it now (060-keys-unify §1.3): each item typed as its
             * [outputs]' says, shown as [labels]; those from [split] set apart, [inRow] under the finger first
             * as [Explicit]'s. Made as it opens ([BaseKeyboard.onPopupAction]); wired as [Named].
             */
            class Resolved(val outputs: List<KeyOutput>, val labels: List<String>, val split: Int, val inRow: Int) : Keyboard()

            /** A named key's long press, the resolver's made as it opens: what [BaseKeyboard.wire] wires. */
            data class Named(val name: String) : Keyboard()
        }

        class Menu(val items: Array<Item>) : Popup() {
            class Item(val label: String, @DrawableRes val icon: Int, val action: KeyAction)
        }
    }
}