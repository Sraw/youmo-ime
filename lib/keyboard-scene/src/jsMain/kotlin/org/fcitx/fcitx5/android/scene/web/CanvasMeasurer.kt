/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.web

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.TextMetrics

/**
 * The browser's [TextMeasurer] (060-render-v2 §6 step 5): a 2D context's measureText in the fonts the page has
 * loaded, as the painter draws them ([CanvasPainter.font], [family]): the advance its width, the ink its
 * actualBoundingBox (Paint.getTextBounds'), the font's extent its fontBoundingBox (FontMetrics' top and bottom,
 * near enough: a browser has no yMin/yMax of the font to give). Letter spacing is the engine's arithmetic
 * (TextMeasurer's own), as a canvas's measureText did not measure it before 2023. Kept by what was asked,
 * the last [KEPT].
 */
internal class CanvasMeasurer(private val ctx: dynamic, private val family: (String) -> String) : TextMeasurer {
    private val kept = LinkedHashMap<String, TextMetrics>()

    override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
        val key = "$font\u0000$weight\u0000$size\u0000$text"
        kept[key]?.let { return it }
        ctx.font = CanvasPainter.font(weight, size, family(font))
        ctx.letterSpacing = "0px"
        val m = ctx.measureText(text)
        fun n(v: dynamic, fallback: Double): Double = (v as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: fallback
        val advance = n(m.width, 0.0)
        val metrics = TextMetrics(
            advance,
            Box(-n(m.actualBoundingBoxLeft, 0.0), -n(m.actualBoundingBoxAscent, size * CAP), n(m.actualBoundingBoxRight, advance), n(m.actualBoundingBoxDescent, 0.0)),
            -n(m.fontBoundingBoxAscent, size * TOP),
            n(m.fontBoundingBoxDescent, size * BOTTOM),
        )
        if (kept.size >= KEPT) kept.remove(kept.keys.first())
        kept[key] = metrics
        return metrics
    }

    private companion object {
        const val KEPT = 1024

        // a browser that gives no box (none known now): a Latin font's usual proportions
        const val CAP = 0.7
        const val TOP = 1.05
        const val BOTTOM = 0.3
    }
}
