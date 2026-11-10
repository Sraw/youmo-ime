/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable

/**
 * The window round the keys as the theme in use has it (060-render-p3 T7a, [WindowLooks]): the floating
 * keyboard's frame, its handle, the buttons beside a board at a side, the navigation bar's colour. A
 * theme that sets none of it, a theme 3.0's among them, has the engine's default rules: the look the
 * app worked out itself before. A look that made no theme (SceneThemes logs it; no key is drawn
 * either) has that look of before worked out here, of its keys and colours.
 */
object SceneWindow {

    /** The board at a side, one-handed: the theme's `@one-handed` scene. */
    const val ONE_HANDED = "one-handed"

    /** The letters split: the theme's `@split` scene. */
    const val SPLIT = "split"

    // [theme]'s window, of the theme its keys are drawn in (a test's own: SceneThemes.source); null: its look made no theme
    private fun looks(theme: Theme): WindowLooks? =
        SceneThemes.of(theme, ThemeManager.shownLook(theme))?.let { WindowLooks(SceneThemes.cascades(it)) }

    /** Where the board is placed as the theme's scenes: [oneHanded] at a side, the letters [split]. */
    fun placement(oneHanded: Boolean, split: Boolean): Set<String> = setOfNotNull(ONE_HANDED.takeIf { oneHanded }, SPLIT.takeIf { split })

    /**
     * The scenes the window is in: the screen's and the settings' (landscape, compact, the look's
     * flat keys, dark), [floating], the board's [placement] (its [ONE_HANDED], [SPLIT]). Not what is
     * typed: the window is every board's and field's.
     */
    fun scenes(context: Context, theme: Theme, floating: Boolean = false, placement: Set<String> = emptySet()): Set<String> =
        buildSet {
            if (keyboardCompact()) add("compact")
            if (landscape(context)) add("landscape")
            if (floating) add("floating")
            addAll(placement)
            if (theme.isDark) add("dark")
            if (!ThemeManager.shownLook(theme).keys.blocks) add("flat")
        }

    /** The floating keyboard's frame (it is in `@floating` whatever [scenes] say). */
    fun frame(theme: Theme, scenes: Set<String>): WindowLooks.Frame =
        looks(theme)?.frame(scenes) ?: ThemeManager.shownLook(theme).keys.let { keys ->
            val land = "landscape" in scenes
            // at 紧凑 the rows' margin is less, as the keys' @compact insets (Gap.vCompact)
            val compact = "compact" in scenes
            val v = when {
                land && compact -> keys.gap.vLandCompact
                land -> keys.gap.vLand
                compact -> keys.gap.vCompact
                else -> keys.gap.v
            }
            val radius = FloatingKeyboard.cornerDp(keys.radiusDp, if (land) keys.gap.hLand else keys.gap.h, v, keys.blocks)
            WindowLooks.Frame(radius.toDouble(), false, null, WindowLooks.ELEVATION_DP, null)
        }

    /** The drag handle at the keyboard's [top] or its bottom. */
    fun handle(theme: Theme, scenes: Set<String>, top: Boolean): WindowLooks.Handle =
        looks(theme)?.handle(scenes, top) ?: plainHandle(theme, top)

    /** The handle of [theme]'s colours alone, no theme 4.0 of it (a look that made none): the app's of before. */
    fun plainHandle(theme: Theme, top: Boolean): WindowLooks.Handle = Argb(theme.altKeyTextColor).let {
        val fill = if (top) WindowLooks.handleColor(Argb(theme.barColor), Argb(theme.keyTextColor)) else null
        WindowLooks.Handle(fill, it, it, WindowLooks.GRIP_WIDTH_DP, WindowLooks.GRIP_HEIGHT_DP)
    }

    /** A button beside a board at a side. */
    fun sideButton(theme: Theme, scenes: Set<String>): WindowLooks.SideButton =
        looks(theme)?.sideButton(scenes) ?: WindowLooks.SideButton(null, Argb(theme.altKeyTextColor), true, 0.0, false, Argb(theme.keyPressHighlightColor))

    /**
     * A side button's background, [size] px square: none set, the pressed colour's ripple on nothing, round
     * past the button as it was; else its fill, a circle or a rectangle round as it says, the ripple within it.
     */
    fun sideButtonBackground(context: Context, look: WindowLooks.SideButton, size: Int): Drawable {
        val fill = look.fill ?: return borderlessRippleDrawable(look.pressed.value)
        val shape = GradientDrawable().apply {
            if (look.circle) {
                shape = GradientDrawable.OVAL
            } else {
                cornerRadius = if (look.round) size / 2f else px(context, look.radiusDp)
            }
            setColor(fill.value)
        }
        return RippleDrawable(ColorStateList.valueOf(look.pressed.value), shape, null)
    }

    /** The navigation bar's colour; null: the keyboard's under it, as ever. */
    fun navbar(theme: Theme, scenes: Set<String>): Int? = looks(theme)?.navbar(scenes)?.value

    /**
     * Whether the navigation bar's buttons are drawn dark on [color] (its alpha aside): the one of black
     * and white that stands out more. Not its luminance over a half: a mid grey's (#808080, 0.22) white
     * buttons stand out less than black ones.
     */
    fun darkButtonsOn(color: Int): Boolean {
        val opaque = ColorUtils.setAlphaComponent(color, OPAQUE)
        return ColorUtils.calculateContrast(Color.BLACK, opaque) > ColorUtils.calculateContrast(Color.WHITE, opaque)
    }

    /** [dp] as the app's px of before (splitties' dp of a whole number: truncated). */
    fun px(context: Context, dp: Double): Float = (dp * context.resources.displayMetrics.density).toInt().toFloat()

    private fun landscape(context: Context) = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private const val OPAQUE = 0xFF
}
