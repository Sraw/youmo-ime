/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Shape
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

/**
 * A mark as placed before any yields ([KeyMarkYield.Mark] of it); [top] its box's (a text's by the
 * font's extent, as its TextView stood: the label under it centres to it).
 */
class MarkDrawn(val drawn: Drawn, val slot: Slot, val drop: Int, val top: Double)

/**
 * The classes a keyboard's glyphs are one size in (060-render-p2 S4e, KeyMarkYield.group): [Main], what
 * a key that is no function key's types (a letter, a 注音 symbol, a 九键 group, a digit, a punctuation);
 * [Icon], a key's icon (⌫ ⇧ ⏎ 🌐, an icon over a label); [Label], a function key's text (123, 符, 分词,
 * the , . beside the space, a number pad's + - * /, the space's name, a badge and its name). By the
 * key's kind (`function`, `enter`, `space`), not what it types: a layout's own keys fall in by theirs.
 */
enum class GlyphClass { Main, Icon, Label }

/**
 * A key's [glyph] (label, icon, badge; null: none) and [marks], before they yield to each other;
 * where it [yields] not, they stay as placed (a table's roots in lines: theme 3.0 measured no ink of
 * a styled text, so nothing gave way to it).
 */
class KeyContent(
    val glyph: Drawn?, val marks: List<MarkDrawn>, val room: Box, val glyphClass: GlyphClass?, val baseline: Double, val yields: Boolean = true,
    /** How far out the marks go, their insets eaten, where nothing else keeps them all ([MARK_EDGE_DP] in from the face's sides, arcs and border). */
    val edge: KeyMarkYield.Edge = KeyMarkYield.Edge(room.toInk()),
    /**
     * Where a glyph a theme aligns (`align`, not centred) stays and is made smaller about: it never moves
     * for its marks, they move for it, as a main character does on its row's baseline; null: centred.
     */
    val anchor: Pair<Double, Double>? = null,
    /**
     * Its glyph a class of its own (a theme's text on the space or Return, `space-content`, `return-icon-*`):
     * sized by itself, so a long one is smaller alone, never the function keys' text with it.
     */
    val own: Boolean = false,
)

/** What a key's content is drawn in, as the theme has it in its normal state: one per part. */
/** A key's parts' looks, each cascaded when asked: most keys have no badge, many no mark. */
class Looks(private val of: (Part) -> TextLook) {
    val label: TextLook get() = of(Part.Label)
    val icon: TextLook get() = of(Part.Icon)
    val badge: TextLook get() = of(Part.Badge)
    val mark: TextLook get() = of(Part.Mark)
    val shortcut: TextLook get() = of(Part.Shortcut)
    val hint: TextLook get() = of(Part.Hint)
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
 *
 * A theme's text look (060-render-p3 T4a): `align` puts the glyph (a label, a badge and its label, an
 * icon alone) at the face's top left ([SYMBOL_SIDE_DP] in, as the symbol in its corner) or its top
 * instead of its middle, by the same box; `letter-spacing` sets characters apart, measured; `font-han`
 * and `font-latin` set each script's run in its own font ([Scripts]), measured run by run;
 * `space-content` and `return-icon-<action>` give the space and Return a text, an icon or nothing of
 * the theme's; `key mark { hidden }` leaves the symbol out, `key hint { hidden }` a function key's
 * swipe marks.
 */
class Content(private val env: Env, private val measurer: TextMeasurer, private val icons: IconSizes = IconSizes.MATERIAL) {
    private val d = env.density
    private val textScale = KeyTextScale.of(env.compact, env.floating)

    fun of(key: ExpandedKey, cell: Box, content: Box, looks: Looks, face: FaceGeometry? = null, snap: Boolean = true): KeyContent {
        val compactScale = if (env.compact) key.attrs.compactTextScale ?: 1.0 else 1.0
        val hidden = looks.label.hidden
        val marks = marks(key, cell, content, looks, hidden, snap)
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
        val (glyph, anchor) = glyph(badge, measured, label, icon, roots, Placing(content, cy, align, looks.label))
        val room = content.inset(ROOM_H_DP * d, ROOM_V_DP * d, ROOM_H_DP * d, ROOM_V_DP * d)
        // by the key's kind (a layout's or a theme's own keys fall in by theirs) and what it shows: a function
        // key's text (符, 分词, the , . beside the space, a number pad's + -) is a function key's, whatever it types
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
        // the face's corners where it is what the content is in (a bar's or a circle's are not: its box's then)
        val radii = face?.pressed?.takeIf { it.box == content }?.radii ?: Shape.NO_RADII
        val line = face?.border ?: 0.0
        // where the yields are worked out (not [snap]): the face half a px in each side, the most a whole
        // pixel's rounding takes off it, so what fits there fits the face as drawn
        val within = if (snap) content else content.inset(HALF_PX, HALF_PX, HALF_PX, HALF_PX)
        val half = minOf(within.width, within.height) / 2
        return KeyMarkYield.Edge(within.toInk(), if (snap) radii else radii.map { minOf(it, half) }, MARK_EDGE_DP * d + line, line)
    }

    /** What a key shows: the layout's label and icon, or the theme's for the space or Return; [textSize] dp, null: the default. */
    private class Shows(val label: String?, val icon: String?, val textSize: Double?, val own: Boolean = false)

    // the space's `space-content` (a text at the space's size); Return's `return-icon-<action>` where it shows the
    // action's icon ({return-icon}), a text as large as the 确定 key's (16 dp: the layout's size is its letters')
    private fun shows(key: ExpandedKey, looks: Looks): Shows {
        val shown = when {
            key.attrs.role == SPACE -> looks.label.spaceContent
            key.attrs.role == ENTER && (key.attrs.icon as? Text.Binding)?.name == RETURN_ICON ->
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

    /** Where a key's glyph goes: centred at ([content]'s middle, [cy]), or as [align] has it in [content]. */
    private class Placing(val content: Box, val cy: Double, val align: String, val look: TextLook)

    // the middle of a box [w]×[h] placed as [p] has it, and where it stays (an aligned glyph's corner or top's middle)
    private fun placed(p: Placing, w: Double, h: Double): Pair<Pair<Double, Double>, Pair<Double, Double>?> = when (p.align) {
        Values.TOP_LEFT -> {
            val left = p.content.left + SYMBOL_SIDE_DP * d
            (left + w / 2 to p.content.top + h / 2) to (left to p.content.top)
        }
        Values.TOP_CENTER -> (p.content.centerX to p.content.top + h / 2) to (p.content.centerX to p.content.top)
        else -> (p.content.centerX to p.cy) to null
    }

    // the key's one thing in the middle: its badge and label (or icon), its label over its icon, its roots, its
    // label, its icon; where a theme aligns it, it and where it stays
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

    // [m]'s text in lines centred on the face at [cy], shrunk to fit it as a whole
    private fun roots(m: Measured, look: TextLook, content: Box, cy: Double): Drawn {
        val lines = m.text.split('\n')
        val first = if (lines.size > 1) m.size * ROOTS_LINES_SCALE else m.size
        val at = lines.map { measure(it, look, m.weight, first) }
        val wide = at.maxOf { it.m.advance }
        val tall = at.first().m.let { it.bottom - it.top } * lines.size
        val roomX = content.width - 2 * ROOTS_INSET_DP * d
        val fit = minOf(1.0, if (wide > 0) roomX / wide else 1.0, if (tall > 0) content.height / tall else 1.0)
        val size = if (fit < 1.0 && fit > 0.0) first * fit else first
        val ms = if (size == first) at else lines.map { measure(it, look, m.weight, size) }
        val lineHeight = ms.first().m.let { it.bottom - it.top }
        val top = cy - lineHeight * lines.size / 2
        val drawn = ms.mapIndexed { i, line -> line.from(content.centerX - line.m.advance / 2, top + i * lineHeight - line.m.top, Part.Label) }
        return Drawn(drawn.flatMap { it.texts }, emptyList(), drawn.map { it.ink }.reduce(Box::union))
    }

    // the badge's and its label's (or icon's: a theme's `space-content`) width together, and their height
    private fun badgedSize(measured: Measured?, icon: String?): Pair<Double, Double> {
        val iconSize = BADGE_DP * d
        val (w, h) = measured?.box?.let { it.width to it.height } ?: icon?.let { i -> icons.dp(i).let { (w, h) -> w * d to h * d } } ?: (0.0 to 0.0)
        return iconSize + BADGE_GAP_DP * d + w to maxOf(iconSize, h)
    }

    // the badge 15 dp, 3 dp, the label ([measured]'s room kept where it is hidden: [label] null) or the [icon], the two centred at [c]
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

    private fun marks(key: ExpandedKey, cell: Box, content: Box, looks: Looks, hidden: Boolean, snap: Boolean): List<MarkDrawn> {
        val m = env.marks
        val name = key.attrs.shortcut
        if (name == null || '/' in name) {
            val symbol = key.alt?.takeIf { m.symbols && key.attrs.mark != false && !hidden && !looks.mark.hidden }
            val shortcut = name?.let { m.shortcuts[it] }?.takeIf { m.charShortcuts }
            // KeyMarks.place's slots, by what each is: a symbol in the shortcut's slot takes it
            val symbolSlot = symbol?.let { KeyMarks.symbolSlot(m.symbolAt, m.shortcutAt, env.landscape, env.compact) }
            val shortcutSlot = shortcut?.let { KeyMarks.shortcutSlot(KeyMarks.onKeys(m.symbolAt, m.shortcutAt, env.landscape, env.compact).shortcut) }
                ?.takeIf { it != symbolSlot }
            return listOfNotNull(
                shortcutSlot?.let { shortcutMark(shortcut, it, KeyMarkYield.DROP_SHORTCUT, cell, content, looks, snap) },
                symbolSlot?.let { symbolMark(symbol, it, content, looks) },
            )
        }
        // a function key's swipes (the space's cursor, ⌫'s sweep): a theme's `key hint { hidden }` leaves them out
        if (looks.hint.hidden) return emptyList()
        val up = m.shortcuts["$name:up"]
        val down = m.shortcuts["$name:down"]
        val placed = KeyMarks.placeFunction(up, down, m.upOn, m.downOn, m.symbolAt, m.shortcutAt, env.landscape, env.compact)
        // up's first, down's second where both are (placeFunction's order)
        return placed.at.entries.mapIndexed { i, (slot, mark) ->
            val drop = if (i == 1) KeyMarkYield.DROP_DOWN_SHORTCUT else KeyMarkYield.DROP_SHORTCUT
            shortcutMark(mark, slot, drop, cell, content, looks, snap)
        }
    }

    private fun symbolMark(text: String, slot: Slot, content: Box, looks: Looks): MarkDrawn {
        val sizeDp = SYMBOL_DP * textScale * looks.mark.scale
        val size = sizeDp * d
        val measured = measure(text, looks.mark, BOLD, size)
        val vPad = if (slot == Slot.Below) BELOW_DP * d else 0.0
        val box = slotBox(slot, measured.box.width, measured.box.height, content, SYMBOL_SIDE_DP * d, vPad)
        val drawn = measured.inBox(box, Part.Mark)
        return MarkDrawn(drawn, slot, KeyMarkYield.DROP_SYMBOL, box.top)
    }

    // a shortcut's mark in [slot]
    private fun shortcutMark(mark: MarkContent, slot: Slot, drop: Int, cell: Box, content: Box, looks: Looks, snap: Boolean): MarkDrawn {
        val compact = env.compact
        val h = (if (compact) SHORTCUT_SIDE_COMPACT_DP else SHORTCUT_SIDE_DP) * d
        val v = (if (compact) SHORTCUT_EDGE_COMPACT_DP else SHORTCUT_EDGE_DP) * d
        val drawn = when (mark) {
            is MarkContent.Icon -> {
                val dp = if (compact) SHORTCUT_ICON_COMPACT_DP else SHORTCUT_ICON_DP
                Drawn.of(PlacedIcon(mark.name, slotBox(slot, dp * d, dp * d, content, h, v).let { if (snap) laidOut(it) else wholeSized(it) }, Part.Shortcut))
            }
            is MarkContent.Text -> {
                val sizeDp = SHORTCUT_TEXT_DP * textScale
                val m = measure(mark.text, looks.shortcut, looks.shortcut.weight, sizeDp * d).squeezed(SHORTCUT_TEXT_SHARE * cell.width)
                m.inBox(slotBox(slot, m.box.width, m.box.height, content, h, v), Part.Shortcut)
            }
        }
        // a shortcut is never under the label (KeyMarks.Slot.Below): its top centres nothing
        return MarkDrawn(drawn, slot, drop, drawn.ink.top)
    }

    // where a view of [b] was laid out: its corner on the pixel it starts in, its size whole pixels (a
    // shortcut's ImageView, its margins and size in dp): drawn half a pixel off it, its edges rounded the
    // other way, an icon a pixel over
    private fun laidOut(b: Box): Box {
        val x = floor(b.left)
        val y = floor(b.top)
        return Box(x, y, x + floor(b.width + HALF_PX), y + floor(b.height + HALF_PX))
    }

    // [b] its size as [laidOut] has it, where it is: what a mark's yields are worked out on (not snap)
    private fun wholeSized(b: Box): Box = Box(b.left, b.top, b.left + floor(b.width + HALF_PX), b.top + floor(b.height + HALF_PX))

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

    /** A run of a text in one [font], [offset] px from the text's start ([Scripts]). */
    internal class Run(val text: String, val font: String, val offset: Double)

    /**
     * Text measured: its [box] as AutoScaleTextView sizes it (a single character's ink across, longer text's
     * advance; the font's extent up), from the pen's start; its [runs] each drawn in its font, its characters
     * [spacing] em apart. The popups' text is centred so too ([PopupBuilder]).
     */
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
            if (box.width <= max || box.width <= 0) this else Measured(text, m, size, font, weight, max / box.width, spacing, runs)

        /** Drawn with its box centred on ([cx], [cy]). */
        fun at(cx: Double, cy: Double, part: Part): Drawn =
            inBox(Box(cx - box.width / 2, cy - box.height / 2, cx + box.width / 2, cy + box.height / 2), part)

        /** Drawn with its box at [b]. */
        fun inBox(b: Box, part: Part): Drawn = from(b.left - box.left, b.top - box.top, part)

        /** Drawn from the pen at ([x], [baseline]): each run from where the one before it ends. */
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
        const val SHORTCUT_SIDE_DP = 3.0
        const val SHORTCUT_SIDE_COMPACT_DP = 2.0
        const val SHORTCUT_EDGE_DP = 2.0
        const val SHORTCUT_EDGE_COMPACT_DP = 1.0
        const val ROOM_H_DP = 2.0
        const val ROOM_V_DP = 1.0

        /**
         * A mark no nearer its face's side than this, its inset eaten (KeyMarkYield, before the glyph is
         * smaller or the mark left out): a quarter dp of the face's colour still round its ink, half
         * a device px or more at any density from 2 (an icon's strokes further in, its box's own margin).
         */
        const val MARK_EDGE_DP = 0.25

        /** A table's roots in two lines: each at this of the size (TextKeyboard's ROOTS_TEXT_SCALE). */
        const val ROOTS_LINES_SCALE = 0.55

        /** The roots kept this far in from the face's sides (TextKeyboard's ROOTS_INSET_DP). */
        const val ROOTS_INSET_DP = 6.0

        private const val ROOT = "root"
        private const val SPACE = "space"
        private const val ENTER = "enter"
        private const val RETURN_ICON = "return-icon"

        /** A text a theme gives Return: as large as the 确定 key's (`{confirm}`, text-size 16). */
        const val RETURN_TEXT_DP = 16.0

        /** Return's icon (the app's name of it, [Texts.returnIcon]) by the action a theme names (`return-icon-<action>`); `previous` none. */
        private val RETURN_ACTION = mapOf(
            "enter" to "newline", "send" to "send", "search" to "search", "go" to "go", "next" to "next", "done" to "done",
        )

        /** The kinds of a function key: its label a [GlyphClass.Label] but a single character. */
        private val FUNCTION_KINDS = setOf("function", "enter", "space")
        private const val HALF_PX = 0.5

        /** ImageTextKeyView's: the label's and the icon's distance from the bottom and the top, portrait and landscape. */
        private val PORTRAIT_PADS = 4.0 to 8.0
        private val LANDSCAPE_PADS = 2.0 to 4.0

        /**
         * [text] in [look]'s font (each script's run in its own: `font-han`, `font-latin`), its characters its
         * `letter-spacing` apart, at [weight], [size] px: one run, as ever, where the theme sets neither. A
         * key's text and its popups' alike.
         */
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
