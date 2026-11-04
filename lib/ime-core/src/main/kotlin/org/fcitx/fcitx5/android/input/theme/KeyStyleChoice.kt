/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/**
 * The key style settings (大小与样式 → 按键样式) over a theme's keys: each null follows the theme, else
 * it is used in place of the theme's. The theme itself is not changed: what is exported or saved is its own.
 */
data class KeyStyleChoice(val blocks: Boolean?, val gap: Gap?, val radiusDp: Int?) {

    init {
        require(radiusDp == null || radiusDp in 0..Keys.MAX_RADIUS_DP) { "radiusDp $radiusDp out of 0..${Keys.MAX_RADIUS_DP}" }
    }

    /**
     * [look] with these over its keys; the very [look] when all follow the theme, as what is decoded of a
     * look is kept by the look. A block theme made flat here gets [LegacyLook.applyTo]'s bar: the bar colour
     * for a transparent one; a theme flat already keeps its own, so 无边框 over it draws as following it does.
     */
    fun applyTo(look: ThemeLook): ThemeLook {
        if (this == FOLLOW) return look
        val keys = look.keys
        return look.copy(
            keys = keys.copy(
                blocks = blocks ?: keys.blocks,
                gap = gap ?: keys.gap,
                radiusDp = radiusDp ?: keys.radiusDp,
            ),
            bar = if (blocks == false && keys.blocks && look.bar == BarBase.Transparent) BarBase.Color else look.bar,
        )
    }

    companion object {
        val FOLLOW = KeyStyleChoice(null, null, null)

        // the levels of 0.5.0's key_spacing_level and key_radius_level, as they were
        val GAP_COMPACT = Gap(h = 2, v = 5, hLand = 2, vLand = 3)
        val GAP_STANDARD = Gap(h = 3, v = 7, hLand = 3, vLand = 4)
        val GAP_LOOSE = Gap(h = 5, v = 9, hLand = 5, vLand = 6)
        const val RADIUS_SQUARE = 0
        const val RADIUS_ROUNDED = 4
        const val RADIUS_ROUND = 10
    }
}
