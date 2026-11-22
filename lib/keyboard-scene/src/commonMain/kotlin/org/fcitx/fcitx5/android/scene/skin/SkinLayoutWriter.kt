/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.ExpandedGap
import org.fcitx.fcitx5.android.scene.engine.ExpandedKey
import org.fcitx.fcitx5.android.scene.engine.ExpandedRow
import org.fcitx.fcitx5.android.scene.engine.ExpandedSlot
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Item
import org.fcitx.fcitx5.android.scene.layout.Cell
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Gap
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutMeta
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.LayoutWriter
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.fcitx.fcitx5.android.scene.layout.Width
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A skin's keys placed on the app's own layout of the same id: only where its keys are and how big change, so its
 * keys' names, long presses and marks stay the app's. A skin's row has to match the cells the app shows in a
 * reference state (pinyin in a text field, not typing); keys standing in for one another in other states take
 * their cell's width.
 */
object SkinLayoutWriter {
    val IDS = listOf("qwerty", "nine", "k18", "k14")

    // a width is rounded to a thousandth of the panel's; room enough for an indent or a gap is in hundredths of it
    private const val STEPS = 1000.0
    private const val PERCENT = 100.0
    private const val MIN_INDENT = 0.5
    private const val MIN_GAP = 1.0
    private const val SAME_ROW = 0.25
    private const val MIN_HEIGHT = 0.3
    private const val MAX_HEIGHT = 3.0

    private val REFERENCE = Input(ime = "pinyin", field = "text", composing = false, table = null)

    // in this order: a key's width is the first of these to give it one
    private val OTHERS = listOf(
        REFERENCE.copy(ime = "english"),
        REFERENCE.copy(composing = true),
        REFERENCE.copy(table = "table", composing = true),
        REFERENCE.copy(table = "table", tableExtra = "semicolon"),
        REFERENCE.copy(table = "table", tableExtra = "star"),
        REFERENCE.copy(table = "table", tableLookUp = true),
        REFERENCE.copy(field = "url"),
        REFERENCE.copy(field = "email"),
        REFERENCE.copy(ime = "shuangpin", schemeTypes = setOf("semicolon")),
    )

    /** A layout file and the `min-app` its `<meta>` has. */
    class Written(val text: String, val minApp: String)

    /** [board] as a pack's `layouts/<id>.xml`; null where it does not match or the import would not take it. */
    fun write(board: SkinBoard, sources: LayoutCheck.Sources): Written? {
        val finite = board.width.isFinite() && board.rows.all { r -> r.all { it.x.isFinite() && it.y.isFinite() && it.w.isFinite() && it.h.isFinite() } }
        if (board.id !in IDS || !(board.width > 0) || !finite) return null
        // a reader's numbers past what was thought of: this board's layout left out, never the skin
        return runCatching { written(board, sources) }.getOrNull()
    }

    private fun written(board: SkinBoard, sources: LayoutCheck.Sources): Written? {
        val app = sources.layouts[board.id]?.let { (LayoutReader.layout(it) as? Parsed.Ok)?.value } ?: return null
        val own = HashMap<String, Fragment>()
        for (src in app.parts.filterIsInstance<Include>().map { it.src }.distinct()) own[src] = sources.fragment(src) ?: return null
        // the app's columns kept: a split board counts a row's widths in letters
        val rows = PackLayouts.convertRows(app.parts, own).map { (it as? Row ?: return null).explicit(app.defaults) }
        val base = Layout(app.id, app.name, app.family, app.columns, app.onSplit, app.defaults, rows, app.preview, app.popups, app.basedOn, null, app.pads)
        val skin = rowsOf(board)
        if (skin.isEmpty()) return null
        val lang = skin.last().any { it.action == SkinAction.Role("lang") }
        val placed = Placed(rows, app.columns)
        val ref = expand(base, REFERENCE.copy(langKey = lang)) ?: return null
        if (ref.size != skin.size) return null
        val heights = skin.map { r -> r.sumOf { it.h } / r.size }
        val mean = heights.average()
        var carried = emptyList<Carry>()
        for ((i, er) in ref.withIndex()) {
            val at = placed.rowOf(er) ?: return null
            carried = placed.row(at, er, skin[i], board.width, carried, last = i == ref.lastIndex) ?: return null
            placed.height[at] = hundredths((heights[i] / mean).coerceIn(MIN_HEIGHT, MAX_HEIGHT))
        }
        OTHERS.mapNotNull { expand(base, it.copy(langKey = lang)) }.filter { it.size == ref.size }.forEach { other ->
            for (i in ref.indices) placed.alternates(ref[i], other[i])
        }
        val made = placed.layout(base)
        val minApp = LayoutCheck.minApp(made.withMeta(LayoutMeta(null, null, null, null, null)))
        val out = LayoutWriter.write(made.withMeta(LayoutMeta(null, null, null, minApp, null)))
        val taken = PackLayouts.convert(app.name, null, board.id, out, emptyMap(), sources, minApp)
        return Written(out, minApp).takeIf { taken is PackLayouts.Converted.Done }
    }

    private fun expand(layout: Layout, input: Input): List<ExpandedRow>? =
        (Expand.rows(layout, emptyMap(), Env(width = 1080.0, height = 800.0, density = 3.0, input = input)) as? Parsed.Ok)?.value

    // decorations left out; a key in the row whose centre is within a quarter of its height of the row's first
    private fun rowsOf(board: SkinBoard): List<List<SkinKeyBox>> {
        val keys = board.rows.flatten().filter { it.action != SkinAction.None && it.w > 0 && it.h > 0 }.sortedBy { it.y + it.h / 2 }
        val out = ArrayList<ArrayList<SkinKeyBox>>()
        for (b in keys) {
            val row = out.lastOrNull()
            val first = row?.first()
            if (first != null && abs((b.y + b.h / 2) - (first.y + first.h / 2)) < first.h * SAME_ROW) row.add(b) else out.add(arrayListOf(b))
        }
        return out.map { r -> r.sortedBy { it.x } }
    }

    private class Carry(val left: Boolean, val width: Double, val rows: Int)

    private class Placed(val rows: List<Row>, val columns: Double) {
        private val minIndent = MIN_INDENT / PERCENT * columns
        private val minGap = MIN_GAP / PERCENT * columns

        private fun round(v: Double): Double = (v / columns * STEPS).roundToInt() * columns / STEPS

        private val rowOfCell = HashMap<Cell, Int>().also { m -> rows.forEachIndexed { i, r -> r.cells.forEach { m[it] = i } } }
        val width = HashMap<Cell, Double>()
        val gapBefore = HashMap<Cell, Double>()
        val indent = HashMap<Int, Double>()
        val endIndent = HashMap<Int, Double>()
        val height = HashMap<Int, Double>()
        private val gaps = HashMap<Int, Double>()
        private val carriedAt = HashMap<Int, Double>()

        fun rowOf(er: ExpandedRow): Int? = er.items.firstNotNullOfOrNull { cell(it) }?.let { rowOfCell[it] }

        /** The skin's row [skin] on the cells shown of row [at]; the slots it carries down, or null where they do not match. */
        fun row(at: Int, er: ExpandedRow, skin: List<SkinKeyBox>, across: Double, carried: List<Carry>, last: Boolean): List<Carry>? {
            val keys = er.items.filterIsInstance<ExpandedKey>()
            val fill = keys.any { it.attrs.width == Width.Fill }
            val extraBefore = HashSet<Int>()
            val pairs = pair(keys, skin, extraOk = last && fill, extraBefore) ?: return null
            fun pc(v: Double) = v / across * columns
            val left = pc(pairs.first().second.x) - carried.filter { it.left }.sumOf { it.width }
            val right = columns - pc(pairs.last().second.let { it.x + it.w }) - carried.filter { !it.left }.sumOf { it.width }
            val next = carried.filter { it.rows > 1 }.map { Carry(it.left, it.width, it.rows - 1) }.toMutableList()
            val ends = slots(er, left, right, next) ?: return null
            if (ends.first < -minIndent || ends.second < -minIndent) return null
            carriedAt[at] = carried.sumOf { it.width }
            val room = columns - carriedAt.getValue(at) - er.items.filterIsInstance<ExpandedSlot>().sumOf { width.getValue(it.slot) }
            val sum = keyWidths(at, pairs, extraBefore, ::pc)
            val widest = pairs.map { it.first }.filter { it.attrs.width != Width.Fill }.maxByOrNull { width.getValue(it.key) }?.key
            ends(at, fill, ends.first.coerceAtLeast(0.0), ends.second.coerceAtLeast(0.0), room - sum, widest)
            return next.takeIf { width.values.none { it <= 0 } }
        }

        // the app's keys in order against the skin's; a key of the skin's last row the app lacks is left to the fill key
        private fun pair(keys: List<ExpandedKey>, skin: List<SkinKeyBox>, extraOk: Boolean, extraBefore: MutableSet<Int>): List<Pair<ExpandedKey, SkinKeyBox>>? {
            val pairs = ArrayList<Pair<ExpandedKey, SkinKeyBox>>()
            var j = 0
            for (b in skin) {
                val extra = extraOk && b.action is SkinAction.Role && keys.drop(j).none { matches(it, b.action) }
                when {
                    j < keys.size && matches(keys[j], b.action) -> pairs += keys[j++] to b
                    extra -> extraBefore += pairs.size
                    else -> return null
                }
            }
            return pairs.takeIf { j == keys.size && it.isNotEmpty() }
        }

        // a slot only at either end, as wide as the skin leaves there; what is left at each end
        private fun slots(er: ExpandedRow, left0: Double, right0: Double, next: MutableList<Carry>): Pair<Double, Double>? {
            var left = left0
            var right = right0
            for ((i, item) in er.items.withIndex()) {
                val slot = (item as? ExpandedSlot)?.slot ?: continue
                val first = i == 0
                val w = round(if (first) left else right)
                if (!first && i != er.items.lastIndex || w <= 0) return null
                width[slot] = w
                if (first) left -= w else right -= w
                if (slot.rows > 1) next += Carry(first, w, slot.rows - 1)
            }
            return left to right
        }

        // each key's width and the gap before it as the skin has them; their sum
        private fun keyWidths(at: Int, pairs: List<Pair<ExpandedKey, SkinKeyBox>>, extraBefore: Set<Int>, pc: (Double) -> Double): Double {
            var sum = 0.0
            for ((n, p) in pairs.withIndex()) {
                val (key, b) = p
                if (key.attrs.width != Width.Fill) width[key.key] = round(pc(b.w)).also { sum += it }
                val prev = pairs.getOrNull(n - 1) ?: continue
                val g = pc(b.x - (prev.second.x + prev.second.w))
                val sized = key.attrs.width != Width.Fill && prev.first.attrs.width != Width.Fill
                if (g >= minGap && n !in extraBefore && sized) gapBefore[key.key] = round(g).also { sum += it }
            }
            gaps[at] = gapBefore.filterKeys { rowOfCell[it] == at }.values.sum()
            return sum
        }

        // the row's indents, and the widest key taking what the rounding leaves over
        private fun ends(at: Int, fill: Boolean, left: Double, right: Double, spare: Double, widest: Cell?) {
            when {
                fill -> {
                    if (left >= minIndent) indent[at] = round(left)
                    if (right >= minIndent) endIndent[at] = round(right)
                }
                // centred: the app centres a row, and one shown with a key more (`;`) stays centred
                abs(left - right) < minIndent -> {
                    if (spare < 0 && widest != null) width[widest] = round(width.getValue(widest) + spare)
                }
                else -> {
                    val l = if (left >= minIndent) round(left) else 0.0
                    val r = if (right >= minIndent) round(right) else 0.0
                    if (l > 0) indent[at] = l
                    if (r > 0) endIndent[at] = r
                    if (widest != null) width[widest] = round(width.getValue(widest) + spare - l - r)
                }
            }
        }

        /** Keys shown in [other] in place of [ref]'s: the cell's width where as many show, else the app's within the room. */
        fun alternates(ref: ExpandedRow, other: ExpandedRow) {
            val a = ref.items.filter { it is ExpandedKey || it is ExpandedSlot }
            val b = other.items.filter { it is ExpandedKey || it is ExpandedSlot }
            if (a.size == b.size) {
                a.zip(b).mapNotNull { (x, y) -> cell(x)?.let { f -> cell(y)?.let { f to it } } }
                    .filter { (from, to) -> to !in width && from in width }
                    .forEach { (from, to) -> width[to] = width.getValue(from) }
                return
            }
            rowOf(other)?.let { fitIn(it, b) }
        }

        private fun fitIn(at: Int, b: List<Item>) {
            val fill = b.any { it is ExpandedKey && it.attrs.width == Width.Fill }
            val ends = (carriedAt[at] ?: 0.0) + (indent[at] ?: 0.0) + (endIndent[at] ?: 0.0) + (gaps[at] ?: 0.0)
            for ((c, own) in b.mapNotNull { item -> cell(item)?.let { c -> (item.width as? Width.Units)?.let { c to it.units } } }) {
                if (c in width) continue
                val room = columns - ends - b.filter { cell(it) != c }.sumOf { width[cell(it)] ?: (it.width as? Width.Units)?.units ?: 0.0 }
                width[c] = if (fill || room >= own) own else round(room).takeIf { it > 0 } ?: own
            }
        }

        fun layout(base: Layout): Layout = Layout(
            base.id, base.name, base.family, columns, base.onSplit, base.defaults,
            rows.mapIndexed { i, r ->
                val cells = ArrayList<Cell>()
                for (c in r.cells) {
                    gapBefore[c]?.let { cells += Gap(null, Width.Units(it), null, c.line) }
                    cells += width[c]?.let { w -> c.withWidth(Width.Units(w)) } ?: c
                }
                Row(r.id, indent[i] ?: r.indent, endIndent[i] ?: r.endIndent, height[i] ?: r.height, r.condition, r.split, r.defaults, cells, r.line)
            },
            base.preview, base.popups, base.basedOn, null, base.pads,
        )
    }

    private fun cell(item: Item): Cell? = when (item) {
        is ExpandedKey -> item.key
        is ExpandedGap -> item.gap
        is ExpandedSlot -> item.slot
        else -> null
    }

    private fun matches(key: ExpandedKey, a: SkinAction): Boolean = when (a) {
        is SkinAction.Letter -> a.char.lowercaseChar().let { c -> key.key.id == c.toString() || key.key.letters?.group?.contains(c) == true }
        is SkinAction.Role -> key.attrs.role == a.name || key.key.id == a.name
        SkinAction.None -> false
    }

    private fun Layout.withMeta(meta: LayoutMeta): Layout = Layout(id, name, family, columns, onSplit, defaults, parts, preview, popups, basedOn, meta, pads)

    // every key's width written, so a width changed on one key does not move another sharing a default
    private fun Row.explicit(fileDefaults: KeyAttrs): Row {
        val shared = defaults.width ?: fileDefaults.width ?: Width.Units(1.0)
        return Row(id, indent, endIndent, height, condition, split, defaults, cells.map { if (it is Key) it.withWidth(it.attrs.width ?: shared) else it }, line)
    }

    private fun Cell.withWidth(w: Width): Cell = when (this) {
        is Key -> withWidth(w)
        is Gap -> Gap(id, w, condition, line)
        is Slot -> Slot(id, kind, w, rows, face, look, classes, condition, line)
    }

    private fun Key.withWidth(w: Width): Key = Key(id, condition, attrs.copy(width = w), popup, menu, line, variants, letters, lean, place)

    private fun hundredths(v: Double): Double = (v * PERCENT).roundToInt() / PERCENT
}
