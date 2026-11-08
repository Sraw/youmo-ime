/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.rules

import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Slot
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Copied from ime-core's `input.keyboard.KeyMarkYield` for P1 (060-render-v2 §6), Float made Double (the
 * engine's numbers are the same on the JVM and in JS only so); S4e (060-render-p2) made it its own.
 *
 * How a key's main glyph (its letter, label or icon) and the marks in its slots ([KeyMarks]: its
 * symbol, its shortcuts) keep out of each other's way, measured by their ink, not their views: a view
 * is the line's height and advance, and on 大千's keys it met the icon where the glyphs did not,
 * moving symbols that touched nothing. Nothing moves while nothing meets.
 *
 * A keyboard's glyphs come in classes ([group]: its main characters, its function keys' icons, their
 * labels), and a class is one size: the user's 「同类型的键大小应该一样」. Marks are one size each too
 * (every symbol, every shortcut icon): no mark and no glyph is ever smaller on its own.
 *
 * Every slot is worked out as the top right one, the key mirrored side for side or top for bottom
 * ([inFrame]). A mark gives way, the lightest first, all within its own corner (a shortcut's corner
 * is the swipe it stands for, so it never goes to another): out towards its corner within the room
 * (a corner's sideways or up, whichever clears in less; the edge's middle only up); then out into its
 * inset, the least that clears, as far as the edge ([Edge]: a quarter dp in from the face's sides and
 * its border, `Content.MARK_EDGE_DP`, and its box's corner within the face's rounded one); then in
 * along the arc, the least. Where a glyph may move ([Member.moves]: not a main character, whose row
 * shares a baseline) it moves the least to clear what is left. Still meeting: the whole class smaller,
 * every glyph of it as much, the most any of its keys needs, but [MAX_CLASS_SHRINK] at most; a key
 * whose marks still meet its glyph then has them left out, the most expendable first ([Mark.drop]: a
 * function key's down shortcut, a shortcut, the symbol last; the swipe and the long press still work).
 * Every step goes as far as it must, no further, in px, not whole px: whether a mark is drawn, or how
 * big the glyphs are, never turns on how a dp rounds.
 *
 * Into its inset, a mark moves on one axis, the one that parts it from what it meets: two boxes part
 * when they part on either, so a move on the other changes the key and parts nothing. Of sideways and
 * up, the less; where neither alone clears, it goes as far as it can on the one with less left to go.
 * It eats as little of its inset as clears: on a key, the marks a share of theirs, the least that
 * clears; so a px more or less of key moves a mark by about that px, never a whole inset at once.
 *
 * Marks meeting each other ([keptApart]): out into their insets, all as far; what that doesn't part
 * is left out, the most expendable first.
 *
 * ime-core's copy, the views', is the older rule (marks smaller, other corners); P2 deletes it.
 */
object KeyMarkYield {

    /** A box's sides, in one view's coordinates. */
    data class Ink(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top

        fun moved(dx: Double, dy: Double) = Ink(left + dx, top + dy, right + dx, bottom + dy)

        /** Scaled by [s] about ([x], [y]). */
        fun scaled(s: Double, x: Double, y: Double) = Ink(x + (left - x) * s, y + (top - y) * s, x + (right - x) * s, y + (bottom - y) * s)

        /** Whether it and [o] come closer than [gap]; touching, with no gap asked, is apart. */
        fun meets(o: Ink, gap: Double): Boolean =
            left < o.right + gap && o.left < right + gap && top < o.bottom + gap && o.top < bottom + gap
    }

    /** A mark's [ink] in its [slot]; [drop], how soon it is left out where nothing else clears it (the higher the sooner). */
    data class Mark(val ink: Ink, val slot: Slot, val drop: Int = DROP_SHORTCUT)

    /** Where a key's glyph and marks end up: [glyph]'s new ink; each mark's in order, null for left out. */
    data class Settled(val glyph: Ink, val marks: List<Ink?>)

    /**
     * How far out a mark goes, its inset eaten: within [face], whose corners are rounded [radii] (top
     * left, top right, bottom right, bottom left), its box [floor] in from the face's sides and its box's
     * corner within the arcs less [line] (the border's width): a side is ink all along, a box's corner a
     * point most glyphs leave empty, the arc curving away from it both ways.
     */
    data class Edge(val face: Ink, val radii: List<Double> = NO_RADII, val floor: Double = 0.0, val line: Double = 0.0) {
        // the radius of [slot]'s frame's top right corner ([right]) or top left
        internal fun radius(slot: Slot, right: Boolean): Double {
            val left = slot.left == right
            return radii[if (slot.bottom) (if (left) 3 else 2) else (if (left) 0 else 1)]
        }

        // the radius of [slot]'s frame's bottom right corner ([right]) or bottom left
        internal fun bottomRadius(slot: Slot, right: Boolean): Double {
            val left = slot.left == right
            return radii[if (slot.bottom) (if (left) 0 else 1) else (if (left) 3 else 2)]
        }
    }

    /**
     * A key of a class: its [glyph]'s ink, smaller about ([x], [y]) (a text's middle on its baseline, an
     * icon's middle), whether it [moves] to clear its marks, its [marks], the [room] they move in, the
     * [edge] they go no further out than.
     */
    data class Member(
        val glyph: Ink, val x: Double, val y: Double, val marks: List<Mark>, val room: Ink, val edge: Edge = Edge(room), val moves: Boolean = true,
    )

    /** Every glyph of a class scaled by [scale] about its point; each key's [keys] settled. */
    data class Group(val scale: Double, val keys: List<Settled>)

    const val DROP_SYMBOL = 1
    const val DROP_SHORTCUT = 2

    /** A function key's down shortcut: the first left out, the up one the one swiped more. */
    const val DROP_DOWN_SHORTCUT = 3

    /**
     * A class at most this much smaller (060-render-p2 S4e): the most a class needs where a mark eaten
     * into its inset and along its arc still meets the glyph, on the keyboards swept, a 0.4% step
     * from the size beside it reads as the same size; past it a mark is left out instead.
     */
    const val MAX_CLASS_SHRINK = 0.06

    // how finely a class's scale is found; a mark's way out into its inset, in px; the marks' share of theirs
    private const val SCALE_STEP = 0.001
    private const val OUT_PX = 0.001
    private const val SHARE_STEP = 0.001

    private val NO_RADII = listOf(0.0, 0.0, 0.0, 0.0)

    /**
     * A key's [glyph] (a label, an icon) and its [marks], within [room] (its face, clear of the rounded
     * edges), kept [gap] apart; the marks out as far as [edge]: the key a class of its own.
     */
    fun key(glyph: Ink, marks: List<Mark>, room: Ink, gap: Double, edge: Edge = Edge(room)): Settled =
        group(listOf(Member(glyph, mid(glyph), (glyph.top + glyph.bottom) / 2.0, marks, room, edge)), gap).keys[0]

    /**
     * A class's [keys], their marks kept [gap] from their glyphs: every glyph as much smaller, the least
     * that clears the key needing most, [MAX_CLASS_SHRINK] at most; what still meets then left out.
     */
    fun group(keys: List<Member>, gap: Double): Group {
        val apart = keys.map { k -> keptApart(k.marks.map { it.within(k.edge) }, k.edge) }
        val least = 1.0 - MAX_CLASS_SHRINK
        fun fits(k: Int, s: Double, marks: List<Mark>) = place(keys[k].at(s), marks, keys[k], gap)
        val need = keys.indices.map { k ->
            val live = apart[k].filterNotNull()
            when {
                fits(k, 1.0, live) != null -> 1.0
                fits(k, least, live) == null -> least
                else -> {
                    // the largest that clears, to a thousandth
                    var lo = least
                    var hi = 1.0
                    while (hi - lo > SCALE_STEP) {
                        val m = (lo + hi) / 2.0
                        if (fits(k, m, live) != null) lo = m else hi = m
                    }
                    lo
                }
            }
        }
        val scale = need.minOrNull() ?: 1.0
        return Group(scale, keys.indices.map { k -> settle(keys[k], keys[k].at(scale), apart[k], gap) })
    }

    // [key] with its glyph at [glyph]: its [marks] placed, those still meeting it left out the most expendable first
    private fun settle(key: Member, glyph: Ink, marks: List<Mark?>, gap: Double): Settled {
        val kept = marks.toMutableList()
        while (true) {
            val live = kept.filterNotNull()
            val s = place(glyph, live, key, gap)
            if (s != null) {
                val each = s.marks.iterator()
                return Settled(s.glyph, kept.map { if (it == null) null else each.next() })
            }
            // the most expendable of those still meeting where they go furthest (none: of all)
            val far = maxOf(key.edge.face.width, key.edge.face.height)
            val out = live.map { out(glyph, it, key.room, key.edge, gap, inward = far) }
            val clash = out.indices.filter { i -> out[i].meets(glyph, gap) || out.indices.any { j -> j != i && out[j].meets(out[i], 0.0) } }
            val worst = clash.ifEmpty { live.indices.toList() }.maxBy { live[it].drop }
            kept[kept.indexOfFirst { it === live[worst] }] = null
        }
    }

    // this mark with its box's corners within [edge]'s arcs (a short key's top mark can reach its bottom ones): moved
    // in the least, sideways or up and down, where one is out of one as placed
    private fun Mark.within(edge: Edge): Mark {
        if (cornersIn(ink, edge)) return this
        val f = edge.face
        // towards the face's middle, no further than to it
        val dx = (f.left + f.right - ink.left - ink.right) / 2.0
        val dy = (f.top + f.bottom - ink.top - ink.bottom) / 2.0
        return listOf(dx to 0.0, 0.0 to dy)
            .filter { (x, y) -> cornersIn(ink.moved(x, y), edge) }
            .map { (x, y) ->
                // the least, in px
                val most = abs(x) + abs(y)
                leastIn(most) { d -> cornersIn(ink.moved(x * d / most, y * d / most), edge) }.let { d -> ink.moved(x * d / most, y * d / most) }
            }
            .minByOrNull { abs(it.left - ink.left) + abs(it.top - ink.top) }
            ?.let { copy(ink = it) } ?: this
    }

    // whether [i]'s corners are all within [edge]'s arcs, less its line
    private fun cornersIn(i: Ink, edge: Edge): Boolean {
        val f = edge.face
        val corners = listOf(Triple(i.left, i.top, 0), Triple(i.right, i.top, 1), Triple(i.right, i.bottom, 2), Triple(i.left, i.bottom, 3))
        return corners.all { (x, y, n) ->
            val r = edge.radii[n]
            if (r <= edge.line) return@all true
            val cx = if (n == 0 || n == 3) f.left + r else f.right - r
            val cy = if (n < 2) f.top + r else f.bottom - r
            val outX = if (n == 0 || n == 3) x < cx else x > cx
            val outY = if (n < 2) y < cy else y > cy
            !outX || !outY || sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) <= r - edge.line + 1e-9
        }
    }

    private fun Member.at(s: Double): Ink = if (s == 1.0) glyph else glyph.scaled(s, x, y)

    // the least of 0 to [whole] (to [step]) that [clears], [all] clearing at the whole of it
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

    // [glyph] and [marks] of [key] apart, each mark in its own corner: moved within the room, then out into its
    // inset (the least share of it), then in along its arc (the least); the glyph moved where it [Member.moves];
    // null where nothing clears
    private fun place(glyph: Ink, marks: List<Mark>, key: Member, gap: Double): Settled? {
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

    // [marks] apart from each other: those meeting out into their insets towards [edge], all as far, the least
    // that parts them; what that doesn't part left out, the most expendable first
    private fun keptApart(marks: List<Mark>, edge: Edge): List<Mark?> {
        val meeting = marks.indices.filter { i -> marks.indices.any { j -> j != i && marks[i].ink.meets(marks[j].ink, 0.0) } }
        if (meeting.isEmpty()) return marks
        outwards(marks, meeting, edge)?.let { return it }
        val now = marks.toMutableList<Mark?>()
        while (true) {
            val clash = now.indices.filter { i -> now[i] != null && now.indices.any { j -> j != i && now[j]?.ink?.meets(now[i]!!.ink, 0.0) == true } }
            if (clash.isEmpty()) return now
            now[clash.maxBy { marks[it].drop }] = null
        }
    }

    // [marks]' [meeting] ones out into their insets, towards [edge], each as far as the others or as it can,
    // the least that parts them all: up (down), sideways, or both, whichever moves them least in all (two
    // boxes part on one axis, so both is only ever the least where the marks part on different ones);
    // null if none does
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
                // the least that parts them, to a thousandth of a px
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

    // how far up the top right [m] (in [slot]'s frame) goes into its inset: to [edge]'s floor from the top,
    // and from the corner's arc where its right side is (an edge's middle: both its top corners)
    private fun upRoom(m: Ink, slot: Slot, edge: Edge): Double {
        val e = edge.face.inFrame(slot)
        var least = maxOf(edge.floor, arcLeast(e.right - m.right, edge.radius(slot, right = true), edge.line))
        if (slot.centred) least = maxOf(least, arcLeast(m.left - e.left, edge.radius(slot, right = false), edge.line))
        return (m.top - e.top - least).coerceAtLeast(0.0)
    }

    // how far right the top right [m] (in [slot]'s frame) goes into its inset, as [upRoom] (an edge's middle: none)
    private fun sideRoom(m: Ink, slot: Slot, edge: Edge): Double {
        if (slot.centred) return 0.0
        val e = edge.face.inFrame(slot)
        // its bottom right corner too: a short key's top mark reaches its bottom arc
        val least = maxOf(
            edge.floor,
            arcLeast(m.top - e.top, edge.radius(slot, right = true), edge.line),
            arcLeast(e.bottom - m.bottom, edge.bottomRadius(slot, right = true), edge.line),
        )
        return (e.right - m.right - least).coerceAtLeast(0.0)
    }

    // how near a side a box's corner [a] from the other side comes, within a corner rounded [r] less [line]
    private fun arcLeast(a: Double, r: Double, line: Double): Double {
        if (r <= line || a >= r) return line
        val reach = r - line
        val across = r - a
        if (across >= reach) return Double.POSITIVE_INFINITY
        return r - sqrt(reach * reach - across * across)
    }

    private fun allApart(inks: List<Ink>): Boolean = inks.indices.all { i -> inks.indices.all { j -> j <= i || !inks[i].meets(inks[j], 0.0) } }

    // [glyph] moved the least, within [room] (or no further out of it than it was), clear of [marks]
    // and they of each other; null where no move does
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

    // ---- one slot, worked out as the top right corner

    // [ink] as the top right slot sees it from [slot] (its own inverse)
    private fun Ink.inFrame(slot: Slot): Ink {
        var i = this
        if (slot.left) i = Ink(-i.right, i.top, -i.left, i.bottom)
        if (slot.bottom) i = Ink(i.left, -i.bottom, i.right, -i.top)
        return i
    }

    // [mark] out of [glyph]'s way, the least, on one axis (two boxes part on either, so a move on the other
    // changes the key and parts nothing): up (an edge's middle: only up) or sideways, as far as [room] goes
    // and [share] of its inset past that, towards [edge]; short of it, in along its arc up to [inward] the
    // least that lets it so far. Of the two, the one that clears with the less move; neither: the one with
    // less still to go. A hair's meeting moves the mark a hair: a px more or less of key never moves it a
    // whole width sideways
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

    // the top right [m] up by [under], [upward] at most; short of it, in from its side up to [inward] (no further
    // than the arc gives more for) the least that lets it up so far, else as far as that lets it
    private fun upBy(m: Ink, slot: Slot, edge: Edge, under: Double, upward: Double, inward: Double): Ink {
        if (under <= upward) return m.moved(0.0, -under)
        val e = edge.face.inFrame(slot)
        val most = if (slot.centred) 0.0 else minOf(inward, edge.radius(slot, right = true) - (e.right - m.right)).coerceAtLeast(0.0)
        if (most <= 0.0) return m.moved(0.0, -upward)
        val x = leastIn(most) { x -> upRoom(m.moved(-x, 0.0), slot, edge) >= under }
        val i = m.moved(-x, 0.0)
        return i.moved(0.0, -minOf(under, upRoom(i, slot, edge)))
    }

    // the top right [m] sideways by [over], [side] at most; short of it, in from its top (down) as [upBy]
    private fun sideBy(m: Ink, slot: Slot, edge: Edge, over: Double, side: Double, inward: Double): Ink {
        if (over <= side) return m.moved(over, 0.0)
        val e = edge.face.inFrame(slot)
        val most = minOf(inward, edge.radius(slot, right = true) - (m.top - e.top)).coerceAtLeast(0.0)
        if (most <= 0.0) return m.moved(side, 0.0)
        val y = leastIn(most) { y -> sideRoom(m.moved(0.0, y), slot, edge) >= over }
        val i = m.moved(0.0, y)
        return i.moved(minOf(over, sideRoom(i, slot, edge)), 0.0)
    }

    // the least of 0 to [most] (to [OUT_PX]) where [ok]; [most] where none is
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
