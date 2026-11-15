/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes
import org.fcitx.fcitx5.android.scene.theme.Twin
import kotlin.math.roundToInt

/**
 * The docked keyboard's size (键盘高度, 两侧边距, 底部边距) as the settings and the theme make it
 * (060-render-v2 §15, 主题管尺寸): a setting at 跟随主题 ([Setting.follow]) takes the theme's where
 * the theme gives one, else the setting's own numbers, which 跟随主题 has put back to the defaults
 * (today's look). [KeyboardHeightCap] still comes after, and floating, one-handed and split are
 * worked out from what this gives, as from the settings before.
 */
data class KeyboardSizes(
    val height: Setting,
    val sidePadding: Setting,
    val bottomPadding: Setting,
    /** 贴底 ([Setting] of the bottom padding's level): no room for the navigation bar. */
    val bottomFlush: Boolean,
    /** What the theme sets; none: nothing. */
    val theme: ThemeSizes = ThemeSizes(),
) {
    /** One of the three: following the theme or not, and its numbers upright and turned (% for the height, dp for a padding). */
    data class Setting(val follow: Boolean, val portrait: Int, val landscape: Int)

    // the theme's where it is followed and gives one
    private fun themed(setting: Setting, theme: Twin?): Twin? = theme?.takeIf { setting.follow }

    /**
     * The keys' height asked for, px (KeyboardHeightCap cuts it after). Upright, the level's percent of [screen] px.
     * Turned (横屏: a foldable's inner screen and a tablet upright too, ScreenShape), [LANDSCAPE_ROWS] rows of the
     * level's row, dp ([KeyboardHeightLevels.landscapeRowDp]; the theme's landscape `height` read the same way), [large]
     * where the display's short side is [KeyboardHeightLevels.LARGE_SHORT_SIDE_DP] or more: no share of the screen, so a
     * key is as tall on a phone of any height and the same on Android 14 and 15, whose screens' heights differ by the bar
     * (061-landscape-height §5.1). [density] px a dp.
     */
    fun heightPx(screen: Int, landscape: Boolean, density: Float, large: Boolean): Int {
        if (!landscape) return percentHeightPx(screen, screen, landscape = false)
        val level = themed(height, theme.height)?.landscape ?: height.landscape.toDouble()
        return (LANDSCAPE_ROWS * KeyboardHeightLevels.landscapeRowDp(level, large) * density).toInt()
    }

    /**
     * The keys' height as 0.6.0 had it, px: the level's percent of [screen], turned of the short side of [screen] and
     * [width] (061-wide-as-landscape §2.2). What a floating keyboard is made from turned as upright: it takes nothing
     * of the app's, and keeps its size from 0.6.0 (061-landscape-height §5.4).
     */
    fun percentHeightPx(screen: Int, width: Int, landscape: Boolean): Int {
        val base = if (landscape) minOf(screen, width) else screen
        return themed(height, theme.height)?.let { (base * it.of(landscape) / PERCENT).toInt() }
            ?: (base * (if (landscape) height.landscape else height.portrait) / PERCENT.toInt())
    }

    /** The padding each side, dp. */
    fun sidePaddingDp(landscape: Boolean): Double = dp(sidePadding, theme.sidePadding, landscape)

    /** The padding under the keys, dp. */
    fun bottomPaddingDp(landscape: Boolean): Double = dp(bottomPadding, theme.bottomPadding, landscape)

    /** 贴底: a theme's bottom padding followed is a padding over the navigation bar's room, not instead of it. */
    val flush: Boolean get() = bottomFlush && themed(bottomPadding, theme.bottomPadding) == null

    /**
     * 紧凑 ([KeyboardHeightLevels.isCompact]): by the height followed, the theme's percentages rounded
     * as a setting's would be; one answer for both orientations, as the setting is one.
     */
    val compact: Boolean
        get() {
            val t = themed(height, theme.height) ?: return KeyboardHeightLevels.isCompact(height.portrait, height.landscape)
            return KeyboardHeightLevels.isCompact(t.portrait.roundToInt(), t.landscape.roundToInt())
        }

    private fun dp(setting: Setting, theme: Twin?, landscape: Boolean): Double =
        themed(setting, theme)?.of(landscape) ?: (if (landscape) setting.landscape else setting.portrait).toDouble()

    companion object {
        private const val PERCENT = 100.0

        /** The rows 横屏's height is of: a layout of more rows has them shorter, as upright (§5.1). */
        const val LANDSCAPE_ROWS = 4
    }
}
