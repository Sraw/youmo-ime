/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes
import org.fcitx.fcitx5.android.scene.theme.Twin
import kotlin.math.roundToInt

/** 键盘高度, 两侧边距, 底部边距: at 跟随主题 the theme's where it gives one, else the setting's numbers; [KeyboardHeightCap] comes after. */
data class KeyboardSizes(
    val height: Setting,
    val sidePadding: Setting,
    val bottomPadding: Setting,
    /** 贴底: no room for the navigation bar. */
    val bottomFlush: Boolean,
    /** None: nothing. */
    val theme: ThemeSizes = ThemeSizes(),
) {
    /** % for the height, dp for a padding. */
    data class Setting(val follow: Boolean, val portrait: Int, val landscape: Int)

    // the theme's where it is followed and gives one
    private fun themed(setting: Setting, theme: Twin?): Twin? = theme?.takeIf { setting.follow }

    /** Upright, a percent of [screen]; turned, [LANDSCAPE_ROWS] rows of the level's row dp (no screen share, so keys match across heights and Android 14/15's bars). */
    fun heightPx(screen: Int, landscape: Boolean, density: Float, large: Boolean): Int {
        if (!landscape) return percentHeightPx(screen, screen, landscape = false)
        val level = themed(height, theme.height)?.landscape ?: height.landscape.toDouble()
        return (LANDSCAPE_ROWS * KeyboardHeightLevels.landscapeRowDp(level, large) * density).toInt()
    }

    /** As 0.6.0 had it, turned of the short side: a floating keyboard keeps its 0.6.0 size and takes nothing of the app's. */
    fun percentHeightPx(screen: Int, width: Int, landscape: Boolean): Int {
        val base = if (landscape) minOf(screen, width) else screen
        return themed(height, theme.height)?.let { (base * it.of(landscape) / PERCENT).toInt() }
            ?: (base * (if (landscape) height.landscape else height.portrait) / PERCENT.toInt())
    }

    /** dp. */
    fun sidePaddingDp(landscape: Boolean): Double = dp(sidePadding, theme.sidePadding, landscape)

    /** dp. */
    fun bottomPaddingDp(landscape: Boolean): Double = dp(bottomPadding, theme.bottomPadding, landscape)

    /** A theme's bottom padding followed is over the navigation bar's room, not instead of it. */
    val flush: Boolean get() = bottomFlush && themed(bottomPadding, theme.bottomPadding) == null

    /** By the height followed, the theme's percentages rounded as a setting's; one answer for both orientations. */
    val compact: Boolean
        get() {
            val t = themed(height, theme.height) ?: return KeyboardHeightLevels.isCompact(height.portrait, height.landscape)
            return KeyboardHeightLevels.isCompact(t.portrait.roundToInt(), t.landscape.roundToInt())
        }

    private fun dp(setting: Setting, theme: Twin?, landscape: Boolean): Double =
        themed(setting, theme)?.of(landscape) ?: (if (landscape) setting.landscape else setting.portrait).toDouble()

    companion object {
        private const val PERCENT = 100.0

        /** A layout of more rows has them shorter, as upright. */
        const val LANDSCAPE_ROWS = 4
    }
}
