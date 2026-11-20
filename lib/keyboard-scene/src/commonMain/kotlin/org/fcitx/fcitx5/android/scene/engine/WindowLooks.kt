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
 * The keyboard's window round its keys, which the app makes of views of its own. What a theme does not set is the look
 * the app worked out before, so a theme that sets none of it looks as it did.
 */
class WindowLooks(private val cascades: Cascades) {
    private val theme = cascades.theme

    fun frame(scenes: Set<String>): Frame {
        val at = scenes + FLOATING
        val v = cascades.of(at).of(FRAME)
        val radius = (v[RADIUS] as? Value.OfRadius)?.radius
        val border = (v[BORDER] as? Value.OfBorder)?.border?.let { b ->
            b.color?.let(theme::color)?.takeIf { b.width > 0 && !it.transparent }?.let { Line(b.width, it) }
        }
        val shadow = (v[SHADOW] as? Value.OfShadow)?.shadow
        return Frame(
            radiusDp = radius?.corners?.maxOrNull() ?: cornerDp(at),
            round = radius?.half == true,
            border = border,
            elevationDp = shadow?.let { if (it.color == null) 0.0 else it.blur } ?: ELEVATION_DP,
            shadowColor = shadow?.color?.let(theme::color),
        )
    }

    /** As round as the keys, nested round them so it clips none; a pill's radius is half a key's height, which no window knows. */
    fun cornerDp(scenes: Set<String>): Double {
        val drawn = KeyLook(cascades.of(scenes).of(KEY), theme)
        val set = KeyLook(cascades.of(scenes - FLAT).of(KEY), theme).radius
        if (!set.half && set.corners.max() == 0.0) return 0.0
        val i = drawn.inset
        val radius = drawn.radius.let { if (it.half) 0.0 else it.corners.max() }
        return radius + minOf(minOf(i.left, i.right), minOf(i.top, i.bottom))
    }

    fun handle(scenes: Set<String>, top: Boolean): Handle {
        val v = cascades.of(scenes).of(HANDLE)
        val color = color(v[COLOR]) ?: variable(ALT_KEY_TEXT)
        return Handle(
            fill = fill(v) ?: if (top) handleColor(variable(BAR), variable(KEY_TEXT)) else null,
            color = color,
            iconColor = color(v[ICON_COLOR]) ?: color,
            widthDp = (v[WIDTH] as? Value.Number)?.number ?: GRIP_WIDTH_DP,
            heightDp = (v[HEIGHT] as? Value.Number)?.number ?: GRIP_HEIGHT_DP,
        )
    }

    fun sideButton(scenes: Set<String>): SideButton {
        val cascade = cascades.of(scenes)
        val v = cascade.of(SIDE_BUTTON)
        val look = KeyLook(v, theme)
        return SideButton(
            fill = fill(v),
            iconColor = color(v[ICON_COLOR]) ?: variable(ALT_KEY_TEXT),
            // a key's shape of no use here (path(…), bubble: warned of) is a circle
            circle = (v[SHAPE] as? Value.Word)?.word !in SIDE_BUTTON_RECTS,
            radiusDp = look.radius.corners.first(),
            round = look.radius.half,
            pressed = KeyLook(cascade.of(SIDE_BUTTON_PRESSED), theme).overlay ?: variable(KEY_PRESS_HIGHLIGHT),
        )
    }

    fun navbar(scenes: Set<String>): Argb? = color(cascades.of(scenes).of(NAVBAR)[COLOR])

    private fun color(v: Value?): Argb? = (v as? Value.OfColor)?.color?.let(theme::color)

    // a picture's or an image's fill is the scene's alone
    private fun fill(v: Map<String, Value>): Argb? = KeyLook(v, theme).fill?.let { f ->
        (f as? Fill.Solid)?.color?.let(theme::color)
    }

    private fun variable(name: String): Argb = theme.colors[name] ?: Argb.TRANSPARENT

    data class Frame(val radiusDp: Double, val round: Boolean, val border: Line?, val elevationDp: Double, val shadowColor: Argb?)

    data class Line(val widthDp: Double, val color: Argb)

    data class Handle(val fill: Argb?, val color: Argb, val iconColor: Argb, val widthDp: Double, val heightDp: Double)

    data class SideButton(val fill: Argb?, val iconColor: Argb, val circle: Boolean, val radiusDp: Double, val round: Boolean, val pressed: Argb)

    companion object {
        /** FloatingCorners' elevation of 0.5. */
        const val ELEVATION_DP = 6.0

        /** FloatingHandle's bar. */
        const val GRIP_WIDTH_DP = 36.0
        const val GRIP_HEIGHT_DP = 4.0

        const val HANDLE_INK_TENTHS = 1

        /** Not `mix()`, which mixes the alpha too; each channel rounded half up, matching the app's float sums of before and JS's doubles. */
        fun handleColor(base: Argb, ink: Argb): Argb {
            fun mix(b: Int, i: Int) = (TENTHS * b + HANDLE_INK_TENTHS * (i - b) + TENTHS / 2) / TENTHS
            return Argb.of(base.alpha, mix(base.red, ink.red), mix(base.green, ink.green), mix(base.blue, ink.blue))
        }

        private const val TENTHS = 10

        private const val FLOATING = "floating"
        private const val FLAT = "flat"
        private const val RADIUS = "radius"
        private const val BORDER = "border"
        private const val SHADOW = "shadow"
        private const val SHAPE = "shape"
        private const val COLOR = "color"
        private const val ICON_COLOR = "icon-color"
        private const val WIDTH = "width"
        private const val HEIGHT = "height"
        private const val BAR = "bar"
        private val SIDE_BUTTON_RECTS = setOf("rect", BAR)
        private const val KEY_TEXT = "keyText"
        private const val ALT_KEY_TEXT = "altKeyText"
        private const val KEY_PRESS_HIGHLIGHT = "keyPressHighlight"
        private val FRAME = Selector.Target("frame")
        private val HANDLE = Selector.Target("handle")
        private val SIDE_BUTTON = Selector.Target("side-button")
        private val SIDE_BUTTON_PRESSED = Selector.Target("side-button", states = setOf("pressed"))
        private val NAVBAR = Selector.Target("navbar")

        private val KEY = Selector.Target("key", classes = setOf("letter"))
    }
}
