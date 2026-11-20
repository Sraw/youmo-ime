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
 * The app's own panels over the keyboard, made of views of its own: what a theme sets is its own; what it does not, the
 * look the app had worked out itself, so a theme that sets none of it looks as it did.
 */
class PanelLooks(private val cascades: Cascades) {
    private val theme = cascades.theme

    /** Not set: the `clipboardEntry` colour, half as round as the keys (whatever `@flat`). */
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

    /** Not set: the column the bar's colour and the one shown the keyboard's; their text faded but the one shown. */
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
                color = color(shown[COLOR]) ?: Argb((text.value and RGB) or (OPAQUE shl ALPHA)),
            ),
        )
    }

    /** Not set: the keys' colours, as blocks or, the keys flat, a grid of lines. A fill set is drawn as blocks, whatever the keys. */
    fun editing(scenes: Set<String>): Editing {
        val v = cascades.of(scenes).of(EDITING)
        val radius = (v[RADIUS] as? Value.OfRadius)?.radius
        val keys = keyRadius(scenes)
        return Editing(
            fill = fill(v),
            radiusDp = radius?.corners?.max(),
            round = (radius ?: keys).half,
            keyRadiusDp = keys.corners.max(),
        )
    }

    /** Not set: the keyboard's colour less translucent by [MASK_ALPHA] (VoiceStyle.mask's), the bubble the accent colour. */
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

    /** [Pad.color] null where it sets none: the app's (HandwritingMode.inkColor). */
    fun pad(scenes: Set<String>): Pad {
        val v = cascades.of(scenes).of(PAD)
        return Pad(color(v[INK_COLOR]), (v[INK_WIDTH] as? Value.Number)?.number)
    }

    // `@flat` keys' faces are square whatever 按键圆角 says, but their popups and panels are as round as it
    private fun keyRadius(scenes: Set<String>) = KeyLook(cascades.of(scenes - FLAT).of(KEY), theme).radius

    private fun color(v: Value?): Argb? = (v as? Value.OfColor)?.color?.let(theme::color)

    // a picture's or an image's fill is the scene's alone
    private fun fill(v: Map<String, Value>): Argb? = KeyLook(v, theme).fill?.let { f -> (f as? Fill.Solid)?.color?.let(theme::color) }

    private fun variable(name: String): Argb = theme.colors[name] ?: Argb.TRANSPARENT

    data class Clipboard(val fill: Argb, val radiusDp: Double, val round: Boolean, val color: Argb)

    data class Picker(val fill: Argb?, val categories: Categories, val selected: Categories)

    data class Categories(val fill: Argb?, val color: Argb)

    data class Editing(val fill: Argb?, val radiusDp: Double?, val round: Boolean, val keyRadiusDp: Double)

    data class Pad(val color: Argb?, val widthDp: Double?)

    data class Voice(val fill: Argb, val blurDp: Double?, val micFill: Argb, val micColor: Argb)

    companion object {
        /** PickerSideUi's INACTIVE. */
        const val SIDE_FADE = 153

        /** Rounded as the app's `alpha()`. */
        const val TAB_FADE = 128

        /** VoiceStyle.MASK_ALPHA */
        const val MASK_ALPHA = 0xE0

        /** The overlays' PILL_ALPHA. */
        const val MIC_ALPHA = 0x29

        /** In whole numbers, as VoiceStyle.mask's. */
        fun mask(color: Argb): Argb = Argb((color.value and RGB) or ((color.alpha * MASK_ALPHA / BYTE) shl ALPHA))

        private const val RGB = 0xFFFFFF
        private const val OPAQUE = 0xFF
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
        private val PAD = Selector.Target("key", "handwriting", "pad", null, setOf("letter"), emptySet(), slot = true)
        private const val INK_COLOR = "ink-color"
        private const val INK_WIDTH = "ink-width"
        private val PICKER = Selector.Target("picker")
        private val CATEGORIES = Selector.Target("picker", part = "categories")
        private val CATEGORIES_SELECTED = Selector.Target("picker", states = setOf("selected"), part = "categories")
        private val EDITING = Selector.Target("editing")
        private val VOICE = Selector.Target("voice")
        private val MIC = Selector.Target("voice", part = "mic")

        private val KEY = Selector.Target("key", classes = setOf("letter"))
    }
}
