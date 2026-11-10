/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.ThemeLook

/**
 * The key style settings (大小与样式 → 按键样式) over a theme's keys: each null follows the theme, else
 * it is used in place of the theme's. The gap is two settings (the user, 2026-11-06): [hGap] the room
 * left and right of a key, [vGap] above and below it, each over its half of the theme's [Gap] alone.
 * The theme itself is not changed: what is exported or saved is its own.
 */
data class KeyStyleChoice(val blocks: Boolean?, val hGap: Spacing?, val vGap: Spacing?, val radiusDp: Int?) {

    /** One direction's room round a key, dp: upright and on its side, as [Gap] has each. */
    data class Spacing(val portrait: Int, val landscape: Int) {
        init {
            require(portrait in 0..Gap.MAX_GAP_DP && landscape in 0..Gap.MAX_GAP_DP) { "spacing out of 0..${Gap.MAX_GAP_DP}" }
        }
    }

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
        val gap = keys.gap
        return look.copy(
            keys = keys.copy(
                blocks = blocks ?: keys.blocks,
                gap = Gap(
                    h = hGap?.portrait ?: gap.h,
                    v = vGap?.portrait ?: gap.v,
                    hLand = hGap?.landscape ?: gap.hLand,
                    vLand = vGap?.landscape ?: gap.vLand,
                ),
                radiusDp = radiusDp ?: keys.radiusDp,
            ),
            bar = if (blocks == false && keys.blocks && look.bar == BarBase.Transparent) BarBase.Color else look.bar,
        )
    }

    companion object {
        val FOLLOW = KeyStyleChoice(null, null, null, null)

        // the levels of 0.5.0's key_spacing_level (and 0.5.2's one key_spacing_choice), a direction each
        val H_COMPACT = Spacing(2, 2)
        val H_STANDARD = Spacing(3, 3)
        val H_LOOSE = Spacing(5, 5)
        val V_COMPACT = Spacing(5, 3)
        val V_STANDARD = Spacing(7, 4)
        val V_LOOSE = Spacing(9, 6)

        // and of key_radius_level
        const val RADIUS_SQUARE = 0
        const val RADIUS_ROUNDED = 4
        const val RADIUS_ROUND = 10
    }
}
