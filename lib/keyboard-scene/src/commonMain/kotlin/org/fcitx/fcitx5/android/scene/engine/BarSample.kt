/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Point
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape
import kotlin.math.ceil
import kotlin.math.max

/**
 * A candidate bar of fixed sample candidates drawn as a [BarLook] has it (060-render-p3 T6a): what the
 * web shows of a theme's bar, which it has no views to draw. The app draws its own bar with views and
 * does not use this; a test of the app holds its bar to it (each candidate's box and colours).
 *
 * Laid out as the app's bar lays its words at their natural width (候选栏 不铺满): from the left, each
 * candidate its text and [BarLook.padding] each side (null: [Metrics.paddingDp], the app's of 候选字号),
 * at least [Metrics.minWidthDp] wide, then [BarLook.gapDp] and the divider's line; its text in the middle.
 * In px as the app's views have them: each length in whole pixels, a dp length truncated, the text's
 * width rounded up.
 */
object BarSample {
    /** A sample candidate: its [words], the [comment] after them (empty: none). */
    data class Sample(val words: String, val comment: String = "")

    /** The candidates the sample shows: words of one, two and three characters, one with a comment. */
    val SAMPLES = listOf(Sample("幽默"), Sample("输入法"), Sample("有"), Sample("油墨", "yóu mò"), Sample("又"))

    /**
     * The bar's size and what the app sizes a candidate by: [density] px a dp, [textSize] px (候选字号),
     * [paddingDp] each side of a word where the theme sets none, [minWidthDp] its least width, the line
     * between two [dividerDp] wide (at least a pixel) and [dividerInsetDp] short of the bar's top and bottom.
     */
    data class Metrics(
        val width: Double,
        val height: Double,
        val density: Double,
        val textSize: Double,
        val paddingDp: Double,
        val minWidthDp: Double,
        val dividerDp: Double = 1.0,
        val dividerInsetDp: Double = 8.0,
    )

    /** What stands before the words of the candidate at [index] (from 0) in [style]; empty where nothing. */
    fun label(style: BarLook.LabelStyle?, index: Int): String = when (style) {
        BarLook.LabelStyle.Number -> "${index + 1}"
        BarLook.LabelStyle.Dot -> DOT
        BarLook.LabelStyle.None, null -> ""
    }

    /** The dot a candidate's `label-style="dot"` puts before its words. */
    const val DOT = "•"

    /**
     * The bar of [samples] in [look], their text in [font] at [weight] (the app's candidate font where the
     * theme sets none) measured by [measurer]: a key each (`candidate/0`…, kind `candidate`), its fill and its
     * text's runs (`label`, the words, `comment`); pressed its colour over it. The bar's base and the lines
     * between are the scene's [Scene.base].
     */
    fun scene(
        look: BarLook,
        m: Metrics,
        measurer: TextMeasurer,
        samples: List<Sample> = SAMPLES,
        font: String = "system",
        weight: Int = REGULAR,
    ): Scene {
        fun px(dp: Double) = (dp * m.density).toInt().toDouble()
        val base = ArrayList<Op>()
        val whole = Shape.Rect(Box(0.0, 0.0, m.width, m.height))
        when (val b = look.base) {
            is BarLook.Base.Solid -> base += Op.Fill(whole, b.color)
            is BarLook.Base.Picture -> base += Op.Picture(whole, b.blurDp, b.brightness, b.over)
            null -> Unit
        }
        val padLeft = px(look.padding?.left ?: m.paddingDp)
        val padRight = px(look.padding?.right ?: m.paddingDp)
        val divider = max(1.0, px(m.dividerDp))
        val room = px(look.gapDp) + divider
        val keys = ArrayList<KeyNode>()
        var x = 0.0
        for ((i, s) in samples.withIndex()) {
            val item = look.candidate(first = i == 0, selected = false)
            val f = item.font ?: font
            val w = item.weight ?: weight
            val label = label(look.labelStyle, i)
            val runs = listOfNotNull(
                (label + SPACE).takeIf { label.isNotEmpty() }?.let { Triple(it, item.labelColor, LABEL) },
                Triple(s.words, item.color, null),
                s.comment.takeIf { it.isNotEmpty() }?.let { Triple(SPACE + it, item.commentColor, COMMENT) },
            )
            val text = runs.joinToString("") { it.first }
            val metrics = measurer.measure(text, f, w, m.textSize)
            val textWidth = ceil(metrics.advance)
            val width = max(textWidth + padLeft + padRight, px(m.minWidthDp))
            val cell = Box(x, 0.0, x + width, m.height)
            val inner = cell.inset(px(look.inset.left), px(look.inset.top), px(look.inset.right), px(look.inset.bottom))
            val face = Shape.Rect(inner, Faces.radii(look.radius, inner, m.density))
            val ops = ArrayList<Op>()
            item.fill?.let { ops += Op.Fill(face, it) }
            // the text in the middle of the room its padding leaves (FrameLayout's centre)
            var pen = cell.left + padLeft + (width - padLeft - padRight - textWidth) / 2
            val baseline = (m.height - (metrics.bottom - metrics.top)) / 2 - metrics.top
            for ((t, color, part) in runs) {
                ops += Op.Text(t, pen, baseline, f, w, m.textSize, color, part = part)
                pen += measurer.measure(t, f, w, m.textSize).advance
            }
            keys += KeyNode(
                id = "candidate/$i", copy = 0, kind = CANDIDATE, role = null, shape = face, bounds = cell, hit = Shape.Rect(cell),
                anchor = Point((cell.left + cell.right) / 2, m.height / 2), up = Point(0.0, -1.0), states = emptySet(),
                faces = mapOf(emptySet<KeyState>() to ops, setOf(KeyState.Pressed) to ops + Op.Fill(face, item.pressed)),
                a11y = s.words, actions = emptyMap(), label = s.words,
            )
            x = cell.right
            // the line in the middle of the room after it, the last's too (the app's decoration, every item's)
            val at = x + ((room - divider) / 2).toInt()
            look.divider?.let { base += Op.Fill(Shape.Rect(Box(at, px(m.dividerInsetDp), at + divider, m.height - px(m.dividerInsetDp))), it) }
            x += room
        }
        return Scene(m.width, m.height, base, keys, emptyList(), preview = false, popups = false)
    }

    private const val REGULAR = 400
    private const val SPACE = " "
    private const val CANDIDATE = "candidate"
    private const val LABEL = "label"
    private const val COMMENT = "comment"
}
