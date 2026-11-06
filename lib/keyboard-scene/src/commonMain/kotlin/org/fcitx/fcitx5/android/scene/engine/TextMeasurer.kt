/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box

/**
 * How big text is, which only a platform knows (its fonts): Android's Paint, the web page's canvas,
 * a test's fixed table (060-render-v2 §6 step 5). Everything else about where text goes is the
 * engine's, the same everywhere.
 */
fun interface TextMeasurer {
    /** [text] in [font] ("system" or a theme's font) at [weight], [size] px. */
    fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics
}

/**
 * [advance] the pen's move; [ink] the glyphs' painted box, from the pen's start on the baseline (y
 * down, so above the baseline is negative); [top] and [bottom] the font's extent (Paint.FontMetrics'
 * top and bottom), the same for any text of that font and size.
 */
data class TextMetrics(val advance: Double, val ink: Box, val top: Double, val bottom: Double)

/**
 * An icon's own size, dp across and down: its drawable's, which an icon key draws it at (rules B §5,
 * ImageKeyView's wrap_content). Only the platform has the drawables; a theme's icon is its file's.
 */
fun interface IconSizes {
    fun dp(name: String): Pair<Double, Double>

    companion object {
        private const val MATERIAL_DP = 24.0

        /** The app's keyboard icons: every one a Material icon's 24 dp (ic_baseline_*_24, ic_capslock_*, ic_number_pad). */
        val MATERIAL = IconSizes { MATERIAL_DP to MATERIAL_DP }
    }
}
