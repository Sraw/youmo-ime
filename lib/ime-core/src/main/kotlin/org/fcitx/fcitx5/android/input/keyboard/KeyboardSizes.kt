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
     * The keys' height asked for, px, out of [screen] px (KeyboardHeightCap cuts it after), on a screen [width] px
     * wide. 横屏's percent is of the short side: a foldable's inner screen and a tablet upright are 横屏 (ScreenShape)
     * and about as high as turned, not the landscape percent of their long side (061-wide-as-landscape §2.2): below API 35
     * [screen] turned is the display less the navigation bar, so upright stands that bar's percent taller.
     */
    fun heightPx(screen: Int, width: Int, landscape: Boolean): Int {
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

    private companion object {
        const val PERCENT = 100.0
    }
}
