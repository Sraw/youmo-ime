/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.rules

import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Slot
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Keeps a key's glyph and its slot marks ([KeyMarks]) apart, measured by ink, in Doubles so the JVM and JS agree.
 * Classes ([board]) are one size; a mark gives way within its own corner, then the glyph class shrinks (to
 * [MAX_CLASS_SHRINK]), then the mark class (to [MIN_MARK_SCALE]), then marks are left out ([Mark.drop]).
 */
object KeyMarkYield {

    data class Ink(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top

        fun moved(dx: Double, dy: Double) = Ink(left + dx, top + dy, right + dx, bottom + dy)

        fun scaled(s: Double, x: Double, y: Double) = Ink(x + (left - x) * s, y + (top - y) * s, x + (right - x) * s, y + (bottom - y) * s)

        /** Touching, with no gap asked, is apart. */
        fun meets(o: Ink, gap: Double): Boolean =
            left < o.right + gap && o.left < right + gap && top < o.bottom + gap && o.top < bottom + gap
    }

    /** [drop]: the higher, the sooner it is left out where nothing else clears it. */
    data class Mark(val ink: Ink, val slot: Slot, val drop: Int = DROP_SHORTCUT, val grows: Grows? = null, val user: Boolean = false) {
        internal val expendable: Int get() = drop + if (user) 0 else BUILT_IN
    }

    /** A font's ink is no linear function of its size (whole pixels, hinting), so the text is measured again. */
    fun interface Grows {
        fun at(s: Double): Ink
    }

    data class Settled(val glyph: Ink, val marks: List<Ink?>)

    /** How far out a mark may go into its inset: within [face] and its corner [radii] (TL, TR, BR, BL), [floor] in from the sides. */
    data class Edge(val face: Ink, val radii: List<Double> = NO_RADII, val floor: Double = 0.0, val line: Double = 0.0) {
        init {
            require(radii.size == 4) { "an edge has four corner radii, not ${radii.size}" }
        }

        internal fun radius(slot: Slot, right: Boolean): Double {
            val left = slot.left == right
            return radii[if (slot.bottom) (if (left) 3 else 2) else (if (left) 0 else 1)]
        }

        internal fun bottomRadius(slot: Slot, right: Boolean): Double {
            val left = slot.left == right
            return radii[if (slot.bottom) (if (left) 0 else 1) else (if (left) 3 else 2)]
        }
    }

    /** A key of a class: its [glyph] scaled about ([x], [y]) (a text's middle on its baseline, an icon's middle). */
    data class Member(
        val glyph: Ink,
        val x: Double,
        val y: Double,
        val marks: List<Mark>,
        val room: Ink,
        val edge: Edge = Edge(room),
        val moves: Boolean = true,
        val glyphClass: Int = 0,
        val grows: Grows? = null,
        val across: Boolean = false,
        /** A text drawn shorter for want of room asks nothing of the mark classes, so one long text never shrinks every mark. */
        val shortened: Boolean = false,
    )

    data class Group(val scale: Double, val keys: List<Settled>)

    data class Board(val scales: Map<Int, Double>, val symbol: Double, val shortcut: Double, val keys: List<Settled>) {
        fun markScale(m: Mark): Double = if (m.drop == DROP_SYMBOL) symbol else shortcut
    }

    val NO_GLYPH = Ink(Double.NaN, Double.NaN, Double.NaN, Double.NaN)

    const val DROP_SYMBOL = 1
    const val DROP_SHORTCUT = 2

    const val DROP_DOWN_SHORTCUT = 3

    private const val BUILT_IN = 10

    const val MAX_CLASS_SHRINK = 0.06

    const val MIN_MARK_SCALE = 0.875

    /** Below [MIN_MARK_SCALE] a key's need fades out over this band, so a class never jumps where a key stops being saved. */
    const val HOPELESS_BAND = 0.15

    /** How far short of its gap a mark may fall and still be kept, px; applied everywhere, else a mark would jump. */
    const val FIT_SLACK = 0.5

    private const val SCALE_STEP = 0.001
    private const val OUT_PX = 0.001
    private const val SHARE_STEP = 0.001

    private val NO_RADII = listOf(0.0, 0.0, 0.0, 0.0)

    fun key(glyph: Ink, marks: List<Mark>, room: Ink, gap: Double, edge: Edge = Edge(room)): Settled =
        group(listOf(Member(glyph, mid(glyph), (glyph.top + glyph.bottom) / 2.0, marks, room, edge)), gap).keys[0]

    fun group(keys: List<Member>, gap: Double): Group {
        val b = board(keys.map { it.copy(glyphClass = 0) }, gap)
        return Group(b.scales[0] ?: 1.0, b.keys)
    }

    /** A board's keys settled so every glyph class ([Member.glyphClass]) and mark class is one size. */
    fun board(drawn: List<Member>, gap: Double, minMark: Double = MIN_MARK_SCALE): Board {
        // needs are worked with each glyph aside, so a class's size follows the room, never how a glyph would move
        val keys = drawn.map { it.aside() }
        val apart = keys.map { k -> keptApart(k.marks.map { it.within(k.edge) }, k.edge) }
        val hopeless = minMark - HOPELESS_BAND
        val asIs = keys.indices.map { k -> drawn[k].copy(moves = false).let { m -> place(m.glyph, apart[k].filterNotNull(), m, gap) } }
        fun whole(k: Int) = asIs[k] != null && apart[k].all { it != null }
        fun marksAt(k: Int, t: Double, u: Double = t, symbol: Boolean? = null): List<Mark?> = keptApart(
            keys[k].marks.map { it to it.sizeIn(t, u, symbol) }.filter { it.second > 0.0 }.map { (m, s) -> m.scaled(s).within(keys[k].edge) },
            keys[k].edge,
        )
        fun fit(k: Int, g: Ink, marks: List<Mark?>) = marks.all { it != null } && place(g, marks.filterNotNull(), keys[k], gap) != null
        val floor = if (minMark >= 1.0) minMark else hopeless
        fun asks(k: Int, only: Boolean) = glyphAsks(
            floor, minMark,
            marks = { t -> if (only) marksAt(k, t, 0.0, symbol = true) else marksAt(k, t) },
            fits = { s, marks -> fit(k, keys[k].at(s), marks) },
        )
        // a key with both asks no more than its symbols alone, so they are not let go with the shortcuts
        val asked = keys.indices.map { k ->
            minOf(if (whole(k)) 1.0 else minOf(asks(k, false), asks(k, true)), keys[k].across())
        }
        val scales = keys.indices.groupBy { keys[it].glyphClass }.mapValues { (_, ks) -> ks.minOf { asked[it] } }
        val markNeed = keys.indices.map { k ->
            if (whole(k)) return@map 1.0 to 1.0
            val g = keys[k].at(scales.getValue(keys[k].glyphClass))
            val need = markNeed(floor, minMark) { t, u, symbol -> fit(k, g, marksAt(k, t, u, symbol)) }
            if (keys[k].shortened) need.first to 1.0 else need
        }
        val symbol = markNeed.minOfOrNull { it.first } ?: 1.0
        val shortcut = markNeed.minOfOrNull { it.second } ?: 1.0
        val marksWhole = symbol == 1.0 && shortcut == 1.0
        val settled = keys.indices.map { k ->
            val key = keys[k]
            if (marksWhole && whole(k) && scales.getValue(key.glyphClass) == 1.0) return@map asIs[k]!!
            val sc = scales.getValue(key.glyphClass)
            settle(drawn[k], drawn[k].at(sc), marksAt(k, symbol, shortcut, symbol = true), gap, key to key.at(sc))
        }
        return Board(scales, symbol, shortcut, settled)
    }

    /** Every choice is made on [was] (before pixel snapping), so one keyboard size gives one picture. */
    fun onto(key: Member, was: Member, board: Board, settled: Settled): Settled {
        val s = board.scales.getValue(key.glyphClass)
        val g0 = was.at(s)
        val glyph = key.at(s).moved(settled.glyph.left - g0.left, settled.glyph.top - g0.top)
        val marks = key.marks.mapIndexed { i, m ->
            settled.marks[i]?.let { c ->
                val t = board.markScale(m)
                if (t == 1.0 && c == was.marks[i].ink) return@let m.within(key.edge)
                val w = was.marks[i].scaled(t)
                val at = if (c == w.within(was.edge).ink) m.scaled(t).let { it.copy(ink = it.ink.moved(c.left - w.ink.left, c.top - w.ink.top)) } else m.copy(ink = c)
                at.within(key.edge)
            }
        }
        // pixel snapping can bring two marks [was] keeps apart together again: part them once more
        val shown = marks.indices.filter { marks[it] != null }
        val apart = keptApart(shown.map { marks[it]!! }, key.edge)
        return Settled(glyph, marks.indices.map { i -> shown.indexOf(i).takeIf { it >= 0 }?.let { apart[it]?.ink } })
    }

    /** [key] after [onto], its glyph moved to [glyph]: kept marks still meeting it are placed again, else left out. */
    fun again(key: Member, glyph: Ink, board: Board, settled: Settled, gap: Double): Settled {
        if (settled.marks.none { it != null && it.meets(glyph, slack(gap)) }) return settled.copy(glyph = glyph)
        val kept = key.marks.indices.filter { settled.marks[it] != null }
        val apart = keptApart(kept.map { i -> key.marks[i].let { m -> m.scaled(board.markScale(m)).within(key.edge) } }, key.edge)
        val marks = key.marks.indices.map { i -> kept.indexOf(i).let { at -> if (at < 0) null else apart[at] } }
        return settle(key.copy(moves = false), glyph, marks, gap)
    }

    /** Whether [key]'s marks fit its glyph as it is: if so no text need be drawn shorter. */
    fun fitsWhole(key: Member, gap: Double): Boolean {
        val apart = keptApart(key.marks.map { it.within(key.edge) }, key.edge)
        if (apart.any { it == null }) return false
        val marks = apart.filterNotNull()
        return place(key.glyph, marks, key.copy(moves = false), gap) != null
    }

    fun crowded(key: Member, gap: Double): Set<Int> {
        val inks = key.marks.map { it.within(key.edge).ink }
        return inks.indices.filter { i -> inks[i].meets(key.glyph, slack(gap)) || inks.indices.any { j -> j != i && inks[j].meets(inks[i], 0.0) } }.toSet()
    }

    private fun slack(gap: Double) = (gap - FIT_SLACK).coerceAtLeast(0.0)

    private fun Mark.sizeIn(t: Double, u: Double, symbol: Boolean?) = if (symbol == null || (drop == DROP_SYMBOL) == symbol) t else u

    private fun glyphAsks(floor: Double, minMark: Double, marks: (Double) -> List<Mark?>, fits: (Double, List<Mark?>) -> Boolean): Double {
        val least = 1.0 - MAX_CLASS_SHRINK
        val live = marks(largest(floor) { t -> marks(t).all { it != null } }).filter { it != null }
        val need = largest(least) { s -> fits(s, live) }
        val saved = if (minMark >= 1.0) 1.0 else share(largest(minMark - HOPELESS_BAND) { t -> fits(least, marks(t)) }, minMark)
        return 1.0 - (1.0 - need) * saved
    }

    // each class starts from a size that fits, so it moves with the room and never flips which gives way
    private fun markNeed(floor: Double, minMark: Double, fits: (Double, Double, Boolean?) -> Boolean): Pair<Double, Double> {
        fun asked(t: Double) = if (t >= minMark) t else 1.0 - (1.0 - minMark) * share(t, minMark)
        val both = largest(floor) { t -> fits(t, t, null) }
        if (both == 1.0) return 1.0 to 1.0
        val w = share(both, minMark)
        val (symbol, shortcut) = if (w > 0.0) {
            val s = largest(both) { t -> fits(t, both, true) }
            asked(s) to asked(largest(both) { u -> fits(s, u, true) })
        } else {
            1.0 to 1.0
        }
        val symbolAlone = asked(largest(floor) { t -> fits(t, 0.0, true) })
        val shortcutAlone = asked(largest(floor) { u -> fits(0.0, u, true) })
        return (w * symbol + (1 - w) * symbolAlone) to (w * shortcut + (1 - w) * shortcutAlone)
    }

    private fun share(t: Double, minMark: Double) = ((t - (minMark - HOPELESS_BAND)) / HOPELESS_BAND).coerceIn(0.0, 1.0)

    private fun largest(least: Double, fits: (Double) -> Boolean): Double {
        if (fits(1.0)) return 1.0
        if (least >= 1.0 || !fits(least)) return least
        var lo = least
        var hi = 1.0
        while (hi - lo > SCALE_STEP) {
            val m = (lo + hi) / 2.0
            if (fits(m)) lo = m else hi = m
        }
        return lo
    }

    // about its corner (an edge's middle: its top's middle)
    private fun Mark.scaled(t: Double): Mark {
        if (t == 1.0) return this
        val f = ink.inFrame(slot)
        val x = if (slot.centred) (f.left + f.right) / 2.0 else f.right
        return copy(ink = f.scaled(t, x, f.top).inFrame(slot).grown(grows, t))
    }

    private fun settle(key: Member, glyph: Ink, marks: List<Mark?>, gap: Double, aside: Pair<Member, Ink>? = null): Settled {
        val kept = marks.toMutableList()
        while (true) {
            val live = kept.filterNotNull()
            val s = place(glyph, live, key, gap) ?: aside?.let { (m, g) -> place(g, live, m, gap) }
            if (s != null) {
                val each = s.marks.iterator()
                return Settled(s.glyph, kept.map { if (it == null) null else each.next() })
            }
            val far = maxOf(key.edge.face.width, key.edge.face.height)
            val out = live.map { out(glyph, it, key.room, key.edge, gap, inward = far) }
            val clash = out.indices.filter { i -> out[i].meets(glyph, slack(gap)) || out.indices.any { j -> j != i && out[j].meets(out[i], 0.0) } }
            val worst = clash.ifEmpty { live.indices.toList() }.maxBy { live[it].expendable }
            kept[kept.indexOfFirst { it === live[worst] }] = null
        }
    }

    private fun Mark.within(edge: Edge): Mark {
        if (cornersIn(ink, edge)) return this
        val f = edge.face
        val dx = (f.left + f.right - ink.left - ink.right) / 2.0
        val dy = (f.top + f.bottom - ink.top - ink.bottom) / 2.0
        return listOf(dx to 0.0, 0.0 to dy)
            .filter { (x, y) -> cornersIn(ink.moved(x, y), edge) }
            .map { (x, y) ->
                val most = abs(x) + abs(y)
                leastIn(most) { d -> cornersIn(ink.moved(x * d / most, y * d / most), edge) }.let { d -> ink.moved(x * d / most, y * d / most) }
            }
            .minByOrNull { abs(it.left - ink.left) + abs(it.top - ink.top) }
            ?.let { copy(ink = it) } ?: this
    }

    private fun cornersIn(i: Ink, edge: Edge): Boolean {
        val f = edge.face
        // across within the face (a wider mark would overlap neighbours); up and down a mark may stand in the insets
        if (i.left < f.left - 1e-9 || i.right > f.right + 1e-9) return false
        // no list allocated: this runs inside the board searches, many times a build
        for (n in edge.radii.indices) {
            val r = edge.radii[n]
            if (r <= edge.line) continue
            val left = n == 0 || n == 3
            val x = if (left) i.left else i.right
            val y = if (n < 2) i.top else i.bottom
            val cx = if (left) f.left + r else f.right - r
            val cy = if (n < 2) f.top + r else f.bottom - r
            val outX = if (left) x < cx else x > cx
            val outY = if (n < 2) y < cy else y > cy
            if (outX && outY && sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) > r - edge.line + 1e-9) return false
        }
        return true
    }

    private fun Member.aside(): Member {
        val still = copy(moves = false)
        if (!moves || glyph.left.isNaN() || marks.isEmpty()) return still
        // a flag, not a coordinate: a room of no width has both sides at one coordinate
        val toRight = when {
            marks.all { it.slot.left } -> true
            marks.all { it.slot.right } -> false
            else -> return still
        }
        val dx = if (toRight) room.right - glyph.right else room.left - glyph.left
        return still.copy(glyph = glyph.moved(dx, 0.0), x = if (toRight) room.right else room.left)
    }

    // what is wider still was shrunk alone before (SceneBuilder.fitted)
    private fun Member.across(): Double {
        if (!across || glyph.left.isNaN() || glyph.width <= room.width) return 1.0
        return largest(1.0 - MAX_CLASS_SHRINK) { s -> at(s).width <= room.width }
    }

    private fun Member.at(s: Double): Ink = if (s == 1.0) glyph else glyph.scaled(s, x, y).grown(grows, s)

    private fun Ink.grown(grows: Grows?, s: Double): Ink =
        grows?.at(s)?.let { d -> Ink(left - d.left, top - d.top, right + d.right, bottom + d.bottom) } ?: this

    private fun <T : Any> least(all: T, whole: Double, step: Double, clears: (Double) -> T?): T {
        var lo = 0.0
        var hi = whole
        var best = all
        while (hi - lo > step) {
            val mid = (lo + hi) / 2.0
            val c = clears(mid)
            if (c != null) { hi = mid; best = c } else lo = mid
        }
        return best
    }

    // always [gap] less [FIT_SLACK], or a mark short by less would jump
    private fun place(glyph: Ink, marks: List<Mark>, key: Member, gap: Double): Settled? =
        placed(glyph, marks, key, slack(gap))?.takeIf { s -> s.marks.all { cornersIn(it!!, key.edge) } }

    private fun placed(glyph: Ink, marks: List<Mark>, key: Member, gap: Double): Settled? {
        val room = key.room
        val edge = key.edge
        val inks = marks.map { it.ink }
        if (marks.none { it.ink.meets(glyph, gap) } && allApart(inks)) return Settled(glyph, inks)
        fun ok(p: List<Ink>): Settled? = when {
            key.moves -> clearOf(glyph, p, room, gap)?.let { Settled(it, p) }
            allApart(p) && p.none { it.meets(glyph, gap) } -> Settled(glyph, p)
            else -> null
        }
        ok(marks.map { out(glyph, it, room, edge, gap, 0.0) })?.let { return it }
        fun share(f: Double) = ok(marks.map { out(glyph, it, room, edge, gap, f) })
        share(1.0)?.let { return least(it, 1.0, SHARE_STEP, ::share) }
        val far = maxOf(edge.face.width, edge.face.height)
        fun along(inward: Double) = ok(marks.map { out(glyph, it, room, edge, gap, inward = inward) })
        return along(far)?.let { least(it, far, OUT_PX, ::along) }
    }

    private fun keptApart(marks: List<Mark>, edge: Edge): List<Mark?> {
        val meeting = marks.indices.filter { i -> marks.indices.any { j -> j != i && marks[i].ink.meets(marks[j].ink, 0.0) } }
        if (meeting.isEmpty()) return marks
        outwards(marks, meeting, edge)?.let { return it }
        val now = marks.toMutableList<Mark?>()
        while (true) {
            val clash = now.indices.filter { i -> now[i] != null && now.indices.any { j -> j != i && now[j]?.ink?.meets(now[i]!!.ink, 0.0) == true } }
            if (clash.isEmpty()) return now
            now[clash.maxBy { marks[it].expendable }] = null
        }
    }

    // two boxes part on one axis, so both is least only where marks part on different ones
    private fun outwards(marks: List<Mark>, meeting: List<Int>, edge: Edge): List<Mark>? {
        fun moved(d: Double, up: Boolean, side: Boolean): List<Mark> = marks.mapIndexed { i, m ->
            if (i !in meeting) return@mapIndexed m
            val f = m.ink.inFrame(m.slot)
            val dy = if (up) minOf(d, upRoom(f, m.slot, edge)) else 0.0
            val dx = if (side) minOf(d, sideRoom(f.moved(0.0, -dy), m.slot, edge)) else 0.0
            m.copy(ink = f.moved(dx, -dy).inFrame(m.slot))
        }
        fun apart(d: Double, up: Boolean, side: Boolean) = allApart(moved(d, up, side).map { it.ink })
        if (apart(0.0, up = false, side = false)) return marks
        val far = maxOf(edge.face.width, edge.face.height)
        return listOf(true to false, false to true, true to true)
            .filter { (up, side) -> apart(far, up, side) }
            .map { (up, side) ->
                var lo = 0.0
                var hi = far
                while (hi - lo > OUT_PX) {
                    val d = (lo + hi) / 2.0
                    if (apart(d, up, side)) hi = d else lo = d
                }
                moved(hi, up, side)
            }
            .minByOrNull { out -> out.indices.sumOf { i -> abs(out[i].ink.left - marks[i].ink.left) + abs(out[i].ink.top - marks[i].ink.top) } }
    }

    private fun upRoom(m: Ink, slot: Slot, edge: Edge): Double {
        val e = edge.face.inFrame(slot)
        var least = maxOf(edge.floor, arcLeast(e.right - m.right, edge.radius(slot, right = true), edge.line))
        if (slot.centred) least = maxOf(least, arcLeast(m.left - e.left, edge.radius(slot, right = false), edge.line))
        return (m.top - e.top - least).coerceAtLeast(0.0)
    }

    private fun sideRoom(m: Ink, slot: Slot, edge: Edge): Double {
        if (slot.centred) return 0.0
        val e = edge.face.inFrame(slot)
        val least = maxOf(
            edge.floor,
            arcLeast(m.top - e.top, edge.radius(slot, right = true), edge.line),
            arcLeast(e.bottom - m.bottom, edge.bottomRadius(slot, right = true), edge.line),
        )
        return (e.right - m.right - least).coerceAtLeast(0.0)
    }

    private fun arcLeast(a: Double, r: Double, line: Double): Double {
        if (r <= line || a >= r) return line
        val reach = r - line
        val across = r - a
        if (across >= reach) return Double.POSITIVE_INFINITY
        return r - sqrt(reach * reach - across * across)
    }

    private fun allApart(inks: List<Ink>): Boolean = inks.indices.all { i -> inks.indices.all { j -> j <= i || !inks[i].meets(inks[j], 0.0) } }

    private fun clearOf(glyph: Ink, marks: List<Ink>, room: Ink, gap: Double): Ink? {
        if (!allApart(marks)) return null
        val meeting = marks.filter { it.meets(glyph, gap) }
        if (meeting.isEmpty()) return glyph
        val dxs = listOf(0.0) + meeting.flatMap { listOf(it.right + gap - glyph.left, it.left - gap - glyph.right) }
        val dys = listOf(0.0) + meeting.flatMap { listOf(it.bottom + gap - glyph.top, it.top - gap - glyph.bottom) }
        val bounds = Ink(minOf(room.left, glyph.left), minOf(room.top, glyph.top), maxOf(room.right, glyph.right), maxOf(room.bottom, glyph.bottom))
        return dxs.flatMap { dx -> dys.map { dy -> dx to dy } }
            .sortedBy { (dx, dy) -> abs(dx) + abs(dy) }
            .map { (dx, dy) -> glyph.moved(dx, dy) }
            .firstOrNull { g ->
                g.left >= bounds.left && g.top >= bounds.top && g.right <= bounds.right && g.bottom <= bounds.bottom &&
                    marks.none { it.meets(g, gap) }
            }
    }


    private fun Ink.inFrame(slot: Slot): Ink {
        var i = this
        if (slot.left) i = Ink(-i.right, i.top, -i.left, i.bottom)
        if (slot.bottom) i = Ink(i.left, -i.bottom, i.right, -i.top)
        return i
    }

    // on one axis only (moving on the other parts nothing); the one clearing with the less move wins
    private fun out(glyph: Ink, mark: Mark, room: Ink, edge: Edge, gap: Double, share: Double = 1.0, inward: Double = 0.0): Ink {
        if (!mark.ink.meets(glyph, gap)) return mark.ink
        val s = mark.slot
        val g = glyph.inFrame(s)
        val m = mark.ink.inFrame(s)
        val r = room.inFrame(s)
        val over = g.right + gap - m.left
        val under = m.bottom - (g.top - gap)
        fun most(whole: Double, free: Double) = free + (whole - free).coerceAtLeast(0.0) * share
        val upward = most(upRoom(m, s, edge), minOf(m.top - r.top, upRoom(m, s, edge)).coerceAtLeast(0.0))
        val up = upBy(m, s, edge, under, upward, inward)
        if (s.centred) return up.inFrame(s)
        val sideRoom = sideRoom(m, s, edge)
        val side = sideBy(m, s, edge, over, most(sideRoom, minOf(r.right - m.right, sideRoom).coerceAtLeast(0.0)), inward)
        val upClears = !up.meets(g, gap)
        val sideClears = !side.meets(g, gap)
        fun moved(i: Ink) = abs(i.left - m.left) + abs(i.top - m.top)
        val pick = when {
            upClears && sideClears -> if (moved(side) < moved(up)) side else up
            upClears -> up
            sideClears -> side
            else -> if (under - (m.top - up.top) <= over - (side.left - m.left)) up else side
        }
        return pick.inFrame(s)
    }

    private fun upBy(m: Ink, slot: Slot, edge: Edge, under: Double, upward: Double, inward: Double): Ink {
        if (under <= upward) return m.moved(0.0, -under)
        val e = edge.face.inFrame(slot)
        val most = if (slot.centred) 0.0 else minOf(inward, edge.radius(slot, right = true) - (e.right - m.right)).coerceAtLeast(0.0)
        if (most <= 0.0) return m.moved(0.0, -upward)
        val x = leastIn(most) { x -> upRoom(m.moved(-x, 0.0), slot, edge) >= under }
        val i = m.moved(-x, 0.0)
        return i.moved(0.0, -minOf(under, upRoom(i, slot, edge)))
    }

    private fun sideBy(m: Ink, slot: Slot, edge: Edge, over: Double, side: Double, inward: Double): Ink {
        if (over <= side) return m.moved(over, 0.0)
        val e = edge.face.inFrame(slot)
        val most = minOf(inward, edge.radius(slot, right = true) - (m.top - e.top)).coerceAtLeast(0.0)
        if (most <= 0.0) return m.moved(side, 0.0)
        val y = leastIn(most) { y -> sideRoom(m.moved(0.0, y), slot, edge) >= over }
        val i = m.moved(0.0, y)
        return i.moved(minOf(over, sideRoom(i, slot, edge)), 0.0)
    }

    private fun leastIn(most: Double, ok: (Double) -> Boolean): Double {
        if (ok(0.0)) return 0.0
        if (!ok(most)) return most
        var lo = 0.0
        var hi = most
        while (hi - lo > OUT_PX) {
            val d = (lo + hi) / 2.0
            if (ok(d)) hi = d else lo = d
        }
        return hi
    }

    private fun mid(ink: Ink): Double = (ink.left + ink.right) / 2.0
}
