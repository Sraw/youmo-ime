/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.SystemColors

// Ref:
// https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md
// https://www.figma.com/community/file/809865700885504168/material-3-android-15
// https://material-foundation.github.io/material-theme-builder/

/**
 * The system's colours a theme's `system(name, fallback)` takes (ColorExpr.SYSTEM_NAMES): the Monet
 * theme's (monet.xml), and any other's.
 *
 * - Android 14 on: Material's roles (`system_primary_light`…) and the palette's tones.
 * - Android 12 and 13 have no roles: each is the palette's tone 0.5's ThemeMonet took for it there, an
 *   approximation (the comments: the tone the role is); the tones as they are.
 * - Before: none, every colour its fallback (monet.xml's: 0.5's static colours there, MD3 of #769CDF).
 */
object ThemeMonet {

    fun systemColors(context: Context): SystemColors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            val tones = tones(context)
            SystemColors(of(context, ROLES_LIGHT) + tones, of(context, ROLES_DARK) + tones)
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val tones = tones(context)
            SystemColors(of(context, APPROXIMATE_LIGHT) + tones, of(context, APPROXIMATE_DARK) + tones)
        }
        else -> SystemColors.NONE
    }

    private fun of(context: Context, colors: Map<String, Int>): Map<String, Argb> = colors.mapValues { (_, id) -> Argb(context.getColor(id)) }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun tones(context: Context): Map<String, Argb> =
        TONES.flatMap { (group, ids) -> STEPS.zip(ids.toList()) { step, id -> "$group-$step" to Argb(context.getColor(id)) } }.toMap()

    private val ROLES_LIGHT
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        get() = mapOf(
            "surface-container" to android.R.color.system_surface_container_light,
            "surface-container-high" to android.R.color.system_surface_container_high_light,
            "surface-container-highest" to android.R.color.system_surface_container_highest_light,
            "surface-bright" to android.R.color.system_surface_bright_light,
            "on-surface" to android.R.color.system_on_surface_light,
            "primary" to android.R.color.system_primary_light,
            "on-primary" to android.R.color.system_on_primary_light,
            "secondary-container" to android.R.color.system_secondary_container_light,
            "on-surface-variant" to android.R.color.system_on_surface_variant_light,
        )

    private val ROLES_DARK
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        get() = mapOf(
            "surface-container" to android.R.color.system_surface_container_dark,
            "surface-container-high" to android.R.color.system_surface_container_high_dark,
            "surface-container-highest" to android.R.color.system_surface_container_highest_dark,
            "surface-bright" to android.R.color.system_surface_bright_dark,
            "on-surface" to android.R.color.system_on_surface_dark,
            "primary" to android.R.color.system_primary_dark,
            "on-primary" to android.R.color.system_on_primary_dark,
            "secondary-container" to android.R.color.system_secondary_container_dark,
            "on-surface-variant" to android.R.color.system_on_surface_variant_dark,
        )

    // 0.5's: monet.xml's light bar is the highest container, its dark one the high. 0.5 had no light high
    // and no dark highest; every role is here all the same (any theme's system() may name one, and would
    // get its fallback on 12 and 13 alone), each the palette's tone nearest Material's for it between
    // its neighbours: the light high (92) the 90 of neutral1, darker than the container's 95 and of the
    // grey the container is; the dark highest (22) the 20 of neutral2, lighter than the high, of its grey
    private val APPROXIMATE_LIGHT
        @RequiresApi(Build.VERSION_CODES.S)
        get() = mapOf(
            "surface-container" to android.R.color.system_neutral1_50, // neutral94
            "surface-container-high" to android.R.color.system_neutral1_100, // neutral92
            "surface-container-highest" to android.R.color.system_neutral2_100, // neutral92
            "surface-bright" to android.R.color.system_neutral1_10, // neutral98
            "on-surface" to android.R.color.system_neutral1_900,
            "primary" to android.R.color.system_accent1_600,
            "on-primary" to android.R.color.system_accent1_0,
            "secondary-container" to android.R.color.system_accent2_100,
            "on-surface-variant" to android.R.color.system_accent2_700,
        )

    private val APPROXIMATE_DARK
        @RequiresApi(Build.VERSION_CODES.S)
        get() = mapOf(
            "surface-container" to android.R.color.system_neutral1_900, // neutral12
            "surface-container-high" to android.R.color.system_neutral2_1000, // neutral17
            "surface-container-highest" to android.R.color.system_neutral2_800, // neutral22
            "surface-bright" to android.R.color.system_neutral1_800, // neutral24
            "on-surface" to android.R.color.system_neutral1_100,
            "primary" to android.R.color.system_accent1_200,
            "on-primary" to android.R.color.system_accent1_800,
            "secondary-container" to android.R.color.system_accent2_700,
            "on-surface-variant" to android.R.color.system_accent2_200,
        )

    private val STEPS = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

    private val TONES: List<Pair<String, IntArray>>
        @RequiresApi(Build.VERSION_CODES.S)
        get() = listOf(
            "accent1" to intArrayOf(
                android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50,
                android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
                android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600,
                android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
                android.R.color.system_accent1_1000,
            ),
            "accent2" to intArrayOf(
                android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50,
                android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300,
                android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600,
                android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900,
                android.R.color.system_accent2_1000,
            ),
            "accent3" to intArrayOf(
                android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50,
                android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300,
                android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600,
                android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900,
                android.R.color.system_accent3_1000,
            ),
            "neutral1" to intArrayOf(
                android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
                android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
                android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600,
                android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
                android.R.color.system_neutral1_1000,
            ),
            "neutral2" to intArrayOf(
                android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50,
                android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
                android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600,
                android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
                android.R.color.system_neutral2_1000,
            ),
        )
}
