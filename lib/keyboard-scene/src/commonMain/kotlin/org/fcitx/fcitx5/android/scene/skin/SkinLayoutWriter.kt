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
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A skin's keys placed on the app's own layout of the same id: only where its keys are, in what order and how big
 * change, so its keys' names, long presses and marks stay the app's. Keys pair by name with the cells the app shows in
 * a reference state (pinyin in a text field, not typing), a skin's row with the app's that has most of its keys; keys
 * standing in for one another in other states take their cell's width. The skin's keys the app lacks are left out, and
 * the app's the skin lacks: what [LayoutCheck] says the board then lacks is told, as for a user's own layout.
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
        REFERENCE.copy(table = "table", tableLookUp = true, lookingUp = true),
        REFERENCE.copy(field = "url"),
        REFERENCE.copy(field = "email"),
        REFERENCE.copy(ime = "shuangpin", schemeTypes = setOf("semicolon")),
    )

    /** A layout file, the `min-app` its `<meta>` has, how many of the skin's keys were left out and what it lacks (`lacks`). */
    class Written(val text: String, val minApp: String, val dropped: Int, val lacks: List<LayoutCheck.Finding>)

    /** [board] as a pack's `layouts/<id>.xml`; null where it shares no row with the app's or the import would not take it. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun write(board: SkinBoard, sources: LayoutCheck.Sources): Written? {
        val finite = board.width.isFinite() && board.rows.all { r -> r.all { it.x.isFinite() && it.y.isFinite() && it.w.isFinite() && it.h.isFinite() } }
        if (board.id !in IDS || !(board.width > 0) || !finite) return null
        // a reader's numbers past what was thought of: this board's layout left out, never the skin (an Error is no such thing)
        return try {
            // with the keys the skin draws apart joined, else without: one may be of a pad of its own
            written(board, sources, joined = true) ?: written(board, sources, joined = false)
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun written(board: SkinBoard, sources: LayoutCheck.Sources, joined: Boolean): Written? {
        val app = sources.layouts[board.id]?.let { (LayoutReader.layout(it) as? Parsed.Ok)?.value } ?: return null
        val own = HashMap<String, Fragment>()
        for (src in app.parts.filterIsInstance<Include>().map { it.src }.distinct()) own[src] = sources.fragment(src) ?: return null
        // the app's columns kept: a split board counts a row's widths in letters
        val rows = PackLayouts.convertRows(app.parts, own).map { (it as? Row ?: return null).explicit(app.defaults) }
        val base = Layout(app.id, app.name, app.family, app.columns, app.onSplit, app.defaults, rows, app.preview, app.popups, app.basedOn, null, app.pads)
        val all = rowsOf(board)
        // the switch key of the row with the space bar: one in a tool row is not the board's
        val lang = all.any { r -> r.any { it.action == SkinAction.Role("lang") } && r.any { it.action == SkinAction.Role("space") } }
        val placed = Placed(rows, app.columns)
        val ref = expand(base, REFERENCE.copy(langKey = lang)) ?: return null
        val others = OTHERS.mapNotNull { expand(base, it.copy(langKey = lang)) }.filter { it.size == ref.size }
        val standIns = ref.indices.map { i -> standIns(ref[i], others.map { it[i] }) }
        val withIt = standIns.flatMap { it.entries }.associate { (c, ks) -> c to ks.map { it.key } }
        val shiftless = rows.none { r -> r.cells.any { it is Key && it.attrs.role == "shift" } }
        val exact = exact(app.family == "nine")
        val fits = standIns.map { fits(it, shiftless, exact) }
        val taken = rowsFor(ref, all, fits) ?: return null
        val skin = if (joined) apart(ref, taken, all) else taken
        val drawn = drawn(ref, skin, exact, fits)
        val drawnFor = DrawnFor((listOf(ref) + others).flatten().flatMap { it.items.filterIsInstance<ExpandedKey>() }, exact, drawn)
        // of the keys drawn in the row's own: one joined from across two rows, or one left out, is no measure of it
        val heights = skin.indices.map { i ->
            val own = drawn[i].keys.map { skin[i][it] }.filter { b -> taken[i].any { it === b } }.ifEmpty { taken[i] }
            own.map { it.h }.sorted()[own.size / 2]
        }
        val mean = heights.average()
        var carried = emptyList<Carry>()
        // before any key moves row
        val ats = ref.map { placed.rowOf(it) ?: return null }
        for ((i, er) in ref.withIndex()) {
            val at = ats[i]
            val gone = er.items.filterIsInstance<ExpandedKey>().filter { it !in drawn[i].values }
                .flatMap { k -> listOf(k.key) + standIns[i][k.key].orEmpty().map { it.key } }.toSet()
            carried = placed.row(at, er, skin[i], drawn[i], gone to withIt, board.width, carried, drawnFor::twice) ?: return null
            placed.height[at] = hundredths((heights[i] / mean).coerceIn(MIN_HEIGHT, MAX_HEIGHT))
        }
        others.forEach { other ->
            for (i in ref.indices) placed.alternates(ats[i], ref[i], other[i])
        }
        val made = placed.layout(base)
        val features = LayoutCheck.minApp(made.withMeta(LayoutMeta(null, null, null, null, null)))
        val done = PackLayouts.convert(app.name, null, board.id, write(made, features), emptyMap(), sources, features) as? PackLayouts.Converted.Done ?: return null
        val lacks = done.problems.filter { it.key == "lacks" }
        val minApp = relaxed(features, done.problems)
        val extra = all.flatten().count { b -> placed.pairs.none { it === b } && drawnFor.elsewhere(b) }
        return Written(write(made, minApp), minApp, extra, lacks)
    }

    // one lacking keys or blank somewhere is for an app that takes it, as LayoutCheck's report and the layout maker say
    internal fun relaxed(features: String, problems: List<LayoutCheck.Finding>): String =
        LayoutCheck.FEATURES.getValue("lacks").takeIf { v -> problems.any { it.key == "lacks" || it.key == "blank" } && ThemeReader.newer(v, features) } ?: features

    // a nine keys' 1 is drawn with its marks, as the skin's comma
    private fun exact(nine: Boolean): (ExpandedKey, SkinAction) -> Boolean = { k, a ->
        matches(k, a) || nine && k.key.id == "1" && a == SkinAction.Role("comma")
    }

    // the app's keys a skin's key is drawn for, and whether another of the skin's keys was drawn for each
    private class DrawnFor(val appKeys: List<ExpandedKey>, val exact: (ExpandedKey, SkinAction) -> Boolean, drawn: List<Map<Int, ExpandedKey>>) {
        private val paired = drawn.flatMap { it.values }.map { it.key }.toSet()

        // one left out is the skin's own unless it is an app's key drawn nowhere else (told as lacking): a pad's 2 beside the board's is
        fun elsewhere(b: SkinKeyBox) = appKeys.filter { exact(it, b.action) }.all { it.key in paired }

        // a digit or letter the board draws too: a pad's beside the board, where its side is (a key of a role drawn twice is no pad)
        fun twice(b: SkinKeyBox) = b.action is SkinAction.Letter && appKeys.any { exact(it, b.action) } && elsewhere(b)
    }

    private fun write(made: Layout, minApp: String): String = LayoutWriter.write(made.withMeta(LayoutMeta(null, null, null, minApp, null)))

    // 18 and 14 keys have no ⇧: a skin's there is the separator's cell
    private fun fits(
        standIns: Map<Cell, List<ExpandedKey>>,
        shiftless: Boolean,
        exact: (ExpandedKey, SkinAction) -> Boolean,
    ): (ExpandedKey, SkinAction) -> Boolean = { k, a ->
        exact(k, a) || standIns[k.key].orEmpty().any { matches(it, a) } ||
            shiftless && k.attrs.role == "apostrophe" && a == SkinAction.Role("shift")
    }

    // each row's keys by the skin's key drawn for them: of its name, else of one standing in for it; where several are, the
    // one nearest the columns of the row's keys drawn once, then the widest (the board's own, not a pad's or side's beside
    // it, however wide); a key the skin draws only in another of its rows moves there
    private fun drawn(
        ref: List<ExpandedRow>,
        skin: List<List<SkinKeyBox>>,
        exact: (ExpandedKey, SkinAction) -> Boolean,
        fits: List<(ExpandedKey, SkinAction) -> Boolean>,
    ): List<Map<Int, ExpandedKey>> {
        val keys = ref.map { er -> er.items.filterIsInstance<ExpandedKey>() }
        val out = ref.indices.map { HashMap<Int, ExpandedKey>() }
        for (i in ref.indices) {
            val once = keys[i].mapNotNull { k -> skin[i].filter { fits[i](k, it.action) }.singleOrNull() }
            val lo = once.minOfOrNull { it.x } ?: Double.NEGATIVE_INFINITY
            val hi = once.maxOfOrNull { it.x + it.w } ?: Double.POSITIVE_INFINITY
            fun off(b: SkinKeyBox) = maxOf(0.0, lo - (b.x + b.w), b.x - hi)
            for (named in listOf(true, false)) {
                for (k in keys[i].filter { it !in out[i].values }) {
                    skin[i].indices.filter { j -> j !in out[i] && if (named) exact(k, skin[i][j].action) else fits[i](k, skin[i][j].action) }
                        .maxWithOrNull(compareBy({ -off(skin[i][it]) }, { skin[i][it].w }))?.let { out[i][it] = k }
                }
            }
        }
        for (i in ref.indices) {
            for (k in keys[i].filter { it !in out[i].values }) {
                val (r, j) = ref.indices.filter { it != i }.firstNotNullOfOrNull { r ->
                    skin[r].indices.firstOrNull { j -> j !in out[r] && matches(k, skin[r][j].action) }?.let { r to it }
                } ?: continue
                out[r][j] = k
            }
        }
        return out
    }

    // a key the skin draws in none of the rows taken (one across two rows its own) joins the app's row it is in: one over
    // the row's middle, beside its keys
    private fun apart(ref: List<ExpandedRow>, taken: List<List<SkinKeyBox>>, all: List<List<SkinKeyBox>>): List<List<SkinKeyBox>> {
        val drawn = taken.flatten()
        val left = all.filter { r -> taken.none { it === r } }.flatten().toMutableList()
        return ref.mapIndexed { i, er ->
            val keys = er.items.filterIsInstance<ExpandedKey>().filter { k -> drawn.none { matches(k, it.action) } }
            val middle = taken[i].first().let { it.y + it.h / 2 }
            fun beside(b: SkinKeyBox) = b.y <= middle && middle <= b.y + b.h && taken[i].none { it.x < b.x + b.w && b.x < it.x + it.w }
            val joins = left.filter { b -> beside(b) && keys.any { matches(it, b.action) } }
            left -= joins.toSet()
            (taken[i] + joins).sortedBy { it.x }
        }
    }

    // each of the app's rows the skin's after the last taken with most of its keys, the widest of them where as many (the
    // board's, not a pad's beside it): a number or tool row is none of them
    private fun rowsFor(
        ref: List<ExpandedRow>,
        skin: List<List<SkinKeyBox>>,
        fits: List<(ExpandedKey, SkinAction) -> Boolean>,
    ): List<List<SkinKeyBox>>? {
        var from = 0
        return ref.mapIndexed { i, er ->
            val keys = er.items.filterIsInstance<ExpandedKey>()
            fun of(r: List<SkinKeyBox>) = r.filter { b -> keys.any { fits[i](it, b.action) } }
            val at = (from..skin.size - ref.size + i).maxWithOrNull(compareBy({ of(skin[it]).size }, { of(skin[it]).sumOf { b -> b.w } }))
                ?.takeIf { of(skin[it]).isNotEmpty() } ?: return null
            from = at + 1
            skin[at]
        }
    }

    // the keys shown in a cell's place in other states (as many shown in the row): a skin draws its cell with any one of them
    private fun standIns(ref: ExpandedRow, others: List<ExpandedRow>): Map<Cell, List<ExpandedKey>> {
        fun shown(r: ExpandedRow) = r.items.filter { it is ExpandedKey || it is ExpandedSlot }
        val a = shown(ref)
        val out = HashMap<Cell, MutableList<ExpandedKey>>()
        for (b in others.map(::shown).filter { it.size == a.size }) {
            a.zip(b).forEach { (x, y) -> if (x is ExpandedKey && y is ExpandedKey && y.key != x.key) out.getOrPut(x.key) { ArrayList() } += y }
        }
        return out
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

    // a key and the skin's it is drawn as; [afterExtra]: a key of the skin's the app lacks before it
    private class Paired(val key: ExpandedKey, val box: SkinKeyBox, val afterExtra: Boolean)

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
        private val order = HashMap<Int, List<Cell>>()
        /** The skin's keys drawn. */
        val pairs = ArrayList<SkinKeyBox>()

        fun rowOf(er: ExpandedRow): Int? = er.items.firstNotNullOfOrNull { cell(it) }?.let { rowOfCell[it] }

        /**
         * The skin's row [skin] on row [at], its keys [drawn] (by the skin's) in its order; [cells]: those left out, and those
         * shown in a key's place that move with it from another row. The slots it carries down, or null where none is drawn.
         */
        fun row(
            at: Int,
            er: ExpandedRow,
            skin: List<SkinKeyBox>,
            drawn: Map<Int, ExpandedKey>,
            cells: Pair<Set<Cell>, Map<Cell, List<Cell>>>,
            across: Double,
            carried: List<Carry>,
            twice: (SkinKeyBox) -> Boolean,
        ): List<Carry>? {
            if (drawn.isEmpty()) return null
            val pairs = ArrayList<Paired>()
            var extra = false
            for (j in skin.indices) {
                val k = drawn[j]
                if (k == null) extra = true else pairs += Paired(k, skin[j], extra).also { extra = false }
            }
            val fill = pairs.any { it.key.attrs.width == Width.Fill }
            val shown: List<Cell> = pairs.map { it.key.key }
            val moved = shown.filter { it !in rows[at].cells }.associateWith { cells.second[it].orEmpty() }
            (shown + moved.values.flatten()).forEach { rowOfCell[it] = at }
            order[at] = ordered(rows[at], shown, cells.first, moved)
            val boxes = pairs.map { it.box }
            this.pairs += boxes
            fun pc(v: Double) = v / across * columns
            val carriedLeft = carried.filter { it.left }.sumOf { it.width }
            // a slot at the start reaches the skin's first key, drawn or left out, past a pad's beside the board (its side)
            val first = skin.first { b -> boxes.any { it === b } || !twice(b) }
            val left = pc(first.x) - carriedLeft
            // a fill key's row gives it the room of keys left out at its ends too
            val last = if (fill) skin.last() else boxes.last()
            val right = columns - pc(last.x + last.w) - carried.filter { !it.left }.sumOf { it.width }
            val next = carried.filter { it.rows > 1 }.map { Carry(it.left, it.width, it.rows - 1) }.toMutableList()
            val ends = slots(er, left, right, next) ?: return null
            // the room of the skin's keys left out before the first drawn: past a slot a gap (a fill key's in its row), else the indent's
            val lead = pc(boxes.first().x - first.x)
            val slotted = carriedLeft > 0 || er.items.firstOrNull() is ExpandedSlot
            val start = if (slotted || fill) ends.first else ends.first + lead
            if (start < -minIndent || ends.second < -minIndent) return null
            carriedAt[at] = carried.sumOf { it.width }
            val room = columns - carriedAt.getValue(at) - er.items.filterIsInstance<ExpandedSlot>().sumOf { width.getValue(it.slot) }
            val leadGap = if (slotted && !fill && lead >= minGap) round(lead).also { gapBefore[shown.first()] = it } else 0.0
            val sum = keyWidths(at, pairs, fill, ::pc) + leadGap
            val widest = pairs.filter { it.key.attrs.width != Width.Fill }.maxByOrNull { width.getValue(it.key.key) }?.key?.key
            ends(at, fill, start.coerceAtLeast(0.0), ends.second.coerceAtLeast(0.0), room - sum, widest)
            return next.takeIf { width.values.none { it <= 0 } }
        }

        // the cells not shown go with the shown key before them in the app's row, a slot at its own end
        private fun ordered(row: Row, shown: List<Cell>, gone: Set<Cell>, moved: Map<Cell, List<Cell>>): List<Cell> {
            val head = ArrayList<Cell>()
            val tail = ArrayList<Cell>()
            val after = HashMap<Cell, MutableList<Cell>>()
            var last: Cell? = null
            for (c in row.cells) {
                when {
                    c in shown -> last = c
                    c in gone -> Unit
                    c is Slot -> (if (last == null) head else tail) += c
                    last == null -> head += c
                    else -> after.getOrPut(last) { ArrayList() } += c
                }
            }
            return head + shown.flatMap { listOf(it) + after[it].orEmpty() + moved[it].orEmpty() } + tail
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

        // each key's width and the gap before it as the skin has them; their sum. A key of the skin's left out leaves its
        // room: to the fill key where there is one, else as a gap
        private fun keyWidths(at: Int, pairs: List<Paired>, fill: Boolean, pc: (Double) -> Double): Double {
            var sum = 0.0
            for ((n, p) in pairs.withIndex()) {
                val key = p.key
                val b = p.box
                if (key.attrs.width != Width.Fill) width[key.key] = round(pc(b.w)).also { sum += it }
                val prev = pairs.getOrNull(n - 1)
                val g = gap(prev, p, fill)?.let(pc) ?: continue
                if (g >= minGap) gapBefore[key.key] = round(g).also { sum += it }
            }
            gaps[at] = gapBefore.filterKeys { rowOfCell[it] == at }.values.sum()
            return sum
        }

        // the room between two keys sized, none past a key left out of a fill key's row
        private fun gap(prev: Paired?, p: Paired, fill: Boolean): Double? {
            val a = prev?.box ?: return null
            val b = p.box
            val sized = p.key.attrs.width != Width.Fill && prev.key.attrs.width != Width.Fill
            return (b.x - (a.x + a.w)).takeIf { sized && !(fill && p.afterExtra) }
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
        fun alternates(at: Int, ref: ExpandedRow, other: ExpandedRow) {
            val a = ref.items.filter { it is ExpandedKey || it is ExpandedSlot }
            val b = other.items.filter { it is ExpandedKey || it is ExpandedSlot }
            if (a.size == b.size) {
                a.zip(b).mapNotNull { (x, y) -> cell(x)?.let { f -> cell(y)?.let { f to it } } }
                    .filter { (from, to) -> to !in width && from in width }
                    .forEach { (from, to) -> width[to] = width.getValue(from) }
                return
            }
            fitIn(at, b)
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
                for (c in order[i] ?: r.cells) {
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
        is Slot -> Slot(id, kind, w, rows, face, look, classes, condition, line, list, direction, show)
    }

    private fun Key.withWidth(w: Width): Key = Key(id, condition, attrs.copy(width = w), popup, menu, line, variants, letters, lean, place)

    private fun hundredths(v: Double): Double = (v * PERCENT).roundToInt() / PERCENT
}
