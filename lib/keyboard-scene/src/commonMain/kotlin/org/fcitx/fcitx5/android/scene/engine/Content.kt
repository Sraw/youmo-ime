/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Slot
import org.fcitx.fcitx5.android.scene.rules.KeyTextScale
import org.fcitx.fcitx5.android.scene.theme.TextLook
import kotlin.math.ceil

/** A mark as placed before any yields ([KeyMarkYield.Mark] of it), and where it would be elsewhere. */
class MarkDrawn(val drawn: Drawn, val slot: Slot, val minScale: Double, val drop: Int, val elsewhere: List<Pair<Slot, Drawn>>)

/** A key's [glyph] (label, icon, badge; null: none) and [marks], before they yield to each other. */
class KeyContent(val glyph: Drawn?, val marks: List<MarkDrawn>, val room: Box, val letter: Boolean, val baseline: Double)

/** What a key's content is drawn in, as the theme has it in its normal state: one per part. */
class Looks(val label: TextLook, val icon: TextLook, val badge: TextLook, val mark: TextLook, val shortcut: TextLook)

/**
 * Step 5 of §6: a key's label, icon and marks where theme 3.0's KeyView put them (rules B §5), from
 * the platform's [measurer]: a single character centred by its ink, longer text by its advance, both
 * upright by the font's extent (AutoScaleTextView); an icon 24 dp in the middle; a badge 15 dp before
 * the label, 3 dp apart, the two centred; the symbol 10.67 dp bold in its corner, 4 dp in from the
 * face's side, or under the label ([Slot.Below], 2 dp up, the label centred above it); a shortcut's
 * icon 11 dp (8 compact), its text 9 dp squeezed to 45% of the key, 3 dp from the side and 2 dp from
 * the top or bottom (2 and 1 compact). Text scales by the compact or floating scale (rules B §6),
 * label and symbol by the theme's too, a shortcut's not.
 */
class Content(private val env: Env, private val measurer: TextMeasurer) {
    private val d = env.density
    private val textScale = KeyTextScale.of(env.compact, env.floating)

    fun of(key: ExpandedKey, cell: Box, content: Box, looks: Looks): KeyContent {
        val compactScale = if (env.compact) key.attrs.compactTextScale ?: 1.0 else 1.0
        val hidden = looks.label.hidden
        val marks = marks(key, cell, content, looks, hidden)
        val below = marks.firstOrNull { it.slot == Slot.Below }
        val label = key.label?.takeIf { it.isNotEmpty() && !hidden }?.let { text ->
            val size = (key.attrs.textSize ?: DEFAULT_TEXT_DP) * d * textScale * compactScale * looks.label.scale
            val weight = if (key.attrs.bold == true) BOLD else looks.label.weight
            Measured(text, measurer.measure(text, looks.label.font, weight, size), size, looks.label.font, weight)
        }
        val icon = key.icon?.takeIf { !hidden }
        val badge = key.badge
        val cy = if (below != null) (content.top + below.drawn.ink.top) / 2 else content.centerY
        val glyph = when {
            label != null && badge != null -> {
                val iconSize = BADGE_DP * d
                val total = iconSize + BADGE_GAP_DP * d + label.box.width
                val left = content.centerX - total / 2
                val b = PlacedIcon(badge, Box(left, cy - iconSize / 2, left + iconSize, cy + iconSize / 2), Part.Badge)
                val t = label.at(left + iconSize + BADGE_GAP_DP * d + label.box.width / 2, cy, Part.Label)
                Drawn(t.texts, listOf(b), t.ink.union(b.box))
            }
            label != null && icon != null -> {
                val iconSize = ICON_WITH_LABEL_DP * d
                val (labelPad, iconPad) = if (env.landscape) LANDSCAPE_PADS else PORTRAIT_PADS
                val t = label.at(content.centerX, content.bottom - labelPad * d - label.box.height / 2, Part.Label)
                val top = content.top + iconPad * d
                val i = PlacedIcon(icon, Box(content.centerX - iconSize / 2, top, content.centerX + iconSize / 2, top + iconSize), Part.Icon)
                Drawn(t.texts, listOf(i), t.ink.union(i.box))
            }
            label != null -> label.at(content.centerX, cy, Part.Label)
            icon != null -> {
                val s = ICON_DP * d
                Drawn.of(PlacedIcon(icon, Box(content.centerX - s / 2, cy - s / 2, content.centerX + s / 2, cy + s / 2), Part.Icon))
            }
            else -> null
        }
        val room = content.inset(ROOM_H_DP * d, ROOM_V_DP * d, ROOM_H_DP * d, ROOM_V_DP * d)
        val letter = key.attrs.alt != null && label != null && icon == null && badge == null
        return KeyContent(glyph, marks, room, letter, glyph?.texts?.firstOrNull()?.baseline ?: 0.0)
    }

    private fun marks(key: ExpandedKey, cell: Box, content: Box, looks: Looks, hidden: Boolean): List<MarkDrawn> {
        val m = env.marks
        val name = key.attrs.shortcut
        if (name == null || '/' in name) {
            val symbol = key.alt?.takeIf { m.symbols && key.attrs.mark != false && !hidden }
            val shortcut = name?.let { m.shortcuts[it] }?.takeIf { m.charShortcuts }
            val placed = KeyMarks.place(symbol, shortcut, m.symbolAt, m.shortcutAt, env.landscape, env.compact)
            val symbolSlot = symbol?.let { placed.slotOf(it) }
            val shortcutSlot = shortcut?.let { placed.at.entries.firstOrNull { e -> e.value === it }?.key }
            return listOfNotNull(
                shortcutSlot?.let { shortcutMark(shortcut, it, listOfNotNull(symbolSlot), KeyMarkYield.DROP_SHORTCUT, 0, listOf(it), cell, content, looks) },
                symbolSlot?.let { symbolMark(symbol, it, content, looks) },
            )
        }
        val up = m.shortcuts["$name:up"]
        val down = m.shortcuts["$name:down"]
        val placed = KeyMarks.placeFunction(up, down, m.upOn, m.downOn, m.symbolAt, m.shortcutAt, env.landscape, env.compact)
        val slots = placed.at.keys.toList()
        return placed.at.entries.mapIndexed { i, (slot, mark) ->
            val drop = if (mark === down && placed.at.size == 2) KeyMarkYield.DROP_DOWN_SHORTCUT else KeyMarkYield.DROP_SHORTCUT
            shortcutMark(mark, slot, emptyList(), drop, i, slots, cell, content, looks)
        }
    }

    private fun symbolMark(text: String, slot: Slot, content: Box, looks: Looks): MarkDrawn {
        val sizeDp = SYMBOL_DP * textScale * looks.mark.scale
        val size = sizeDp * d
        val measured = Measured(text, measurer.measure(text, looks.mark.font, BOLD, size), size, looks.mark.font, BOLD)
        val vPad = if (slot == Slot.Below) BELOW_DP * d else 0.0
        val drawn = measured.inBox(slotBox(slot, measured.box.width, measured.box.height, content, SYMBOL_SIDE_DP * d, vPad), Part.Mark)
        return MarkDrawn(drawn, slot, KeyMarkYield.minScale(KeyMarkYield.MIN_SYMBOL_DP, sizeDp), KeyMarkYield.DROP_SYMBOL, emptyList())
    }

    // a shortcut's mark, the [index]th of its key's [slots] (KeyMarks.elsewhere tries them together)
    private fun shortcutMark(
        mark: MarkContent, slot: Slot, taken: List<Slot>, drop: Int, index: Int, slots: List<Slot>, cell: Box, content: Box, looks: Looks,
    ): MarkDrawn {
        val compact = env.compact
        val h = (if (compact) SHORTCUT_SIDE_COMPACT_DP else SHORTCUT_SIDE_DP) * d
        val v = (if (compact) SHORTCUT_EDGE_COMPACT_DP else SHORTCUT_EDGE_DP) * d
        val (drawnAt, minScale) = when (mark) {
            is MarkContent.Icon -> {
                val dp = if (compact) SHORTCUT_ICON_COMPACT_DP else SHORTCUT_ICON_DP
                val f = { at: Slot -> Drawn.of(PlacedIcon(mark.name, slotBox(at, dp * d, dp * d, content, h, v), Part.Shortcut)) }
                f to KeyMarkYield.minScale(KeyMarkYield.MIN_ICON_DP, dp)
            }
            is MarkContent.Text -> {
                val sizeDp = SHORTCUT_TEXT_DP * textScale
                val m = Measured(mark.text, measurer.measure(mark.text, looks.shortcut.font, looks.shortcut.weight, sizeDp * d), sizeDp * d, looks.shortcut.font, looks.shortcut.weight)
                    .squeezed(SHORTCUT_TEXT_SHARE * cell.width)
                val f = { at: Slot -> m.inBox(slotBox(at, m.box.width, m.box.height, content, h, v), Part.Shortcut) }
                f to KeyMarkYield.minScale(KeyMarkYield.MIN_SYMBOL_DP, sizeDp)
            }
        }
        val elsewhere = KeyMarks.elsewhere(slots, taken).map { alt -> alt[index] to drawnAt(alt[index]) }
        return MarkDrawn(drawnAt(slot), slot, minScale, drop, elsewhere)
    }

    /** A box [w]×[h] in [slot] of [content]: [hPad] in from a side, [vPad] from the top or bottom; centred ones in the middle. */
    private fun slotBox(slot: Slot, w: Double, h: Double, content: Box, hPad: Double, vPad: Double): Box {
        val x = when {
            slot.left -> content.left + hPad
            slot.right -> content.right - hPad - w
            else -> content.centerX - w / 2
        }
        val y = if (slot.bottom) content.bottom - vPad - h else content.top + vPad
        return Box(x, y, x + w, y + h)
    }

    /** Text measured: its [box] as AutoScaleTextView sizes it (a single character's ink across, longer text's advance; the font's extent up), from the pen's start. */
    private class Measured(val text: String, val m: TextMetrics, val size: Double, val font: String, val weight: Int, val scaleX: Double = 1.0) {
        val box: Box = if (codePoints(text) == 1) {
            Box(m.ink.left * scaleX, m.top, m.ink.right * scaleX, m.bottom)
        } else {
            Box(0.0, m.top, ceil(m.advance) * scaleX, m.bottom)
        }

        fun squeezed(max: Double): Measured = if (box.width <= max || box.width <= 0) this else Measured(text, m, size, font, weight, max / box.width)

        /** Drawn with its box centred on ([cx], [cy]). */
        fun at(cx: Double, cy: Double, part: Part): Drawn =
            inBox(Box(cx - box.width / 2, cy - box.height / 2, cx + box.width / 2, cy + box.height / 2), part)

        /** Drawn with its box at [b]. */
        fun inBox(b: Box, part: Part): Drawn {
            val x = b.left - box.left
            val y = b.top - box.top
            val ink = Box(m.ink.left * scaleX + x, m.ink.top + y, m.ink.right * scaleX + x, m.ink.bottom + y)
            return Drawn.of(PlacedText(text, x, y, size, scaleX, font, weight, part), ink)
        }
    }

    companion object {
        const val DEFAULT_TEXT_DP = 23.0
        const val BOLD = 700
        const val ICON_DP = 24.0
        const val ICON_WITH_LABEL_DP = 13.0
        const val BADGE_DP = 15.0
        const val BADGE_GAP_DP = 3.0
        const val SYMBOL_DP = 10.666667
        const val SYMBOL_SIDE_DP = 4.0
        const val BELOW_DP = 2.0
        const val SHORTCUT_ICON_DP = 11.0
        const val SHORTCUT_ICON_COMPACT_DP = 8.0
        const val SHORTCUT_TEXT_DP = 9.0
        const val SHORTCUT_TEXT_SHARE = 0.45
        const val SHORTCUT_SIDE_DP = 3.0
        const val SHORTCUT_SIDE_COMPACT_DP = 2.0
        const val SHORTCUT_EDGE_DP = 2.0
        const val SHORTCUT_EDGE_COMPACT_DP = 1.0
        const val ROOM_H_DP = 2.0
        const val ROOM_V_DP = 1.0

        /** ImageTextKeyView's: the label's and the icon's distance from the bottom and the top, portrait and landscape. */
        private val PORTRAIT_PADS = 4.0 to 8.0
        private val LANDSCAPE_PADS = 2.0 to 4.0

        /** How many code points [s] is: one is centred by its ink. */
        fun codePoints(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
                n++
            }
            return n
        }
    }
}
