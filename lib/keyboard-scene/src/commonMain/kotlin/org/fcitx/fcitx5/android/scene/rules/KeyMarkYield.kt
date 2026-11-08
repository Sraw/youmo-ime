/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.rules

import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Slot
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Copied from ime-core's `input.keyboard.KeyMarkYield` for P1 (060-render-v2 §6), Float made Double (the
 * engine's numbers are the same on the JVM and in JS only so); P2 deletes that one.
 *
 * How a key's main glyph (its letter, label or icon) and the marks in its slots ([KeyMarks]: its
 * symbol, its shortcuts) keep out of each other's way, measured by their ink, not their views: a view
 * is the line's height and advance, and on 大千's keys it met the icon where the glyphs did not,
 * moving symbols that touched nothing. Nothing moves while nothing meets.
 *
 * Every slot is worked out as the top right one, the key mirrored side for side or top for bottom
 * ([inFrame]). A mark gives way, the lightest first: out towards its corner within the room (a
 * corner's sideways or up, whichever clears in less; the edge's middle only up); then smaller about
 * its corner, to its least ([Mark.minScale]: a symbol 6 dp, an icon 7 dp, below which they are not
 * read); then, at its least, out into its inset, the least that clears, as far as the edge ([Edge]:
 * a quarter dp in from the face's sides and its border, `Content.MARK_EDGE_DP`, and its box's corner
 * within the face's rounded one); then into another corner; last, left out.
 * Why so: moving within the room costs nothing seen. Smaller keeps the mark where the theme put it,
 * its corner on the line its neighbours' share, so it comes before the inset, which moves that
 * corner; both stop where they would cost something (a mark no longer read; its ink at the face's
 * side, a quarter dp still between, half a device px or more at any density from 2; its box's corner
 * at the arc, which curves away from it both ways). Another corner is where the user no longer looks
 * for it; left out, it is gone. Every step goes as far as it must, no further, in px, not whole px:
 * whether a mark is drawn, or how big the letters are, never turns on how a dp rounds (a small
 * phone's ⌫, 2.75 px a dp, compact, had its two icons 0.5 px short at their least: one was left out,
 * before the inset step).
 *
 * Into its inset, a mark moves on one axis, the one that parts it from what it meets: two boxes part
 * when they part on either, so a move on the other changes the key and parts nothing (in a rounded
 * corner it takes the box's corner out towards the arc besides). Of sideways and up, the less; where
 * both would do as well, which it takes turns on a hair, the mark's move as big either way (no move
 * between them parts it: it would have to go both ways, the move this rule has no use for). Where
 * neither alone clears, it goes as far as it can on the one with less left to go, and the glyph moves
 * or is smaller for the rest. It eats as little of its inset as clears: on a key, the marks a share of
 * theirs, the least that clears with the glyph moved; the glyph smaller only once they are at the edge,
 * by the least hundredth, and they then go out by what that hundredth still leaves; so a px more or
 * less of key moves a mark or the glyph by about that px, never a whole inset at once.
 *
 * Marks meeting each other ([keptApart]): smaller together about their corners, all as much, then out
 * into their insets, all as far. A key's label or icon ([key]): the marks moved first, then it moves
 * itself (any way, the least), then the marks smaller, then out into their insets, then it is smaller
 * about its middle, a fifth at most ([MAX_LABEL_SHRINK]). A keyboard's letters ([letters]): they stay
 * where they are, a row's on one baseline at one size; the marks move, are smaller and go out into
 * their insets first; what still meets, every letter is as much smaller about its baseline, a quarter
 * at most ([MAX_LETTER_SHRINK]). The glyph gives way after its marks have given all they can: it is
 * what the key is; the letters more so, one size for the whole board, so that a px one key's mark
 * lacks would make every letter smaller.
 *
 * Nothing of that enough: the key's shortcuts are tried in its other free corners ([Mark.elsewhere],
 * all at once, a function key's two kept mirrored), each moved, smaller and out there as before; what
 * still meets the glyph or another mark is left out, the most expendable first ([Mark.drop]: a
 * function key's down shortcut, then a shortcut; the swipes still work), and the glyph gives way to
 * the rest only, as if it had never been there (no smaller for a mark not drawn). A symbol ([DROP_SYMBOL]) is
 * never left out (the long press's first, the user's to see): a shortcut meeting it goes instead, and
 * the glyph is as small as it goes ([MAX_LABEL_SHRINK], a letter's [MAX_LETTER_SHRINK]); were that
 * not enough, it is drawn over the glyph. Else whatever is drawn never meets anything else drawn on
 * its key; KeyMarksEverywhereUatTest walks every keyboard and finds no symbol over its glyph.
 *
 * ime-core's copy, the views', has no inset step (P2 deletes it); their insets, splitties' dp, were
 * whole px, cut down, which often left them the room the inset step now gives.
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

    /**
     * A mark's [ink] in its [slot], no smaller than [minScale] of it; [drop], how soon it is left out
     * where nothing else clears it (the higher the sooner; [DROP_SYMBOL] never); [elsewhere], the
     * slots it is tried in before that, in turn, with its ink there (a key's marks that have them, as
     * many each, moved together: [KeyMarks.elsewhere]).
     */
    data class Mark(
        val ink: Ink, val slot: Slot, val minScale: Double = 1.0, val drop: Int = DROP_SHORTCUT,
        val elsewhere: List<Pair<Slot, Ink>> = emptyList(),
    ) {
        // in its next slot elsewhere, the rest still to try
        internal fun next(): Mark = copy(slot = elsewhere[0].first, ink = elsewhere[0].second, elsewhere = elsewhere.drop(1))
    }

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
    }

    const val DROP_SYMBOL = 1
    const val DROP_SHORTCUT = 2

    /** A function key's down shortcut: the first left out, the up one the one swiped more. */
    const val DROP_DOWN_SHORTCUT = 3

    /** A symbol no smaller than this: 6 dp, about the cap height of a phone's smallest notification text. */
    const val MIN_SYMBOL_DP = 6.0

    /** An icon no smaller than this: 7 dp, a Material icon's strokes still apart (at 6 the clipboard is a blot). */
    const val MIN_ICON_DP = 7.0

    /** A label or a key's icon a fifth smaller at most. */
    const val MAX_LABEL_SHRINK = 0.2

    /** The letters a quarter smaller at most: their row, the keyboard's, the same size still. */
    const val MAX_LETTER_SHRINK = 0.25

    /** A mark of [dp] (a text's size, an icon's height) no smaller than [min] dp: as a scale of it. */
    fun minScale(min: Double, dp: Double): Double = if (dp <= 0.0) 1.0 else (min / dp).coerceAtMost(1.0)

    // the label's shrink tried in steps of a hundredth
    private const val STEP = 0.01

    // how finely a mark's way out into its inset is found, in px; the marks' share of theirs, to a thousandth
    private const val OUT_PX = 0.001
    private const val SHARE_STEP = 0.001

    private val NO_RADII = listOf(0.0, 0.0, 0.0, 0.0)

    /**
     * A key's [glyph] (a label, an icon) and its [marks], within [room] (its face, clear of the
     * rounded edges), kept [gap] apart; the marks out as far as [edge] where nothing else keeps them
     * (their insets eaten), [room] where none is given.
     */
    fun key(glyph: Ink, marks: List<Mark>, room: Ink, gap: Double, edge: Edge = Edge(room)): Settled {
        val apart = keptApart(marks, edge)
        val live = apart.filterNotNull()
        val s = keyApart(glyph, live, room, gap, edge)
        val settled = s.marks.iterator()
        return Settled(s.glyph, apart.map { if (it == null) null else settled.next() })
    }

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

    private fun keyApart(glyph: Ink, marks: List<Mark>, room: Ink, gap: Double, edge: Edge): Settled {
        if (marks.none { it.ink.meets(glyph, gap) }) return Settled(glyph, marks.map { it.ink })
        inPlace(glyph, marks, room, gap, edge)?.let { return it }
        // a shortcut that would be left out tried elsewhere (where it meets no other mark), the glyph as it
        // was: all the above again from there
        val small = glyph.scaled(1.0 - MAX_LABEL_SHRINK, mid(glyph), (glyph.top + glyph.bottom) / 2.0)
        val placed = marks.map { out(small, it, room, edge, gap) }
        val stuck = placed.indices.any { i -> marks[i].elsewhere.isNotEmpty() && (placed[i].meets(small, 0.0) || placed.indices.any { j -> j != i && placed[j].meets(placed[i], 0.0) }) }
        var moved = marks
        while (stuck && moved.any { it.elsewhere.isNotEmpty() }) {
            moved = moved.map { if (it.elsewhere.isEmpty()) it else it.next() }
            if (allApart(moved.map { it.ink })) return keyApart(glyph, moved, room, gap, edge)
        }
        val (kept, placedKept) = dropped(small, marks) { out(small, it, room, edge, gap) }
        // what is left out leaves the glyph its room: all the above again with the rest (fewer each time)
        val rest = kept.filterNotNull()
        if (rest.size == marks.size) return Settled(small, placedKept)
        val again = keyApart(glyph, rest, room, gap, edge)
        val each = again.marks.iterator()
        return Settled(again.glyph, kept.map { if (it == null) null else each.next() })
    }

    // [keyApart]'s steps with every mark in its own corner and kept; null where they leave it still meeting the glyph
    private fun inPlace(glyph: Ink, marks: List<Mark>, room: Ink, gap: Double, edge: Edge): Settled? {
        // the marks moved only, then smaller too: the glyph moved clear of them
        for (shrink in listOf(false, true)) {
            val placed = marks.map { into(glyph, it, room, edge, gap, if (shrink) it.minScale else 1.0) }
            clearOf(glyph, placed, room, gap)?.let { return Settled(it, placed) }
        }
        // the marks, at their least, out into their insets, all by the least share of what each has that, the
        // glyph moved as far as it goes, clears (from nothing, where the glyph's move alone stops clearing); then,
        // their insets all eaten, the glyph smaller about its middle, the least that clears with a move
        val cx = mid(glyph)
        val cy = (glyph.top + glyph.bottom) / 2.0
        var s = 1.0
        while (s >= 1.0 - MAX_LABEL_SHRINK - STEP / 2) {
            val small = glyph.scaled(s, cx, cy)
            fun clears(share: Double) = marks.map { out(small, it, room, edge, gap, share) }.let { p -> clearOf(small, p, room, gap)?.let { Settled(it, p) } }
            clears(1.0)?.let { return least(it, 1.0, SHARE_STEP, ::clears) }
            s -= STEP
        }
        val small = glyph.scaled(1.0 - MAX_LABEL_SHRINK, cx, cy)
        // the glyph at its least, a mark at its arc in along it, the least that clears (from nothing, where the
        // glyph's shrink stops clearing), before it is tried in another corner or left out
        val far = maxOf(edge.face.width, edge.face.height)
        fun along(inward: Double) = marks.map { out(small, it, room, edge, gap, inward = inward) }.let { p -> clearOf(small, p, room, gap)?.let { Settled(it, p) } }
        return along(far)?.let { least(it, far, OUT_PX, ::along) }
    }

    /** A letter key's: its [letter]'s ink and [baseline], its [marks], the [room] they move in, the [edge] they go no further out than. */
    data class LetterKey(val letter: Ink, val baseline: Double, val marks: List<Mark>, val room: Ink, val edge: Edge = Edge(room))

    /** Every letter scaled by [scale] about its ink's middle on its baseline; each key's [keys] settled. */
    data class Letters(val scale: Double, val keys: List<Settled>)

    /** How a keyboard's letters, [keys], and their marks keep [gap] apart. */
    fun letters(keys: List<LetterKey>, gap: Double): Letters {
        val apart = keys.map { keptApart(it.marks, it.edge) }
        // a shortcut that would need more than a quarter tried elsewhere first ([fitting])
        val live = apart.mapIndexed { k, marks -> fitting(keys[k], marks.filterNotNull()) }
        // what each letter would need, its marks as far in and small as they go; the most needed, for all,
        // but a quarter at most: a shortcut that needs more is left out, its letter kept; a symbol is never
        // left out, the letters a quarter smaller for it
        val least = 1.0 - MAX_LETTER_SHRINK
        val need = keys.flatMapIndexed { k, key ->
            live[k].mapNotNull { m ->
                val n = need(key, m, gap).scale
                when {
                    n >= least - STEP / 2 -> n
                    m.drop == DROP_SYMBOL -> least
                    else -> null
                }
            }
        }.minOrNull() ?: 1.0
        val scale = need.coerceAtLeast(1.0 - MAX_LETTER_SHRINK)
        // each mark then only as far in and small as the letter, so much smaller, leaves it, the way [need] takes it
        return Letters(scale, keys.mapIndexed { k, key ->
            val letter = if (scale < 1.0) key.letter.scaled(scale, mid(key.letter), key.baseline) else key.letter
            val settled = dropped(letter, live[k]) { m -> out(letter, m, key.room, key.edge, gap, up = need(key, m, gap).up) }.second.iterator()
            Settled(letter, apart[k].map { if (it == null) null else settled.next() })
        })
    }

    // the letter's scale [need]ed for a mark, and the axis it goes out on (null: nothing to choose)
    private class Need(val scale: Double, val up: Boolean?)

    // how much smaller [key]'s letter would be for [m], as far in and small as it goes: where neither axis
    // alone parts them, out on the one that leaves the letter larger (the letter smaller about its baseline
    // parts them on one axis too, so the other's move is wasted; which one is the letter's, not the px')
    // Each scale counted in [STEP]s before they are compared, a tie up: half a px either way never swaps them.
    // A mark that clears it (no smaller) goes the way it would for the letter its size, whatever size the letters
    // are: the way is the key's, not the keyboard's (the letters' size changing never swaps it).
    private fun need(key: LetterKey, m: Mark, gap: Double): Need {
        fun at(up: Boolean) = letterScale(key.letter, out(key.letter, m, key.room, key.edge, gap, up = up), m.slot, gap, mid(key.letter), key.baseline)
        if (!into(key.letter, m, key.room, key.edge, gap, m.minScale).meets(key.letter, gap)) {
            return Need(1.0, if (m.ink.meets(key.letter, gap)) way(key.letter, m, key.room, key.edge, gap) else null)
        }
        val up = at(true)
        val side = at(false)
        return if (floor(side / STEP + 1e-9) > floor(up / STEP + 1e-9)) Need(side, false) else Need(up, true)
    }

    // [key]'s [marks], a movable one that would meet its letter even a quarter smaller (left out: touching is
    // apart then, no gap) moved elsewhere with the others movable, the first place where none would and they
    // are apart; as they were if none
    private fun fitting(key: LetterKey, marks: List<Mark>): List<Mark> {
        fun outOfReach(m: Mark) = need(key, m, 0.0).scale < 1.0 - MAX_LETTER_SHRINK - STEP / 2
        if (marks.none { it.elsewhere.isNotEmpty() && outOfReach(it) }) return marks
        var moved = marks
        while (moved.any { it.elsewhere.isNotEmpty() }) {
            moved = moved.map { if (it.elsewhere.isEmpty()) it else it.next() }
            val fits = moved.none { m -> m !in marks && outOfReach(m) }
            if (fits && allApart(moved.map { it.ink })) return moved
        }
        return marks
    }

    // [marks], each where [place] puts it, less those still meeting [glyph] (none: only each other) or each
    // other: the movable ones first tried elsewhere, all at once (a function key's two stay mirrored), then
    // the most expendable left out first; a symbol never, kept where it meets. The marks as they end up
    // (moved, before [place]) and their inks
    private fun dropped(glyph: Ink?, marks: List<Mark>, place: (Mark) -> Ink): Pair<List<Mark?>, List<Ink?>> {
        val now = marks.toMutableList<Mark?>()
        val out = marks.map(place).toMutableList<Ink?>()
        while (true) {
            val clash = out.indices.filter { i ->
                val a = out[i] ?: return@filter false
                glyph?.let { a.meets(it, 0.0) } == true || out.indices.any { j -> j != i && out[j]?.meets(a, 0.0) == true }
            }
            if (clash.isEmpty()) return now to out
            val worst = clash.maxBy { marks[it].drop }
            // the symbol alone still meeting: drawn as it is (the user's hard rule: a symbol is never left out)
            if (marks[worst].drop <= DROP_SYMBOL) return now to out
            if (now[worst]?.elsewhere.isNullOrEmpty()) {
                out[worst] = null
                now[worst] = null
                continue
            }
            for (i in now.indices) {
                val m = now[i]?.takeIf { it.elsewhere.isNotEmpty() } ?: continue
                now[i] = m.next().also { out[i] = place(it) }
            }
        }
    }

    // [marks] apart from each other: those meeting smaller about their corners (an edge's middle, its
    // middle), all as much, the least that parts them, no smaller than each's own; then, at their least,
    // out into their insets towards [edge], all as far, the least that parts them; what that doesn't part
    // left out, the most expendable first
    private fun keptApart(marks: List<Mark>, edge: Edge): List<Mark?> {
        val meeting = marks.indices.filter { i -> marks.indices.any { j -> j != i && marks[i].ink.meets(marks[j].ink, 0.0) } }
        if (meeting.isEmpty()) return marks
        val least = meeting.minOf { marks[it].minScale }
        var s = 1.0 - STEP
        while (s >= least - STEP / 2) {
            val small = marks.mapIndexed { i, m -> if (i in meeting) m.smaller(maxOf(s, m.minScale)) else m }
            if (allApart(small.map { it.ink })) return small
            s -= STEP
        }
        val smallest = marks.mapIndexed { i, m -> if (i in meeting) m.smaller(m.minScale) else m }
        return outwards(smallest, meeting, edge) ?: dropped(null, marks) { it.ink }.first
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
        val least = maxOf(edge.floor, arcLeast(m.top - e.top, edge.radius(slot, right = true), edge.line))
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

    // this mark [s] its size about its corner, its smallest the same as it was; so too where it is tried elsewhere
    private fun Mark.smaller(s: Double): Mark =
        copy(ink = smaller(ink, slot, s), minScale = minScale / s, elsewhere = elsewhere.map { (at, i) -> at to smaller(i, at, s) })

    private fun smaller(ink: Ink, slot: Slot, s: Double): Ink {
        val f = ink.inFrame(slot)
        val x = if (slot.centred) mid(f) else f.right
        return f.scaled(s, x, f.top).inFrame(slot)
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

    // [mark] out of [glyph]'s way, within [room] (and [edge]: a mark already out in its inset on one axis
    // keeps its box's corner within the arc as it moves on the other): towards its corner, the least, then
    // smaller to [minScale]
    // ([prefer]: the way it goes where both would part them alone, up or sideways)
    private fun into(glyph: Ink, mark: Mark, room: Ink, edge: Edge, gap: Double, minScale: Double, prefer: Boolean? = null): Ink {
        if (!mark.ink.meets(glyph, gap)) return mark.ink
        val s = mark.slot
        return corner(glyph.inFrame(s), mark.ink.inFrame(s), room.inFrame(s), gap, minScale, s, edge, prefer).inFrame(s)
    }

    // the way [into] takes [mark] out of [glyph]'s way, up (true) or sideways; where it takes both, the one
    // with less still to go (as [out]), a tie up
    private fun way(glyph: Ink, mark: Mark, room: Ink, edge: Edge, gap: Double): Boolean {
        val s = mark.slot
        val g = glyph.inFrame(s)
        val m = mark.ink.inFrame(s)
        val r = room.inFrame(s)
        return way(g, m, r, gap, s, edge, null) ?: (s.centred || under(g, m, gap) - upRoomOf(m, r, s, edge) <= over(g, m, gap) - sideRoomOf(m, r, s, edge))
    }

    private fun over(glyph: Ink, mark: Ink, gap: Double) = glyph.right + gap - mark.left
    private fun under(glyph: Ink, mark: Ink, gap: Double) = mark.bottom - (glyph.top - gap)
    private fun sideRoomOf(mark: Ink, room: Ink, slot: Slot, edge: Edge) =
        if (slot.centred) 0.0 else minOf(room.right - mark.right, sideRoom(mark, slot, edge)).coerceAtLeast(0.0)
    private fun upRoomOf(mark: Ink, room: Ink, slot: Slot, edge: Edge) = minOf(mark.top - room.top, upRoom(mark, slot, edge)).coerceAtLeast(0.0)

    // the way [corner] takes the top right [mark]: sideways (false) where that alone parts them and it is the
    // less or up alone does not ([prefer] false: or up alone does too), up (true) where that alone does; null: both
    private fun way(glyph: Ink, mark: Ink, room: Ink, gap: Double, slot: Slot, edge: Edge, prefer: Boolean?): Boolean? {
        val over = over(glyph, mark, gap)
        val under = under(glyph, mark, gap)
        val sideways = !slot.centred && over <= sideRoomOf(mark, room, slot, edge)
        val upFits = under <= upRoomOf(mark, room, slot, edge)
        return when {
            sideways && (!upFits || (prefer ?: (over > under)) == false) -> false
            upFits -> true
            else -> null
        }
    }

    // the top right [mark] out of [glyph]'s way: right or up, whichever clears it in less ([Slot.centred]: up
    // only); neither alone: into [room]'s corner, then smaller about its top right (top middle), to [minScale];
    // no move past [edge]'s arc (within the room it never is: only a mark [keptApart] took out of it)
    private fun corner(glyph: Ink, mark: Ink, room: Ink, gap: Double, minScale: Double, slot: Slot, edge: Edge, prefer: Boolean?): Ink {
        val centred = slot.centred
        when (way(glyph, mark, room, gap, slot, edge, prefer)) {
            false -> return mark.moved(glyph.right + gap - mark.left, 0.0)
            true -> return mark.moved(0.0, -(mark.bottom - (glyph.top - gap)))
            null -> {}
        }
        val rightRoom = sideRoomOf(mark, room, slot, edge)
        val up = mark.moved(0.0, -upRoomOf(mark, room, slot, edge))
        val moved = up.moved(if (centred) 0.0 else minOf(rightRoom, sideRoom(up, slot, edge)).coerceAtLeast(0.0), 0.0)
        val byLeft = if (centred) 0.0 else (moved.right - (glyph.right + gap)) / moved.width
        val byBottom = (glyph.top - gap - moved.top) / moved.height
        val scale = maxOf(byLeft, byBottom).coerceIn(minScale, 1.0)
        val x = if (centred) mid(moved) else moved.right
        return moved.scaled(scale, x, moved.top)
    }

    // [mark] as [into] puts it at its least within [room]; still meeting [glyph], out into its inset (no
    // more than [share] of it) the least on the one axis that parts them, sideways or up, the less of the
    // two (as [corner]); where neither does alone, as far as it goes on the one with less still to go ([up]:
    // the one asked for), the glyph's move or shrink to do the rest (the other axis left: a move there parts nothing)
    private fun out(glyph: Ink, mark: Mark, room: Ink, edge: Edge, gap: Double, share: Double = 1.0, up: Boolean? = null, inward: Double = 0.0): Ink {
        val at = into(glyph, mark, room, edge, gap, mark.minScale, up)
        if (!at.meets(glyph, gap)) return at
        val s = mark.slot
        val g = glyph.inFrame(s)
        val m = at.inFrame(s)
        val over = g.right + gap - m.left
        val under = m.bottom - (g.top - gap)
        val side = sideRoom(m, s, edge) * share
        val upward = upRoom(m, s, edge) * share
        val sideways = !s.centred && over <= side
        val upFits = under <= upward
        val moved = when {
            up == true || s.centred -> upBy(m, s, edge, under, upward, inward)
            up == false -> sideBy(m, s, edge, over, side, inward)
            sideways && (over <= under || !upFits) -> m.moved(over, 0.0)
            upFits -> m.moved(0.0, -under)
            under - upward <= over - side -> upBy(m, s, edge, under, upward, inward)
            else -> sideBy(m, s, edge, over, side, inward)
        }
        return moved.inFrame(s)
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

    // how much smaller [letter] about ([x], [y]) clears [mark] in [slot]: by the side facing it or its top
    // (bottom), whichever needs less
    private fun letterScale(letter: Ink, mark: Ink, slot: Slot, gap: Double, x: Double, y: Double): Double {
        if (!letter.meets(mark, gap)) return 1.0
        val l = letter.inFrame(slot)
        val m = mark.inFrame(slot)
        val o = Ink(x, y, x, y).inFrame(slot)
        val byRight = if (!slot.centred && l.right > o.right) (m.left - gap - o.right) / (l.right - o.right) else 0.0
        val byTop = if (l.top < o.top) (o.top - (m.bottom + gap)) / (o.top - l.top) else 0.0
        return maxOf(byRight, byTop).coerceIn(0.0, 1.0)
    }
}
