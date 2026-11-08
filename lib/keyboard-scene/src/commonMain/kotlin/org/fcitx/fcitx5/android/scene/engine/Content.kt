/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Slot
import org.fcitx.fcitx5.android.scene.rules.KeyTextScale
import org.fcitx.fcitx5.android.scene.theme.TextLook
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A mark as placed before any yields ([KeyMarkYield.Mark] of it), and where it would be elsewhere;
 * [top] its box's (a text's by the font's extent, as its TextView stood: the label under it centres to it).
 */
class MarkDrawn(val drawn: Drawn, val slot: Slot, val minScale: Double, val drop: Int, val elsewhere: List<Pair<Slot, Drawn>>, val top: Double)

/**
 * A key's [glyph] (label, icon, badge; null: none) and [marks], before they yield to each other;
 * where it [yields] not, they stay as placed (a table's roots in lines: theme 3.0 measured no ink of
 * a styled text, so nothing gave way to it).
 */
class KeyContent(val glyph: Drawn?, val marks: List<MarkDrawn>, val room: Box, val letter: Boolean, val baseline: Double, val yields: Boolean = true)

/** What a key's content is drawn in, as the theme has it in its normal state: one per part. */
/** A key's parts' looks, each cascaded when asked: most keys have no badge, many no mark. */
class Looks(private val of: (Part) -> TextLook) {
    val label: TextLook get() = of(Part.Label)
    val icon: TextLook get() = of(Part.Icon)
    val badge: TextLook get() = of(Part.Badge)
    val mark: TextLook get() = of(Part.Mark)
    val shortcut: TextLook get() = of(Part.Shortcut)
}

/**
 * Step 5 of §6: a key's label, icon and marks where theme 3.0's KeyView put them (rules B §5), from
 * the platform's [measurer]: a single character centred by its ink, longer text by its advance, both
 * upright by the font's extent (AutoScaleTextView); an icon alone at its own size ([icons]: the
 * drawable's, ImageKeyView's wrap_content) in the middle; a badge 15 dp before the label, 3 dp apart,
 * the two centred; the symbol 10.67 dp bold in its corner, 4 dp in from the face's side, or under the
 * label ([Slot.Below], 2 dp up, the label centred between the face's top and the symbol's, the
 * symbol's by its extent as its TextView's was); a shortcut's icon 11 dp (8 compact), its text 9 dp
 * squeezed to 45% of the key, 3 dp from the side and 2 dp from the top or bottom (2 and 1 compact).
 * Text scales by the compact or floating scale (rules B §6), label and symbol by the theme's too, a
 * shortcut's not. A theme's `hidden` label hides the label, the icon and the symbol, never the badge
 * or the shortcuts (rules B §5); the badge keeps its place beside the label hidden, or beside no
 * label where there is none (an input method with no name: the label's 3 dp still after it).
 * A table's roots (`{root:x}`, TextKeyboard's) are set as theme 3.0 set them: in lines, two in the
 * height of one ([ROOTS_LINES_SCALE] of the size), the whole shrunk to the face less [ROOTS_INSET_DP]
 * a side where it is wider (AutoScaleTextView's Proportional), each line centred.
 */
class Content(private val env: Env, private val measurer: TextMeasurer, private val icons: IconSizes = IconSizes.MATERIAL) {
    private val d = env.density
    private val textScale = KeyTextScale.of(env.compact, env.floating)

    fun of(key: ExpandedKey, cell: Box, content: Box, looks: Looks): KeyContent {
        val compactScale = if (env.compact) key.attrs.compactTextScale ?: 1.0 else 1.0
        val hidden = looks.label.hidden
        val marks = marks(key, cell, content, looks, hidden)
        val below = marks.firstOrNull { it.slot == Slot.Below }
        val measured = key.label?.takeIf { it.isNotEmpty() }?.let { text ->
            val size = (key.attrs.textSize ?: DEFAULT_TEXT_DP) * d * textScale * compactScale * looks.label.scale
            val weight = if (key.attrs.bold == true) BOLD else looks.label.weight
            Measured(text, measurer.measure(text, looks.label.font, weight, size), size, looks.label.font, weight)
        }
        val label = measured?.takeIf { !hidden }
        val roots = (key.attrs.label as? Text.Binding)?.name == ROOT
        val lines = roots && label != null && '\n' in label.text
        val icon = key.icon?.takeIf { !hidden }
        val badge = key.badge
        val cy = if (below != null) (content.top + below.top) / 2 else content.centerY
        val glyph = glyph(badge, measured, label, icon, roots, content, cy)
        val room = content.inset(ROOM_H_DP * d, ROOM_V_DP * d, ROOM_H_DP * d, ROOM_V_DP * d)
        val letter = key.attrs.alt != null && label != null && icon == null && badge == null && !lines
        return KeyContent(glyph, marks, room, letter, glyph?.texts?.firstOrNull()?.baseline ?: 0.0, yields = !lines)
    }

    // the key's one thing in the middle: its badge and label, its label over its icon, its roots, its label, its icon
    private fun glyph(badge: String?, measured: Measured?, label: Measured?, icon: String?, roots: Boolean, content: Box, cy: Double): Drawn? = when {
        badge != null -> badged(badge, measured, label, content.centerX, cy)
        label != null && icon != null -> {
            val iconSize = ICON_WITH_LABEL_DP * d
            val (labelPad, iconPad) = if (env.landscape) LANDSCAPE_PADS else PORTRAIT_PADS
            val t = label.at(content.centerX, content.bottom - labelPad * d - label.box.height / 2, Part.Label)
            val top = content.top + iconPad * d
            val i = PlacedIcon(icon, Box(content.centerX - iconSize / 2, top, content.centerX + iconSize / 2, top + iconSize), Part.Icon)
            Drawn(t.texts, listOf(i), t.ink.union(i.box))
        }
        label != null && roots -> roots(label, content, cy)
        label != null -> label.at(content.centerX, cy, Part.Label)
        icon != null -> {
            val (w, h) = icons.dp(icon).let { (w, h) -> w * d to h * d }
            Drawn.of(PlacedIcon(icon, Box(content.centerX - w / 2, cy - h / 2, content.centerX + w / 2, cy + h / 2), Part.Icon))
        }
        else -> null
    }

    // [m]'s text in lines centred on the face at [cy], shrunk to fit it as a whole
    private fun roots(m: Measured, content: Box, cy: Double): Drawn {
        val lines = m.text.split('\n')
        val first = if (lines.size > 1) m.size * ROOTS_LINES_SCALE else m.size
        val at = lines.map { measurer.measure(it, m.font, m.weight, first) }
        val wide = at.maxOf { it.advance }
        val tall = at.first().let { it.bottom - it.top } * lines.size
        val roomX = content.width - 2 * ROOTS_INSET_DP * d
        val fit = minOf(1.0, if (wide > 0) roomX / wide else 1.0, if (tall > 0) content.height / tall else 1.0)
        val size = if (fit < 1.0 && fit > 0.0) first * fit else first
        val ms = if (size == first) at else lines.map { measurer.measure(it, m.font, m.weight, size) }
        val lineHeight = ms.first().let { it.bottom - it.top }
        val top = cy - lineHeight * lines.size / 2
        val texts = lines.mapIndexed { i, line ->
            PlacedText(line, content.centerX - ms[i].advance / 2, top + i * lineHeight - ms[i].top, size, 1.0, m.font, m.weight, Part.Label)
        }
        val ink = texts.mapIndexed { i, t -> ms[i].ink.moved(t.x, t.baseline) }.reduce(Box::union)
        return Drawn(texts, emptyList(), ink)
    }

    // the badge 15 dp, 3 dp, the label ([measured]'s room kept where it is hidden: [label] null), the two centred at [cx]
    private fun badged(badge: String, measured: Measured?, label: Measured?, cx: Double, cy: Double): Drawn {
        val iconSize = BADGE_DP * d
        val labelWidth = measured?.box?.width ?: 0.0
        val left = cx - (iconSize + BADGE_GAP_DP * d + labelWidth) / 2
        val b = PlacedIcon(badge, Box(left, cy - iconSize / 2, left + iconSize, cy + iconSize / 2), Part.Badge)
        val t = label?.at(left + iconSize + BADGE_GAP_DP * d + labelWidth / 2, cy, Part.Label)
        return if (t == null) Drawn.of(b) else Drawn(t.texts, listOf(b), t.ink.union(b.box))
    }

    private fun marks(key: ExpandedKey, cell: Box, content: Box, looks: Looks, hidden: Boolean): List<MarkDrawn> {
        val m = env.marks
        val name = key.attrs.shortcut
        if (name == null || '/' in name) {
            val symbol = key.alt?.takeIf { m.symbols && key.attrs.mark != false && !hidden }
            val shortcut = name?.let { m.shortcuts[it] }?.takeIf { m.charShortcuts }
            // KeyMarks.place's slots, by what each is: a symbol in the shortcut's slot takes it
            val symbolSlot = symbol?.let { KeyMarks.symbolSlot(m.symbolAt, m.shortcutAt, env.landscape, env.compact) }
            val shortcutSlot = shortcut?.let { KeyMarks.shortcutSlot(KeyMarks.onKeys(m.symbolAt, m.shortcutAt, env.landscape, env.compact).shortcut) }
                ?.takeIf { it != symbolSlot }
            return listOfNotNull(
                shortcutSlot?.let { shortcutMark(shortcut, it, listOfNotNull(symbolSlot), KeyMarkYield.DROP_SHORTCUT, 0, listOf(it), cell, content, looks) },
                symbolSlot?.let { symbolMark(symbol, it, content, looks) },
            )
        }
        val up = m.shortcuts["$name:up"]
        val down = m.shortcuts["$name:down"]
        val placed = KeyMarks.placeFunction(up, down, m.upOn, m.downOn, m.symbolAt, m.shortcutAt, env.landscape, env.compact)
        val slots = placed.at.keys.toList()
        // up's first, down's second where both are (placeFunction's order)
        return placed.at.entries.mapIndexed { i, (slot, mark) ->
            val drop = if (i == 1) KeyMarkYield.DROP_DOWN_SHORTCUT else KeyMarkYield.DROP_SHORTCUT
            shortcutMark(mark, slot, emptyList(), drop, i, slots, cell, content, looks)
        }
    }

    private fun symbolMark(text: String, slot: Slot, content: Box, looks: Looks): MarkDrawn {
        val sizeDp = SYMBOL_DP * textScale * looks.mark.scale
        val size = sizeDp * d
        val measured = Measured(text, measurer.measure(text, looks.mark.font, BOLD, size), size, looks.mark.font, BOLD)
        val vPad = if (slot == Slot.Below) BELOW_DP * d else 0.0
        val box = slotBox(slot, measured.box.width, measured.box.height, content, SYMBOL_SIDE_DP * d, vPad)
        val drawn = measured.inBox(box, Part.Mark)
        return MarkDrawn(drawn, slot, KeyMarkYield.minScale(KeyMarkYield.MIN_SYMBOL_DP, sizeDp), KeyMarkYield.DROP_SYMBOL, emptyList(), box.top)
    }

    // a shortcut's mark, the [index]th of its key's [slots] (KeyMarks.elsewhere tries them together)
    private fun shortcutMark(
        mark: MarkContent, slot: Slot, taken: List<Slot>, drop: Int, index: Int, slots: List<Slot>, cell: Box, content: Box, looks: Looks,
    ): MarkDrawn {
        val compact = env.compact
        // in whole px, as its view's margins were (splitties' dp, rounded down): at 2.75 a compact 1 dp is
        // 2 px, and a ⌫'s two icons on a short face, 39 px apart not 38, both still fit (at their least, 38.5)
        val h = Popups.px(if (compact) SHORTCUT_SIDE_COMPACT_DP else SHORTCUT_SIDE_DP, d)
        val v = Popups.px(if (compact) SHORTCUT_EDGE_COMPACT_DP else SHORTCUT_EDGE_DP, d)
        val (drawnAt, minScale) = when (mark) {
            is MarkContent.Icon -> {
                val dp = if (compact) SHORTCUT_ICON_COMPACT_DP else SHORTCUT_ICON_DP
                val f = { at: Slot -> Drawn.of(PlacedIcon(mark.name, laidOut(slotBox(at, dp * d, dp * d, content, h, v)), Part.Shortcut)) }
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
        val drawn = drawnAt(slot)
        // a shortcut is never under the label (KeyMarks.Slot.Below): its top centres nothing
        return MarkDrawn(drawn, slot, minScale, drop, elsewhere, drawn.ink.top)
    }

    // where a view of [b] was laid out: its corner on the pixel it starts in, its size whole pixels (a
    // shortcut's ImageView, its margins and size in dp): drawn half a pixel off it, its edges rounded the
    // other way, an icon a pixel over
    private fun laidOut(b: Box): Box {
        val x = floor(b.left)
        val y = floor(b.top)
        return Box(x, y, x + floor(b.width + HALF_PX), y + floor(b.height + HALF_PX))
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

    /** Text measured: its [box] as AutoScaleTextView sizes it (a single character's ink across, longer text's advance; the font's extent up), from the pen's start. The popups' text is centred so too ([PopupBuilder]). */
    internal class Measured(val text: String, val m: TextMetrics, val size: Double, val font: String, val weight: Int, val scaleX: Double = 1.0) {
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

        /** A table's roots in two lines: each at this of the size (TextKeyboard's ROOTS_TEXT_SCALE). */
        const val ROOTS_LINES_SCALE = 0.55

        /** The roots kept this far in from the face's sides (TextKeyboard's ROOTS_INSET_DP). */
        const val ROOTS_INSET_DP = 6.0

        private const val ROOT = "root"
        private const val HALF_PX = 0.5

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
