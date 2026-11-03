/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/**
 * The few choices of the app's theme edit page (the rest is the web page's): the steps the key style
 * settings had before theme file 3.0, so their users find them as they were. A theme whose value is
 * none of them (set on the web page) shows its choice as 「自定义」 ([of] null) till one is picked.
 */
object ThemeEditLevels {

    /** 直角, 圆角, 大圆角: the old 按键圆角. */
    val RADIUS = listOf(0, 4, 10)

    /** 无, 细, 粗. */
    val BORDER = listOf(0, 1, 2)

    /** 紧凑, 标准, 宽松: the old 按键间距, portrait and landscape. */
    val GAP = listOf(Gap(2, 5, 2, 3), Gap(3, 7, 3, 4), Gap(5, 9, 5, 6))

    /** Which of [levels] [value] is; null for none (自定义). */
    fun <T> of(levels: List<T>, value: T): Int? = levels.indexOf(value).takeIf { it >= 0 }

    /**
     * [look] with its keys [blocks] or flat, as the old 按键边框 switch drew them: a flat keyboard's bar
     * in its colour (the keys' base seen through it would be the same colour as the keys'), a block
     * one's showing the base. A bar of the picture is left as it is.
     */
    fun blocks(look: ThemeLook, blocks: Boolean): ThemeLook = look.copy(
        keys = look.keys.copy(blocks = blocks),
        bar = when {
            look.bar is BarBase.Picture -> look.bar
            blocks -> BarBase.Transparent
            else -> BarBase.Color
        },
    )

    fun radius(look: ThemeLook, level: Int) = look.copy(keys = look.keys.copy(radiusDp = RADIUS[level]))

    fun border(look: ThemeLook, level: Int) = look.copy(keys = look.keys.copy(borderDp = BORDER[level]))

    fun gap(look: ThemeLook, level: Int) = look.copy(keys = look.keys.copy(gap = GAP[level]))

    fun press(look: ThemeLook, press: PressEffect) = look.copy(keys = look.keys.copy(press = press))
}
