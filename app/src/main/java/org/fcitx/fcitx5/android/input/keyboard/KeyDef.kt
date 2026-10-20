/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.data.InputFeedbacks

open class KeyDef(
    val appearance: Appearance,
    val behaviors: Set<Behavior>,
    val popup: Array<Popup>? = null
) {
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
             * are symbols set apart: rows of their own above the rest, a line between, each shown as
             * Chinese punctuation maps it, typed as a key the engine maps. [asIs]: every item is
             * committed as it is, never a key (none set apart then: nothing would map them).
             */
            class Explicit(val items: Array<String>, val symbolsFrom: Int = items.size, val asIs: Boolean = false) : Keyboard() {
                init {
                    require(!asIs || symbolsFrom >= items.size) { "symbols committed as they are are not mapped" }
                }
            }
        }

        class Menu(val items: Array<Item>) : Popup() {
            class Item(val label: String, @DrawableRes val icon: Int, val action: KeyAction)
        }
    }
}