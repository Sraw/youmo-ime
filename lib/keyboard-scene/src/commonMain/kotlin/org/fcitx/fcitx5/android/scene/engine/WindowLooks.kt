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
 * The keyboard's window round its keys, which the app makes of views of its own (060-render-v2 §16-8,
 * 060-render-p3 T7a): the floating keyboard's [Frame], its [Handle] (the docked one's too, 始终显示悬浮条),
 * the buttons beside a board at a side ([SideButton]), the navigation bar's colour ([navbar]). What a
 * theme sets is its own; what it does not, the default rule below, which is the look the app worked
 * out itself before (FloatingKeyboard.cornerDp, handleColor; the side buttons' tint), so a theme that
 * sets none of it, theme 3.0's among them, looks as it did. The split's middle (`split-gap`) is the
 * scene's own ([SceneBuilder]).
 *
 * [scenes] are the screen's and the settings' ([Env.scenes] but what is typed: the window is every
 * board's); the floating keyboard's frame is in `@floating` whatever they say.
 */
class WindowLooks(private val cascades: Cascades) {
    private val theme = cascades.theme

    /**
     * The floating keyboard's frame (`frame`): its corners' radius, its border, its shadow. Not set,
     * as round as its keys ([cornerDp]), no border, 6 dp of the system's shadow.
     */
    fun frame(scenes: Set<String>): Frame {
        val at = scenes + FLOATING
        val v = cascades.of(at).of(FRAME)
        val radius = (v[RADIUS] as? Value.OfRadius)?.radius
        val border = (v[BORDER] as? Value.OfBorder)?.border?.let { b ->
            b.color?.let(theme::color)?.takeIf { b.width > 0 && !it.transparent }?.let { Line(b.width, it) }
        }
        val shadow = (v[SHADOW] as? Value.OfShadow)?.shadow
        return Frame(
            radiusDp = radius?.corners?.first() ?: cornerDp(at),
            round = radius?.half == true,
            border = border,
            elevationDp = shadow?.let { if (it.color == null) 0.0 else it.blur } ?: ELEVATION_DP,
            shadowColor = shadow?.color?.let(theme::color),
        )
    }

    /**
     * The frame's radius where the theme sets none, dp: as round as the keys, its curve along the
     * corner keys' (nested round them) so it clips none of them: the keys' radius and the least of
     * their margins (`inset`). Keys square as the user's 按键圆角 (直角) has them, square; keys drawn
     * flat (`@flat`, their faces square whatever the radius) the margin alone. A pill's radius (`50%`)
     * is half a key's height, which no window knows: the margin alone too.
     */
    fun cornerDp(scenes: Set<String>): Double {
        val drawn = KeyLook(cascades.of(scenes).of(KEY), theme)
        val set = KeyLook(cascades.of(scenes - FLAT).of(KEY), theme).radius
        if (!set.half && set.corners.max() == 0.0) return 0.0
        val i = drawn.inset
        val radius = drawn.radius.let { if (it.half) 0.0 else it.corners.max() }
        return radius + minOf(minOf(i.left, i.right), minOf(i.top, i.bottom))
    }

    /**
     * The drag handle (`handle`) at the keyboard's [top] or its bottom: the strip's [Handle.fill], the
     * grip's colour and size, the dock button's icon. Not set: the grip [GRIP_WIDTH_DP] by
     * [GRIP_HEIGHT_DP] of the function keys' text colour, the strip at the top a shade of the bar's
     * ([handleColor]), at the bottom none (the keyboard's own under it).
     */
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

    /**
     * A button in the room beside a board at a side (`side-button`: one-handed, the boards but the
     * letters split): its background, a circle or a rectangle, its icon's colour, the ripple pressed.
     * Not set: no background, the function keys' text colour, the key pressed colour's ripple.
     */
    fun sideButton(scenes: Set<String>): SideButton {
        val v = cascades.of(scenes).of(SIDE_BUTTON)
        val look = KeyLook(v, theme)
        return SideButton(
            fill = fill(v),
            iconColor = color(v[ICON_COLOR]) ?: variable(ALT_KEY_TEXT),
            circle = (v[SHAPE] as? Value.Word)?.word?.let { it == CIRCLE } ?: true,
            radiusDp = look.radius.corners.first(),
            round = look.radius.half,
            pressed = look.overlay ?: variable(KEY_PRESS_HIGHLIGHT),
        )
    }

    /** The navigation bar's colour (`navbar { color }`); null: `follow`, or none set, the keyboard's under it as ever. */
    fun navbar(scenes: Set<String>): Argb? = color(cascades.of(scenes).of(NAVBAR)[COLOR])

    private fun color(v: Value?): Argb? = (v as? Value.OfColor)?.color?.let(theme::color)

    // a solid fill's colour: what a view's background takes (a picture's or an image's is the scene's alone)
    private fun fill(v: Map<String, Value>): Argb? = KeyLook(v, theme).fill?.let { f ->
        (f as? Fill.Solid)?.color?.let(theme::color)
    }

    // one of the colours every theme has (ThemeReader.REQUIRED_COLORS)
    private fun variable(name: String): Argb = theme.colors[name] ?: Argb.TRANSPARENT

    /** The floating keyboard's frame: dp, [round] a pill (`50%`, the radius half its shorter side); [shadowColor] null the system's. */
    data class Frame(val radiusDp: Double, val round: Boolean, val border: Line?, val elevationDp: Double, val shadowColor: Argb?)

    /** A line: its width, dp, and colour. */
    data class Line(val widthDp: Double, val color: Argb)

    /** The handle: the strip's [fill] (null: none drawn), the grip's [color] and size (dp), the dock button's [iconColor]. */
    data class Handle(val fill: Argb?, val color: Argb, val iconColor: Argb, val widthDp: Double, val heightDp: Double)

    /** A side button: its [fill] (null: none), a [circle] or a rectangle [radiusDp] round ([round]: a pill), its icon's colour, the ripple's. */
    data class SideButton(val fill: Argb?, val iconColor: Argb, val circle: Boolean, val radiusDp: Double, val round: Boolean, val pressed: Argb)

    companion object {
        /** The frame's shadow not set: FloatingCorners' elevation of 0.5. */
        const val ELEVATION_DP = 6.0

        /** The grip on the handle not set (FloatingHandle's bar). */
        const val GRIP_WIDTH_DP = 36.0
        const val GRIP_HEIGHT_DP = 4.0

        /** How many tenths of the keys' text colour the handle's strip at the top has over the bar's. */
        const val HANDLE_INK_TENTHS = 1

        /**
         * The handle's strip at the top not set: [base], the bar's, with [ink], the keys' text colour,
         * laid over by [HANDLE_INK_TENTHS], its alpha the bar's (a theme whose bar is the keyboard's colour
         * did not tell the strip from the bar under it). Not `mix()`, which mixes the alpha too. Each
         * channel in whole numbers, its half rounded up: the app's float sums of before to the last value
         * (b + (i − b) × 0.1f is never within a float's error of a half but at one), and the same in JS,
         * whose floats are doubles.
         */
        fun handleColor(base: Argb, ink: Argb): Argb {
            fun mix(b: Int, i: Int) = (TENTHS * b + HANDLE_INK_TENTHS * (i - b) + TENTHS / 2) / TENTHS
            return Argb.of(base.alpha, mix(base.red, ink.red), mix(base.green, ink.green), mix(base.blue, ink.blue))
        }

        private const val TENTHS = 10

        private const val FLOATING = "floating"
        private const val FLAT = "flat"
        private const val CIRCLE = "circle"
        private const val RADIUS = "radius"
        private const val BORDER = "border"
        private const val SHADOW = "shadow"
        private const val SHAPE = "shape"
        private const val COLOR = "color"
        private const val ICON_COLOR = "icon-color"
        private const val WIDTH = "width"
        private const val HEIGHT = "height"
        private const val BAR = "bar"
        private const val KEY_TEXT = "keyText"
        private const val ALT_KEY_TEXT = "altKeyText"
        private const val KEY_PRESS_HIGHLIGHT = "keyPressHighlight"
        private val FRAME = Selector.Target("frame")
        private val HANDLE = Selector.Target("handle")
        private val SIDE_BUTTON = Selector.Target("side-button")
        private val NAVBAR = Selector.Target("navbar")

        // the keys whose corners the frame's follow: the letters, most of them
        private val KEY = Selector.Target("key", classes = setOf("letter"))
    }
}
