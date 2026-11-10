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
 * actualBoundingBox (Paint.getTextBounds'). The font's extent (FontMetrics' top and bottom: its head table's
 * yMax and yMin) is the page's own two fonts' ([EXTENTS]) where the family starts with one, as Android's comes
 * from a typeface's first font; else the browser's fontBoundingBox, near enough (a browser gives no yMax).
 * Letter spacing is the engine's arithmetic (TextMeasurer's own), as a canvas's measureText did not measure it
 * before 2023.
 *
 * A text measured before its font is in is the fallback font's: a page awaits `document.fonts.ready` (or loads
 * the faces it uses) before it builds a scene. Such a measure is not kept ([loaded] says whether a font is in),
 * so the scene built once the fonts are in is theirs; the rest are kept, the [limit] last asked for.
 */
internal class CanvasMeasurer(
    private val ctx: dynamic,
    private val family: (String) -> String,
    private val loaded: (String) -> Boolean = ::fontLoaded,
    private val limit: Int = KEPT,
) : TextMeasurer {
    private val kept = LinkedHashMap<String, TextMetrics>()

    override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
        val key = "$font\u0000$weight\u0000$size\u0000$text"
        // the last asked for at the end: the first is the one longest not asked for
        kept.remove(key)?.let {
            kept[key] = it
            return it
        }
        val families = family(font)
        val css = CanvasPainter.font(weight, size, families)
        ctx.font = css
        ctx.letterSpacing = "0px"
        val m = ctx.measureText(text)
        fun n(v: dynamic, fallback: Double): Double = (v as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: fallback
        val advance = n(m.width, 0.0)
        val extent = EXTENTS[families.substringBefore(',').trim()]?.let { if (weight >= BOLD) it[1] else it[0] }
        val metrics = TextMetrics(
            advance,
            Box(-n(m.actualBoundingBoxLeft, 0.0), -n(m.actualBoundingBoxAscent, size * CAP), n(m.actualBoundingBoxRight, advance), n(m.actualBoundingBoxDescent, 0.0)),
            extent?.let { -it.first * size } ?: -n(m.fontBoundingBoxAscent, size * TOP),
            extent?.let { it.second * size } ?: n(m.fontBoundingBoxDescent, size * BOTTOM),
        )
        if (loaded(css)) {
            if (kept.size >= limit) kept.remove(kept.keys.first())
            kept[key] = metrics
        }
        return metrics
    }

    internal companion object {
        const val KEPT = 1024

        // a browser that gives no box (none known now): a Latin font's usual proportions
        const val CAP = 0.7
        const val TOP = 1.05
        const val BOTTOM = 0.3
        const val BOLD = 600

        /**
         * The page's fonts' extents per em, [regular, bold] of [yMax, -yMin] of their head tables: the sources'
         * (Robolectric's Roboto and Noto Sans CJK SC, tools/subset-fonts.py), not the subsets', whose boxes the
         * subsetting made the kept glyphs'. Android's FontMetrics top and bottom of the same fonts.
         */
        val EXTENTS: Map<String, List<Pair<Double, Double>>> = mapOf(
            "\"Youmo Latin\"" to listOf(2163.0 / 2048 to 555.0 / 2048, 2163.0 / 2048 to 555.0 / 2048),
            "\"Youmo Han\"" to listOf(1.808 to 1.048, 1.806 to 1.046),
        )
    }
}

// whether the page's fonts of [font] (a canvas's font) are in: a page's FontFaceSet says; none (node, a worker
// without one) is a yes, a font it cannot read a no
private fun fontLoaded(font: String): Boolean = FONT_CHECK(font) as Boolean

// made once, not a function a measure
private val FONT_CHECK: dynamic = js(
    "(function (f) { var s = typeof document !== 'undefined' ? document.fonts : (typeof self !== 'undefined' ? self.fonts : null); " +
        "if (!s) return true; try { return s.check(f); } catch (e) { return false; } })",
)
