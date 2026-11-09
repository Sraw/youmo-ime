/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box

/**
 * A font of fixed sizes, the same on every platform (060-render-v2 §6 step 5's fake): a Latin
 * character 0.6 of the size across, a wide (CJK) one 1.0, bold 5% wider; ink from 0.05 of the size
 * in from each side, cap height 0.7, descenders 0.2; the font's extent 1.05 above, 0.3 below.
 *
 * The engine's tests' (their snapshots are of it), and what the theme maker's library measures by where
 * its caller has no canvas (node: the library's own tests, which hold its scenes to those snapshots and
 * its drawing to the app's): one measurer, so the three draw the same scenes.
 */
object FixedMeasurer : TextMeasurer {
    override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
        val bold = if (weight >= BOLD) BOLD_WIDER else 1.0
        val advance = text.sumOf { (if (it.code >= WIDE_FROM) WIDE else NARROW) * size * bold }
        val descends = text.any { it in "gjpqy,;" }
        val ink = Box(size * SIDE, -size * CAP, maxOf(size * SIDE, advance - size * SIDE), if (descends) size * DESCENT else 0.0)
        return TextMetrics(advance, ink, -size * TOP, size * BOTTOM)
    }

    private const val BOLD = 700
    private const val BOLD_WIDER = 1.05
    private const val WIDE_FROM = 0x2E80
    private const val WIDE = 1.0
    private const val NARROW = 0.6
    private const val SIDE = 0.05
    private const val CAP = 0.7
    private const val DESCENT = 0.2
    private const val TOP = 1.05
    private const val BOTTOM = 0.3
}
