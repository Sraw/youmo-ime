/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Icons
import org.fcitx.fcitx5.android.scene.MarkFate
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Slot
import org.fcitx.fcitx5.android.scene.rules.KeyTextScale
import org.fcitx.fcitx5.android.scene.theme.Shown
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** A mark as placed before any yields; [top] by the font's extent (a label under it centres to it); [way] null: built in. */
class MarkDrawn(
    val drawn: Drawn, val slot: Slot, val drop: Int, val top: Double, val way: UserWay? = null, val text: Boolean = false,
) {
    val user: Boolean get() = way != null
}

class UserWay(val up: Boolean, val fate: MarkFate)

/** The classes glyphs are one size in, by the key's kind (`function`, `enter`, `space`), not what it types. */
enum class GlyphClass { Main, Icon, Label }

/** Where it does not [yields] (a table's roots: theme 3.0 measured no ink of a styled text), they stay as placed. */
class KeyContent(
    val glyph: Drawn?, val marks: List<MarkDrawn>, val room: Box, val glyphClass: GlyphClass?, val baseline: Double, val yields: Boolean = true,
    val edge: KeyMarkYield.Edge = KeyMarkYield.Edge(room.toInk()),
    /** Where a theme-aligned glyph stays and is scaled about: its marks move for it; null: centred. */
    val anchor: Pair<Double, Double>? = null,
    /** A class of its own (a theme's text on the space or Return), so a long one never shrinks the function keys' text. */
    val own: Boolean = false,
)

/** Cascaded only when asked: most keys have no badge, many no mark. */
class Looks(private val of: (Part) -> TextLook) {
    val label: TextLook get() = of(Part.Label)
    val icon: TextLook get() = of(Part.Icon)
    val badge: TextLook get() = of(Part.Badge)
    val mark: TextLook get() = of(Part.Mark)
    val shortcut: TextLook get() = of(Part.Shortcut)
    val hint: TextLook get() = of(Part.Hint)
}

/**
 * A key's label, icon and marks where theme 3.0's KeyView put them, measured by [measurer] (AutoScaleTextView's rules),
 * plus the theme's text look. [themedSign]: the theme draws its own [Icons.SHORT_TEXT], whose ink is unknown: its
 * whole square kept apart.
 */
class Content(
    private val env: Env,
    private val measurer: TextMeasurer,
    private val icons: IconSizes = IconSizes.MATERIAL,
    private val themedSign: Boolean = false,
) {
    private val d = env.density
    private val textScale = KeyTextScale.of(env.compact, env.floating)

    fun of(key: ExpandedKey, cell: Box, content: Box, looks: Looks, face: FaceGeometry? = null, snap: Boolean = true, short: Map<Int, Int> = emptyMap()): KeyContent {
        val compactScale = if (env.compact) key.attrs.compactTextScale ?: 1.0 else 1.0
        val hidden = looks.label.hidden
        val marks = marks(key, cell, content, looks, hidden, snap, short)
        val below = marks.firstOrNull { it.slot == Slot.Below }
        val shows = shows(key, looks)
        val measured = shows.label?.takeIf { it.isNotEmpty() }?.let { text ->
            val size = (shows.textSize ?: DEFAULT_TEXT_DP) * d * textScale * compactScale * looks.label.scale
            measure(text, looks.label, if (key.attrs.bold == true) BOLD else looks.label.weight, size)
        }
        val label = measured?.takeIf { !hidden }
        val roots = (key.attrs.label as? Text.Binding)?.name == ROOT
        val lines = roots && label != null && '\n' in label.text
        val icon = shows.icon?.takeIf { !hidden }
        val badge = key.badge
        val cy = if (below != null) (content.top + below.top) / 2 else content.centerY
        val align = looks.label.align.takeIf { !roots && (label == null || icon == null) } ?: Values.CENTER
        val (glyph, anchor) = glyph(badge, measured, label, icon, roots, Placing(content, cy, align, looks.label, corner(content, face)))
        val room = content.inset(ROOM_H_DP * d, ROOM_V_DP * d, ROOM_H_DP * d, ROOM_V_DP * d)
        val glyphClass = when {
            glyph == null || lines -> null
            icon != null -> GlyphClass.Icon
            badge == null && key.kind !in FUNCTION_KINDS -> GlyphClass.Main
            else -> GlyphClass.Label
        }
        val edge = edge(content, face, snap)
        return KeyContent(
            glyph, marks, room, glyphClass, glyph?.texts?.firstOrNull()?.baseline ?: 0.0, yields = !lines, edge = edge, anchor = anchor,
            own = shows.own && glyphClass != null,
        )
    }

    private fun edge(content: Box, face: FaceGeometry?, snap: Boolean): KeyMarkYield.Edge {
        // the face's corners only where the face is the content's box (a bar's, circle's or path's are not)
        val radii = (face?.pressed as? Shape.Rect)?.takeIf { it.box == content }?.radii ?: Shape.NO_RADII
        val line = face?.border ?: 0.0
        // yields are worked on the face half a px in each side, so what fits there fits the face as drawn
        val within = if (snap) content else content.inset(HALF_PX, HALF_PX, HALF_PX, HALF_PX)
        val half = minOf(within.width, within.height) / 2
        return KeyMarkYield.Edge(within.toInk(), if (snap) radii else radii.map { minOf(it, half) }, MARK_EDGE_DP * d + line, line)
    }

    private class Shows(val label: String?, val icon: String?, val textSize: Double?, val own: Boolean = false)

    /** The icon a key draws in a state in place of [drawn]: its `return-icon-*` first, then `content`. */
    fun icon(key: ExpandedKey, looks: Looks, drawn: String): String {
        val returned = if (returns(key)) RETURN_ACTION[env.texts.returnIcon]?.let { looks.icon.returnIcon(it) } else null
        return when (returned) {
            is Shown.Icon -> returned.name
            null, Shown.ImeName -> looks.icon.content ?: drawn
            else -> drawn
        }
    }

    private fun returns(key: ExpandedKey) = key.attrs.role == ENTER && (key.attrs.icon as? Text.Binding)?.name == RETURN_ICON

    // Return's theme text is as large as the 确定 key's (the layout's size is its letters')
    private fun shows(key: ExpandedKey, looks: Looks): Shows {
        val shown = when {
            key.attrs.role == SPACE -> looks.label.spaceContent
            returns(key) ->
                RETURN_ACTION[env.texts.returnIcon]?.let { looks.icon.returnIcon(it) }
            else -> null
        }
        return when (shown) {
            null, Shown.ImeName -> Shows(key.label, key.icon, key.attrs.textSize)
            Shown.None -> Shows(null, null, null)
            is Shown.Text -> Shows(shown.text, null, if (key.attrs.role == ENTER) RETURN_TEXT_DP else key.attrs.textSize, own = true)
            is Shown.Icon -> Shows(null, shown.name, null)
        }
    }

    private class Placing(val content: Box, val cy: Double, val align: String, val look: TextLook, val corner: Double = 0.0)

    // in from the arc where it crosses its 45° line, so a 50% radius does not cut the glyph off
    private fun corner(content: Box, face: FaceGeometry?): Double {
        val r = (face?.pressed as? Shape.Rect)?.takeIf { it.box == content }?.radii?.get(0) ?: return 0.0
        return minOf(r, minOf(content.width, content.height) / 2) * (1 - sqrt(0.5))
    }

    private fun placed(p: Placing, w: Double, h: Double): Pair<Pair<Double, Double>, Pair<Double, Double>?> = when (p.align) {
        Values.TOP_LEFT -> {
            val left = p.content.left + maxOf(SYMBOL_SIDE_DP * d, p.corner)
            val top = p.content.top + p.corner
            (left + w / 2 to top + h / 2) to (left to top)
        }
        Values.TOP_CENTER -> (p.content.centerX to p.content.top + h / 2) to (p.content.centerX to p.content.top)
        else -> (p.content.centerX to p.cy) to null
    }

    private fun glyph(badge: String?, measured: Measured?, label: Measured?, icon: String?, roots: Boolean, p: Placing): Pair<Drawn?, Pair<Double, Double>?> {
        val content = p.content
        return when {
            badge != null -> {
                val (w, h) = badgedSize(measured, icon?.takeIf { measured == null })
                val (c, anchor) = placed(p, w, h)
                badged(badge, measured, label, icon?.takeIf { measured == null }, c) to anchor
            }
            label != null && icon != null -> {
                val iconSize = ICON_WITH_LABEL_DP * d
                val (labelPad, iconPad) = if (env.landscape) LANDSCAPE_PADS else PORTRAIT_PADS
                val t = label.at(content.centerX, content.bottom - labelPad * d - label.box.height / 2, Part.Label)
                val top = content.top + iconPad * d
                val i = PlacedIcon(icon, Box(content.centerX - iconSize / 2, top, content.centerX + iconSize / 2, top + iconSize), Part.Icon)
                Drawn(t.texts, listOf(i), t.ink.union(i.box)) to null
            }
            label != null && roots -> roots(label, p.look, content, p.cy) to null
            label != null -> {
                val (c, anchor) = placed(p, label.box.width, label.box.height)
                label.at(c.first, c.second, Part.Label) to anchor
            }
            icon != null -> {
                val (w, h) = icons.dp(icon).let { (w, h) -> w * d to h * d }
                val (c, anchor) = placed(p, w, h)
                Drawn.of(PlacedIcon(icon, Box(c.first - w / 2, c.second - h / 2, c.first + w / 2, c.second + h / 2), Part.Icon)) to anchor
            }
            else -> null to null
        }
    }

    private fun roots(m: Measured, look: TextLook, content: Box, cy: Double): Drawn {
        val lines = m.text.split('\n')
        val first = if (lines.size > 1) m.size * ROOTS_LINES_SCALE else m.size
        val at = lines.map { measure(it, look, m.weight, first) }
        val wide = at.maxOf { it.m.advance }
        val tall = at.first().m.let { it.bottom - it.top } * lines.size
        val roomX = maxOf(0.0, content.width - 2 * ROOTS_INSET_DP * d)
        val fit = minOf(1.0, if (wide > 0) roomX / wide else 1.0, if (tall > 0) content.height / tall else 1.0)
        val size = if (fit < 1.0) first * (if (fit > 0.0) fit else ROOTS_MIN_FIT) else first
        val ms = if (size == first) at else lines.map { measure(it, look, m.weight, size) }
        val lineHeight = ms.first().m.let { it.bottom - it.top }
        val top = cy - lineHeight * lines.size / 2
        val drawn = ms.mapIndexed { i, line -> line.from(content.centerX - line.m.advance / 2, top + i * lineHeight - line.m.top, Part.Label) }
        return Drawn(drawn.flatMap { it.texts }, emptyList(), drawn.map { it.ink }.reduce(Box::union))
    }

    private fun badgedSize(measured: Measured?, icon: String?): Pair<Double, Double> {
        val iconSize = BADGE_DP * d
        val (w, h) = measured?.box?.let { it.width to it.height } ?: icon?.let { i -> icons.dp(i).let { (w, h) -> w * d to h * d } } ?: (0.0 to 0.0)
        return iconSize + BADGE_GAP_DP * d + w to maxOf(iconSize, h)
    }

    private fun badged(badge: String, measured: Measured?, label: Measured?, icon: String?, c: Pair<Double, Double>): Drawn {
        val (cx, cy) = c
        val iconSize = BADGE_DP * d
        val (w, h) = icon?.let { i -> icons.dp(i).let { (w, h) -> w * d to h * d } } ?: ((measured?.box?.width ?: 0.0) to 0.0)
        val left = cx - (iconSize + BADGE_GAP_DP * d + w) / 2
        val b = PlacedIcon(badge, Box(left, cy - iconSize / 2, left + iconSize, cy + iconSize / 2), Part.Badge)
        val after = left + iconSize + BADGE_GAP_DP * d
        if (icon != null) {
            val i = PlacedIcon(icon, Box(after, cy - h / 2, after + w, cy + h / 2), Part.Icon)
            return Drawn(emptyList(), listOf(b, i), b.box.union(i.box))
        }
        val t = label?.at(after + w / 2, cy, Part.Label)
        return if (t == null) Drawn.of(b) else Drawn(t.texts, listOf(b), t.ink.union(b.box))
    }

    private fun measure(text: String, look: TextLook, weight: Int, size: Double): Measured = measure(measurer, text, look, weight, size)

    private fun marks(key: ExpandedKey, cell: Box, content: Box, looks: Looks, hidden: Boolean, snap: Boolean, short: Map<Int, Int>): List<MarkDrawn> {
        val name = key.attrs.shortcut
        val char = name == null || !KeyId.of(name).isFunction
        if (!char && looks.hint.hidden) return emptyList()
        val ways = ways(name, char, looks, hidden)
        val slots = if (char) charSlots(ways) else functionSlots(ways)
        // the shortcuts first, the up one before the down one, then the symbols (the old order)
        val sorted = slots.entries.sortedWith(compareBy({ it.value.symbol != null }, { !it.value.up })).map { it.key to it.value }
        // a text of one character drawn as a symbol is no shortcut here
        val firstShortcut = sorted.indices.indexOfFirst { steps(sorted[it].second, short[it]) >= 0 }
        fun drawn(i: Int, slot: Slot, w: Way, room: Double): MarkDrawn {
            var fate: MarkFate? = null
            val steps = steps(w, short[i])
            val m = if (steps < 0) {
                symbolMark(w.symbol ?: (w.shortcut as MarkContent.Text).text, slot, content, looks)
            } else {
                val drop = if (i == firstShortcut) KeyMarkYield.DROP_SHORTCUT else KeyMarkYield.DROP_DOWN_SHORTCUT
                val s = w.shortcut!!
                // the room between the corners may differ by a pixel between the whole-pixel and the loose pass
                val shown = if (s is MarkContent.Text) stepped(s, steps, room, cell, looks) else s
                if (w.user) fate = fateOf(s, shown)
                shortcutMark(shown, slot, drop, cell, content, looks, snap)
            }
            val text = w.shortcut is MarkContent.Text
            return when {
                w.user -> MarkDrawn(m.drawn, m.slot, m.drop, m.top, way = UserWay(w.up, fate ?: MarkFate.AsSet), text = text)
                text -> MarkDrawn(m.drawn, m.slot, m.drop, m.top, text = true)
                else -> m
            }
        }
        // the corners first: else 「你好」 at 顶部 read on into 右上's 「1」 as 「你好1」
        val corners = sorted.mapIndexed { i, (slot, w) -> if (slot.centred) null else drawn(i, slot, w, Double.POSITIVE_INFINITY) }
        return sorted.mapIndexed { i, (slot, w) -> corners[i] ?: drawn(i, slot, w, between(slot, corners.filterNotNull(), content)) }
    }

    // -1: drawn as a symbol. A text of one character looks as a first does (else 九键's 「1」 set on 1 is thinner
    // than 2's 「2」), its first step down the text's own look.
    private fun steps(w: Way, short: Int?): Int {
        val t = w.shortcut as? MarkContent.Text
        return when {
            w.symbol != null -> -1
            t != null && clusterEnds(t.text).size == 1 -> (short ?: 0) - 1
            else -> short ?: 0
        }
    }

    private fun stepped(s: MarkContent.Text, from: Int, room: Double, cell: Box, looks: Looks): MarkContent {
        if (room == Double.POSITIVE_INFINITY) return rung(s, from, looks)
        var steps = from
        while (steps < s.ladder.size && textWidth(rung(s, steps, looks), cell, looks) > room) steps++
        return rung(s, steps, looks)
    }

    private fun fateOf(s: MarkContent, shown: MarkContent): MarkFate = when {
        shown == MarkContent.Text.SIGN -> MarkFate.Sign
        s is MarkContent.Text && shown is MarkContent.Text && shown.text != s.text -> MarkFate.Shortened(shown.text)
        else -> MarkFate.AsSet
    }

    private fun between(slot: Slot, corners: List<MarkDrawn>, content: Box): Double {
        val half = corners.filter { it.slot.bottom == slot.bottom }.minOfOrNull {
            if (it.slot.left) content.centerX - it.drawn.ink.right else it.drawn.ink.left - content.centerX
        } ?: return Double.POSITIVE_INFINITY
        return 2 * (half - MIDDLE_GAP_DP * d)
    }

    private fun textWidth(mark: MarkContent, cell: Box, looks: Looks): Double = when (mark) {
        is MarkContent.Text -> measure(mark.text, looks.shortcut, looks.shortcut.weight, SHORTCUT_TEXT_DP * textScale * d)
            .squeezed(SHORTCUT_TEXT_SHARE * cell.width).box.width
        else -> 0.0
    }

    private fun ways(name: String?, char: Boolean, looks: Looks, hidden: Boolean): List<Way> {
        val m = env.marks
        // `mark="false"` hides the built-in first, not one the user set
        val symbolsShown = m.symbols && !hidden && !looks.mark.hidden
        val shortcutsShown = !char || (m.charShortcuts && !looks.shortcut.hidden)
        return listOf(true, false).mapNotNull { up ->
            if (!(if (up) m.upOn else m.downOn)) return@mapNotNull null
            val user = m.isUser(name, up)
            when (val c = m.of(name, up)) {
                is MarkContent.Symbol -> c.text.takeIf { symbolsShown && it.isNotEmpty() }?.let { Way(up, it, null, user) }
                is MarkContent.Icon, is MarkContent.Text -> c.takeIf { shortcutsShown }?.let { Way(up, null, it, user) }
                MarkContent.Empty -> null
            }
        }
    }

    private fun functionSlots(ways: List<Way>): Map<Slot, Way> {
        val m = env.marks
        return KeyMarks.placeFunction(
            ways.firstOrNull { it.up }, ways.firstOrNull { !it.up }, true, true, m.upAt, m.downAt, env.landscape, env.compact,
        ).at
    }

    // unreachable once the settings are settled (KeyMarks.settled), kept for safety
    private fun charSlots(ways: List<Way>): Map<Slot, Way> {
        val m = env.marks
        val slots = LinkedHashMap<Slot, Way>()
        for (w in ways.sortedBy { if (it.symbol != null) 0 else 1 }) {
            val at = if (w.up) m.upAt else m.downAt
            val other = if (w.up) m.downAt else m.upAt
            val slot = if (w.symbol != null) {
                KeyMarks.symbolSlot(at, other, env.landscape, env.compact)
            } else {
                KeyMarks.shortcutSlot(KeyMarks.onKeys(other, at, env.landscape, env.compact).shortcut)
            }
            if (slot != null && slot !in slots) slots[slot] = w
        }
        return slots
    }

    private class Way(val up: Boolean, val symbol: String?, val shortcut: MarkContent?, val user: Boolean = false)

    private fun symbolMark(text: String, slot: Slot, content: Box, looks: Looks): MarkDrawn {
        val sizeDp = SYMBOL_DP * textScale * looks.mark.scale
        val size = sizeDp * d
        val measured = measure(text, looks.mark, BOLD, size)
        val vPad = if (slot == Slot.Below) BELOW_DP * d else 0.0
        val box = slotBox(slot, measured.box.width, measured.box.height, content, SYMBOL_SIDE_DP * d, vPad)
        val drawn = measured.inBox(box, Part.Mark)
        return MarkDrawn(drawn, slot, KeyMarkYield.DROP_SYMBOL, box.top)
    }

    // a shorter text only where it measures narrower ("hi" is no wider than "h…": the sign next)
    private fun rung(s: MarkContent.Text, steps: Int, looks: Looks): MarkContent {
        fun width(t: String) = measure(t, looks.shortcut, looks.shortcut.weight, SHORTCUT_TEXT_DP * textScale * d).box.width
        return s.ladder.take(steps).fold<MarkContent, MarkContent>(MarkContent.Text(s.text)) { now, t ->
            if (t !is MarkContent.Text || now is MarkContent.Text && width(t.text) < width(now.text)) t else now
        }
    }

    private fun shortcutMark(mark: MarkContent, slot: Slot, drop: Int, cell: Box, content: Box, looks: Looks, snap: Boolean): MarkDrawn {
        val compact = env.compact
        val h = (if (compact) SHORTCUT_SIDE_COMPACT_DP else SHORTCUT_SIDE_DP) * d
        val v = (if (compact) SHORTCUT_EDGE_COMPACT_DP else SHORTCUT_EDGE_DP) * d
        val drawn = when (mark) {
            is MarkContent.Icon -> {
                val dp = if (compact) SHORTCUT_ICON_COMPACT_DP else SHORTCUT_ICON_DP
                val icon = PlacedIcon(mark.name, slotBox(slot, dp * d, dp * d, content, h, v).let { if (snap) laidOut(it) else wholeSized(it) }, Part.Shortcut)
                // the sign's ink is a thin band of its square, so it fits where 「T」 did; a theme's own art may fill its square
                if (mark == MarkContent.Text.SIGN && !themedSign) Drawn(emptyList(), listOf(icon), Icons.SHORT_TEXT_INK.of(icon.box)) else Drawn.of(icon)
            }
            is MarkContent.Text -> {
                val text = mark.text
                val sizeDp = SHORTCUT_TEXT_DP * textScale
                val m = measure(text, looks.shortcut, looks.shortcut.weight, sizeDp * d).squeezed(SHORTCUT_TEXT_SHARE * cell.width)
                m.inBox(slotBox(slot, m.box.width, m.box.height, content, h, v), Part.Shortcut)
            }
            else -> error("not a shortcut's mark: $mark")
        }
        return MarkDrawn(drawn, slot, drop, drawn.ink.top)
    }

    // as a view was laid out: its corner on the pixel it starts in, its size whole pixels
    private fun laidOut(b: Box): Box {
        val x = floor(b.left)
        val y = floor(b.top)
        return Box(x, y, x + floor(b.width + HALF_PX), y + floor(b.height + HALF_PX))
    }

    private fun wholeSized(b: Box): Box = Box(b.left, b.top, b.left + floor(b.width + HALF_PX), b.top + floor(b.height + HALF_PX))

    private fun slotBox(slot: Slot, w: Double, h: Double, content: Box, hPad: Double, vPad: Double): Box {
        val x = when {
            slot.left -> content.left + hPad
            slot.right -> content.right - hPad - w
            else -> content.centerX - w / 2
        }
        val y = if (slot.bottom) content.bottom - vPad - h else content.top + vPad
        return Box(x, y, x + w, y + h)
    }

    internal class Run(val text: String, val font: String, val offset: Double)

    /** Its [box] as AutoScaleTextView sizes it (a single character's ink across, longer text's advance; the font's extent up). */
    internal class Measured(
        val text: String, val m: TextMetrics, val size: Double, val font: String, val weight: Int, val scaleX: Double = 1.0,
        val spacing: Double = 0.0, val runs: List<Run> = listOf(Run(text, font, 0.0)),
    ) {
        val box: Box = if (codePoints(text) == 1) {
            Box(m.ink.left * scaleX, m.top, m.ink.right * scaleX, m.bottom)
        } else {
            Box(0.0, m.top, ceil(m.advance) * scaleX, m.bottom)
        }

        fun squeezed(max: Double): Measured =
            if (box.width <= max || box.width <= 0) this else Measured(text, m, size, font, weight, scaleX * max / box.width, spacing, runs)

        fun at(cx: Double, cy: Double, part: Part): Drawn =
            inBox(Box(cx - box.width / 2, cy - box.height / 2, cx + box.width / 2, cy + box.height / 2), part)

        fun inBox(b: Box, part: Part): Drawn = from(b.left - box.left, b.top - box.top, part)

        fun from(x: Double, baseline: Double, part: Part): Drawn {
            val ink = Box(m.ink.left * scaleX + x, m.ink.top + baseline, m.ink.right * scaleX + x, m.ink.bottom + baseline)
            return Drawn(runs.map { PlacedText(it.text, x + it.offset * scaleX, baseline, size, scaleX, it.font, weight, part, spacing) }, emptyList(), ink)
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

        const val MIDDLE_GAP_DP = 1.0
        const val SHORTCUT_SIDE_DP = 3.0
        const val SHORTCUT_SIDE_COMPACT_DP = 2.0
        const val SHORTCUT_EDGE_DP = 2.0
        const val SHORTCUT_EDGE_COMPACT_DP = 1.0
        const val ROOM_H_DP = 2.0
        const val ROOM_V_DP = 1.0

        /** A quarter dp of the face's colour still around a mark's ink: half a device px or more at any density from 2. */
        const val MARK_EDGE_DP = 0.25

        const val ROOTS_LINES_SCALE = 0.55

        const val ROOTS_INSET_DP = 6.0

        private const val ROOTS_MIN_FIT = 0.1

        private const val ROOT = "root"
        private const val SPACE = "space"
        private const val ENTER = "enter"
        private const val RETURN_ICON = "return-icon"

        const val RETURN_TEXT_DP = 16.0

        private val RETURN_ACTION = mapOf(
            "enter" to "newline", "send" to "send", "search" to "search", "go" to "go", "next" to "next", "done" to "done",
        )

        private val FUNCTION_KINDS = setOf("function", "enter", "space")
        private const val HALF_PX = 0.5

        private val PORTRAIT_PADS = 4.0 to 8.0
        private val LANDSCAPE_PADS = 2.0 to 4.0

        /** Each script's run in its own font (`font-han`, `font-latin`), `letter-spacing` apart. */
        internal fun measure(measurer: TextMeasurer, text: String, look: TextLook, weight: Int, size: Double): Measured {
            val spacing = look.letterSpacing
            val runs = Scripts.runs(text, look.font, look.fontHan, look.fontLatin)
            if (runs.size == 1) {
                val font = runs.single().second
                return Measured(text, measurer.measure(text, font, weight, size, spacing), size, font, weight, spacing = spacing)
            }
            var pen = 0.0
            var ink: Box? = null
            var top = 0.0
            var bottom = 0.0
            val placed = runs.map { (t, font) ->
                val m = measurer.measure(t, font, weight, size, spacing)
                ink = m.ink.moved(pen, 0.0).let { i -> ink?.union(i) ?: i }
                top = minOf(top, m.top)
                bottom = maxOf(bottom, m.bottom)
                Run(t, font, pen).also { pen += m.advance }
            }
            return Measured(text, TextMetrics(pen, ink!!, top, bottom), size, look.font, weight, spacing = spacing, runs = placed)
        }

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
