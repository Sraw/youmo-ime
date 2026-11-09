/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Value

/**
 * The app's own panels over the keyboard, made of views of its own (060-render-p3 T7b; §16: their
 * native parts know the theme's colours and a few properties): the clipboard's entries, the emoji
 * and symbol picker's categories, the editing panel's buttons, the voice mask. What a theme sets
 * is its own, as it says; what it does not, the default rule below, the look the app had worked
 * out itself, so a theme that sets none of it, theme 3.0's among them, looks as it did. The
 * handwriting pad's is the scene's ([Pads]).
 *
 * [scenes] are the screen's and the settings' (the window's: what is typed aside).
 */
class PanelLooks(private val cascades: Cascades) {
    private val theme = cascades.theme

    /**
     * A clipboard entry (`clipboard`): its card's fill, corners and text colour. Not set: the
     * `clipboardEntry` colour, half as round as the keys (as the user's 按键圆角 has them, whatever
     * `@flat`; a pill's keys, a pill), the keys' text colour.
     */
    fun clipboard(scenes: Set<String>): Clipboard {
        val v = cascades.of(scenes).of(CLIPBOARD)
        val radius = (v[RADIUS] as? Value.OfRadius)?.radius ?: keyRadius(scenes).let { it.copy(corners = it.corners.map { c -> c / 2 }) }
        return Clipboard(
            fill = fill(v) ?: variable(CLIPBOARD_ENTRY),
            radiusDp = radius.corners.max(),
            round = radius.half,
            color = color(v[COLOR]) ?: variable(KEY_TEXT),
        )
    }

    /**
     * The picker's (`picker`): its [Picker.fill] under the grid (not set: none, the keyboard's
     * under it), its bar of categories (`picker categories`) and the one shown (`picker:selected
     * categories`), the symbols' column down its [side] or the emoji's tabs in the bar. Not set: the
     * column the bar's colour and the one shown the keyboard's, the tabs on the bar's own; their
     * text the keys' text colour, faded but the one shown (by [SIDE_FADE], [TAB_FADE]).
     */
    fun picker(scenes: Set<String>, side: Boolean): Picker {
        val cascade = cascades.of(scenes)
        val v = cascade.of(CATEGORIES)
        val shown = cascade.of(CATEGORIES_SELECTED)
        val text = variable(KEY_TEXT)
        return Picker(
            fill = fill(cascade.of(PICKER)),
            categories = Categories(
                fill = fill(v) ?: variable(BAR).takeIf { side },
                color = color(v[COLOR]) ?: Argb((text.value and RGB) or ((if (side) SIDE_FADE else TAB_FADE) shl ALPHA)),
            ),
            selected = Categories(
                fill = fill(shown) ?: variable(KEYBOARD).takeIf { side },
                color = color(shown[COLOR]) ?: text,
            ),
        )
    }

    /**
     * The editing panel's buttons (`editing`): their fill and corners. Not set: the keys' colours (the
     * function keys' for the ones that act on the selection), drawn as blocks with the keys' shadow, or,
     * the keys flat (`@flat`), as a grid of lines; [Editing.radiusDp] null, the app's own of before.
     * A fill set is drawn as blocks, whatever the keys.
     */
    fun editing(scenes: Set<String>): Editing {
        val v = cascades.of(scenes).of(EDITING)
        val radius = (v[RADIUS] as? Value.OfRadius)?.radius
        return Editing(
            fill = fill(v),
            radiusDp = radius?.corners?.max(),
            round = radius?.half == true,
            keyRadiusDp = keyRadius(scenes).corners.max(),
        )
    }

    /**
     * The voice mask (`voice`): its colour over the keys, its frosted glass's blur (dp, 0: none, the keys
     * plain under it), its microphone's bubble (`voice mic`) and the sound's bars in it. Not set: the
     * keyboard's colour less translucent by [MASK_ALPHA] (VoiceStyle.mask's), the glass of before
     * ([Voice.blurDp] null), the bubble the accent colour at [MIC_ALPHA], the bars it.
     */
    fun voice(scenes: Set<String>): Voice {
        val cascade = cascades.of(scenes)
        val v = cascade.of(VOICE)
        val mic = cascade.of(MIC)
        val accent = variable(ACCENT_KEY)
        return Voice(
            fill = fill(v) ?: mask(variable(KEYBOARD)),
            blurDp = (v[BLUR] as? Value.Number)?.number,
            micFill = fill(mic) ?: Argb((accent.value and RGB) or (MIC_ALPHA shl ALPHA)),
            micColor = color(mic[ICON_COLOR]) ?: accent,
        )
    }

    // the keys' corners as the user's 按键圆角 has them, the letters' (WindowLooks.cornerDp's): `@flat` keys' faces are
    // square whatever it says, but what is round about them, their popups and panels, is as round as it
    private fun keyRadius(scenes: Set<String>) = KeyLook(cascades.of(scenes - FLAT).of(KEY), theme).radius

    private fun color(v: Value?): Argb? = (v as? Value.OfColor)?.color?.let(theme::color)

    // a solid fill's colour: what a view's background takes (a picture's or an image's are the scene's alone)
    private fun fill(v: Map<String, Value>): Argb? = KeyLook(v, theme).fill?.let { f -> (f as? Fill.Solid)?.color?.let(theme::color) }

    // one of the colours every theme has (ThemeReader.REQUIRED_COLORS)
    private fun variable(name: String): Argb = theme.colors[name] ?: Argb.TRANSPARENT

    /** A clipboard entry: its card's [fill], corners (dp; [round] a pill), its text's [color]. */
    data class Clipboard(val fill: Argb, val radiusDp: Double, val round: Boolean, val color: Argb)

    /** The picker: what is under its grid ([fill], null none), its categories, and the one shown of them. */
    data class Picker(val fill: Argb?, val categories: Categories, val selected: Categories)

    /** The picker's categories: their background ([fill], null none) and text or icon [color]. */
    data class Categories(val fill: Argb?, val color: Argb)

    /**
     * The editing panel's buttons: their [fill] (null: the keys' colours as before), corners ([radiusDp] null: the
     * app's of before, of the keys' [keyRadiusDp]; [round] a pill).
     */
    data class Editing(val fill: Argb?, val radiusDp: Double?, val round: Boolean, val keyRadiusDp: Double)

    /** The voice mask: its colour ([fill]), its glass's blur (dp; null the app's own), its microphone's bubble and bars. */
    data class Voice(val fill: Argb, val blurDp: Double?, val micFill: Argb, val micColor: Argb)

    companion object {
        /** The symbols' column of categories, not the one shown: the keys' text at 60% (PickerSideUi's INACTIVE). */
        const val SIDE_FADE = 153

        /** The emoji's tabs, not the one shown: at 50% (0.5 × 255, rounded as the app's `alpha()`). */
        const val TAB_FADE = 128

        /** The voice mask's alpha of the keyboard's colour's, of 255 (VoiceStyle.MASK_ALPHA). */
        const val MASK_ALPHA = 0xE0

        /** The voice microphone's bubble not set: the accent colour at this alpha (the overlays' PILL_ALPHA). */
        const val MIC_ALPHA = 0x29

        /** [color] less translucent by [MASK_ALPHA]: its alpha × 0xE0 / 0xFF, in whole numbers (VoiceStyle.mask's). */
        fun mask(color: Argb): Argb = Argb((color.value and RGB) or ((color.alpha * MASK_ALPHA / BYTE) shl ALPHA))

        private const val RGB = 0xFFFFFF
        private const val ALPHA = 24
        private const val BYTE = 0xFF
        private const val FLAT = "flat"
        private const val RADIUS = "radius"
        private const val COLOR = "color"
        private const val ICON_COLOR = "icon-color"
        private const val BLUR = "blur"
        private const val BAR = "bar"
        private const val KEYBOARD = "keyboard"
        private const val KEY_TEXT = "keyText"
        private const val ACCENT_KEY = "accentKey"
        private const val CLIPBOARD_ENTRY = "clipboardEntry"
        private val CLIPBOARD = Selector.Target("clipboard")
        private val PICKER = Selector.Target("picker")
        private val CATEGORIES = Selector.Target("picker", part = "categories")
        private val CATEGORIES_SELECTED = Selector.Target("picker", states = setOf("selected"), part = "categories")
        private val EDITING = Selector.Target("editing")
        private val VOICE = Selector.Target("voice")
        private val MIC = Selector.Target("voice", part = "mic")

        // the keys whose corners the panels' follow: the letters (WindowLooks')
        private val KEY = Selector.Target("key", classes = setOf("letter"))
    }
}
