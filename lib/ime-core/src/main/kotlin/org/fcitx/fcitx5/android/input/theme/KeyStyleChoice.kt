/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.engine.KeyStyleOver
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.ThemeLook

/** 按键样式 over a theme's keys: null follows the theme. [hGap]/[vGap] each over its half of the theme's [Gap]. The theme itself is not changed. */
data class KeyStyleChoice(val blocks: Boolean?, val hGap: Spacing?, val vGap: Spacing?, val radiusDp: Int?) {

    /** dp, upright and on its side. */
    data class Spacing(val portrait: Int, val landscape: Int) {
        init {
            require(portrait in 0..Gap.MAX_GAP_DP && landscape in 0..Gap.MAX_GAP_DP) { "spacing out of 0..${Gap.MAX_GAP_DP}" }
        }
    }

    init {
        require(radiusDp == null || radiusDp in 0..Keys.MAX_RADIUS_DP) { "radiusDp $radiusDp out of 0..${Keys.MAX_RADIUS_DP}" }
    }

    /** The very [look] when all follow, so its decoded state is kept. A block theme made flat gets [LegacyLook.applyTo]'s bar; a flat one keeps its own. */
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

    /** Laid on each key after a 4.0 pack's cascade; null when all follow the pack. 有边框 is the pack's own faces. */
    val overPack: KeyStyleOver?
        get() = KeyStyleOver(
            across = hGap?.let { KeyStyleOver.Level(it.portrait, it.landscape) },
            upDown = vGap?.let { KeyStyleOver.Level(it.portrait, it.landscape) },
            radiusDp = radiusDp?.toDouble(),
            flat = blocks == false,
        ).takeUnless { it.none }

    companion object {
        val FOLLOW = KeyStyleChoice(null, null, null, null)

        // 0.5's key_spacing_level (and 0.5.2's key_spacing_choice) levels, a direction each
        val H_COMPACT = Spacing(2, 2)
        val H_STANDARD = Spacing(3, 3)
        val H_LOOSE = Spacing(5, 5)

        // up and down only: as tight as H_COMPACT
        val V_VERY_COMPACT = Spacing(2, 2)
        val V_COMPACT = Spacing(5, 3)
        val V_STANDARD = Spacing(7, 4)
        val V_LOOSE = Spacing(9, 6)

        // and of key_radius_level
        const val RADIUS_SQUARE = 0
        const val RADIUS_ROUNDED = 4
        const val RADIUS_ROUND = 10
    }
}
