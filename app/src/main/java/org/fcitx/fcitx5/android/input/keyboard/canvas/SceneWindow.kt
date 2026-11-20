/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable

/**
 * The window round the keys as the theme has it: the floating frame, its handle, the side buttons, the
 * navigation bar's colour. A look that made no theme has the look of before worked out here.
 */
object SceneWindow {

    const val ONE_HANDED = "one-handed"

    const val SPLIT = "split"

    // null: its look made no theme
    private fun looks(theme: Theme): WindowLooks? =
        SceneThemes.of(theme, ThemeManager.shownLook(theme))?.let { WindowLooks(SceneThemes.cascades(it)) }

    fun placement(oneHanded: Boolean, split: Boolean): Set<String> = setOfNotNull(ONE_HANDED.takeIf { oneHanded }, SPLIT.takeIf { split })

    /** Not what is typed: the window is every board's and field's. */
    fun scenes(context: Context, theme: Theme, floating: Boolean = false, placement: Set<String> = emptySet()): Set<String> =
        scenes(landscape(context), theme, floating, placement)

    fun scenes(landscape: Boolean, theme: Theme, floating: Boolean = false, placement: Set<String> = emptySet()): Set<String> =
        buildSet {
            if (keyboardCompact()) add("compact")
            if (landscape) add("landscape")
            if (floating) add("floating")
            addAll(placement)
            if (theme.isDark) add("dark")
            if (!ThemeManager.shownLook(theme).keys.blocks) add("flat")
        }

    /** It is in `@floating` whatever [scenes] say. */
    fun frame(theme: Theme, scenes: Set<String>): WindowLooks.Frame =
        looks(theme)?.frame(scenes) ?: ThemeManager.shownLook(theme).keys.let { keys ->
            val land = "landscape" in scenes
            // at 紧凑 the rows' margin is less, as the keys' @compact insets
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

    fun handle(theme: Theme, scenes: Set<String>, top: Boolean): WindowLooks.Handle =
        looks(theme)?.handle(scenes, top) ?: plainHandle(theme, top)

    /** A look that made no theme: the app's of before. */
    fun plainHandle(theme: Theme, top: Boolean): WindowLooks.Handle = Argb(theme.altKeyTextColor).let {
        val fill = if (top) WindowLooks.handleColor(Argb(theme.barColor), Argb(theme.keyTextColor)) else null
        WindowLooks.Handle(fill, it, it, WindowLooks.GRIP_WIDTH_DP, WindowLooks.GRIP_HEIGHT_DP)
    }

    fun sideButton(theme: Theme, scenes: Set<String>): WindowLooks.SideButton =
        looks(theme)?.sideButton(scenes) ?: WindowLooks.SideButton(null, Argb(theme.altKeyTextColor), true, 0.0, false, Argb(theme.keyPressHighlightColor))

    /** None set, the ripple on nothing, round past the button as it was. */
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

    /** Null: the keyboard's under it. */
    fun navbar(theme: Theme, scenes: Set<String>): Int? = looks(theme)?.navbar(scenes)?.value

    /** Not its luminance over a half: a mid grey's (#808080, 0.22) white buttons stand out less than black ones. */
    fun darkButtonsOn(color: Int): Boolean {
        val opaque = ColorUtils.setAlphaComponent(color, OPAQUE)
        return ColorUtils.calculateContrast(Color.BLACK, opaque) > ColorUtils.calculateContrast(Color.WHITE, opaque)
    }

    /** Splitties' dp of a whole number: truncated. */
    fun px(context: Context, dp: Double): Float = (dp * context.resources.displayMetrics.density).toInt().toFloat()

    private fun landscape(context: Context) = KeyboardArea.landscape(context)

    private const val OPAQUE = 0xFF
}
