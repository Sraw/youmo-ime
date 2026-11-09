/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable

/**
 * The window round the keys as the theme in use has it (060-render-p3 T7a, [WindowLooks]): the floating
 * keyboard's frame, its handle, the buttons beside a board at a side, the navigation bar's colour. A
 * theme that sets none of it, a theme 3.0's among them, has the engine's default rules: the look the
 * app worked out itself before. A look that made no theme (SceneThemes logs it; no key is drawn
 * either) has its colours alone and a square frame.
 */
object SceneWindow {

    // [theme]'s window, of the theme its keys are drawn in (a test's own: SceneThemes.source); null: its look made no theme
    private fun looks(theme: Theme): WindowLooks? =
        SceneThemes.of(ThemeManager.shownLook(theme), theme.name, theme.isDark)?.let { WindowLooks(SceneThemes.cascades(it)) }

    /**
     * The scenes the window is in: the screen's and the settings' (landscape, compact, the look's
     * flat keys, dark), [floating], a board at a side [oneHanded] or [split]. Not what is typed: the
     * window is every board's and field's.
     */
    fun scenes(context: Context, theme: Theme, floating: Boolean = false, oneHanded: Boolean = false, split: Boolean = false): Set<String> =
        buildSet {
            if (keyboardCompact()) add("compact")
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) add("landscape")
            if (floating) add("floating")
            if (split) add("split")
            if (oneHanded) add("one-handed")
            if (theme.isDark) add("dark")
            if (!ThemeManager.shownLook(theme).keys.blocks) add("flat")
        }

    /** The floating keyboard's frame (it is in `@floating` whatever [scenes] say). */
    fun frame(theme: Theme, scenes: Set<String>): WindowLooks.Frame =
        looks(theme)?.frame(scenes) ?: WindowLooks.Frame(0.0, false, null, WindowLooks.ELEVATION_DP, null)

    /** The drag handle at the keyboard's [top] or its bottom. */
    fun handle(theme: Theme, scenes: Set<String>, top: Boolean): WindowLooks.Handle =
        looks(theme)?.handle(scenes, top) ?: theme.altKeyTextColor.let {
            WindowLooks.Handle(null, Argb(it), Argb(it), WindowLooks.GRIP_WIDTH_DP, WindowLooks.GRIP_HEIGHT_DP)
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

    /** [dp] as the app's px of before (splitties' dp of a whole number: truncated). */
    fun px(context: Context, dp: Double): Float = (dp * context.resources.displayMetrics.density).toInt().toFloat()
}
