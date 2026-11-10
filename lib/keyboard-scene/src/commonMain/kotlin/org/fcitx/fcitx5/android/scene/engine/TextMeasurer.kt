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

    /**
     * [text] set [letterSpacing] em apart (a theme's `letter-spacing`): as Android's Paint.letterSpacing
     * sets it, half the space before each character and half after, so the advance grows by the space
     * a character and the ink moves right by half of it, its right edge by all but half. A platform
     * that measures it itself (Android's Paint) says so here; 0: [measure] as ever.
     */
    fun measure(text: String, font: String, weight: Int, size: Double, letterSpacing: Double): TextMetrics {
        val m = measure(text, font, weight, size)
        // by code points, not Android's grapheme clusters: a combining mark or ZWJ emoji spaces a little wider here than on a device
        val n = Content.codePoints(text)
        if (letterSpacing == 0.0 || n == 0) return m
        val space = letterSpacing * size
        val ink = m.ink
        return m.copy(advance = m.advance + n * space, ink = Box(ink.left + space / 2, ink.top, ink.right + (n - 0.5) * space, ink.bottom))
    }
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
