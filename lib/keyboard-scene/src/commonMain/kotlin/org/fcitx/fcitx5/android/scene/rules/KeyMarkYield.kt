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
 * A keyboard's glyphs come in classes ([board]: its main characters, its function keys' icons, their
 * labels), and a class is one size: the user's 「同类型的键大小应该一样」. Marks are of two classes too
 * (every symbol, every shortcut): no mark and no glyph is ever smaller on its own.
 *
 * Every slot is worked out as the top right one, the key mirrored side for side or top for bottom
 * ([inFrame]). A mark gives way, the lightest first, all within its own corner (a shortcut's corner
 * is the swipe it stands for, so it never goes to another): out towards its corner within the room
 * (a corner's sideways or up, whichever clears in less; the edge's middle only up); then out into its
 * inset, the least that clears, as far as the edge ([Edge]: a quarter dp in from the face's sides and
 * its border, `Content.MARK_EDGE_DP`, and its box's corner within the face's rounded one); then in
 * along the arc, the least. Where a glyph may move ([Member.moves]: not a main character, whose row
 * shares a baseline) it moves the least to clear what is left. Still meeting: the whole glyph class
 * smaller, every glyph of it as much, the most any of its keys needs, but [MAX_CLASS_SHRINK] at most;
 * then the whole mark class (every symbol, every shortcut, each about its corner) as much smaller, the
 * most any key with one needs, to [MIN_MARK_SCALE]; a key whose marks still meet then has them left
 * out, the most expendable first ([Mark.drop]: a function key's down shortcut, a shortcut, the symbol
 * last; the swipe and the long press still work). The glyphs first: a letter 6% smaller reads as the
 * same size, a mark 6% smaller beside its letter does not, and a mark is the lesser of the two.
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
 * ime-core's copy, the views', is the older rule (marks smaller, other corners); to be deleted
 * with the other copies (060-pending-decisions, S8a).
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
    data class Mark(val ink: Ink, val slot: Slot, val drop: Int = DROP_SHORTCUT, val grows: Grows? = null)

    /**
     * How much more a glyph's or mark's ink is at a scale than its ink scaled (each side, out positive): its
     * text measured again at the size drawn, a font's ink no linear function of its size (whole pixels, hinting).
     */
    fun interface Grows {
        fun at(s: Double): Ink
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
        init {
            require(radii.size == 4) { "an edge has four corner radii, not ${radii.size}" }
        }

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
        val glyph: Ink,
        val x: Double,
        val y: Double,
        val marks: List<Mark>,
        val room: Ink,
        val edge: Edge = Edge(room),
        val moves: Boolean = true,
        val glyphClass: Int = 0,
        val grows: Grows? = null,
        /** Its glyph a label, kept within [room] across (rules B §8a); an icon is as large as it is. */
        val across: Boolean = false,
    )

    /** Every glyph of a class scaled by [scale] about its point; each key's [keys] settled. */
    data class Group(val scale: Double, val keys: List<Settled>)

    /**
     * A board's keys settled ([board]): each glyph class's scale by its number, every symbol's and every
     * shortcut's scale (each about its corner), each key's [keys].
     */
    data class Board(val scales: Map<Int, Double>, val symbol: Double, val shortcut: Double, val keys: List<Settled>) {
        fun markScale(m: Mark): Double = if (m.drop == DROP_SYMBOL) symbol else shortcut
    }

    /** A key with no glyph (a space with no name, its marks still of their classes): meets nothing, never moves. */
    val NO_GLYPH = Ink(Double.NaN, Double.NaN, Double.NaN, Double.NaN)

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

    /**
     * A mark class (every symbol, every shortcut) no smaller than this (060-render-p2 S4e): an 8 dp icon
     * at 7 dp, the old rule's least. The keyboards swept need 0.926 to 0.949 of the shortcuts at their
     * height (a floating board's ⌫, at every density) and 0.875 or less only where they need much less
     * (at three quarters of the height, as many keys need 0.85 as 0.875): past it a mark is left out.
     */
    const val MIN_MARK_SCALE = 0.875

    /**
     * How far past what its classes may give a key fades out of their need (060-render-p2 S4e): a key
     * whose marks fit only at [MIN_MARK_SCALE] less this or smaller (or never) asks its classes for
     * nothing, its marks left out; one between asks the less the nearer it is to that, so a class's
     * scale moves with the keyboard's size and never jumps where a key stops being saved. As wide as
     * keeps a class's growth back to its size, as a key fades, to about 2% a px of keyboard.
     */
    const val HOPELESS_BAND = 0.15

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

    /** A class's [keys] as [board] settles them, all of one glyph class: its scale, each key settled. */
    fun group(keys: List<Member>, gap: Double): Group {
        val b = board(keys.map { it.copy(glyphClass = 0) }, gap)
        return Group(b.scales[0] ?: 1.0, b.keys)
    }

    /**
     * A board's [keys], every glyph class ([Member.glyphClass]) and every mark class (symbols, shortcuts)
     * one size: in its corner first; then each glyph class smaller, the most any of its keys needs, to
     * [MAX_CLASS_SHRINK]; then each mark class smaller, the most any key with one needs, to [minMark]
     * (scaled about its corner); what still meets then left out, the most expendable first.
     *
     * A glyph's need is worked out with all its key's marks as large as they are kept apart from each
     * other (down to [HOPELESS_BAND] below [minMark]), its glyph not moving (aside, if it moves);
     * moving it is only for settling, to keep a mark that would else be left out. A mark class shrinks
     * only for a key where it meets: symbols, shortcuts or both, each its own need.
     */
    fun board(drawn: List<Member>, gap: Double, minMark: Double = MIN_MARK_SCALE): Board {
        // what a class needs is worked out with every glyph that moves as far from its marks' side as its room
        // goes, there to stay: a class's size then moves with the room, never with how a glyph would move as
        // its marks meet it (the least that clears them, near and far by turns); the glyphs then move the least
        val keys = drawn.map { it.aside() }
        val apart = keys.map { k -> keptApart(k.marks.map { it.within(k.edge) }, k.edge) }
        val hopeless = minMark - HOPELESS_BAND
        // each key as it is (most are: worked out once)
        val asIs = keys.indices.map { k -> drawn[k].copy(moves = false).let { m -> place(m.glyph, apart[k].filterNotNull(), m, gap) } }
        fun whole(k: Int) = asIs[k] != null && apart[k].all { it != null }
        // a key's marks, those of [symbol] (or the shortcuts) [t] their size, the others [u]; a class at 0 left out
        fun marksAt(k: Int, t: Double, u: Double = t, symbol: Boolean? = null): List<Mark?> = keptApart(
            keys[k].marks.map { it to it.sizeIn(t, u, symbol) }.filter { it.second > 0.0 }.map { (m, s) -> m.scaled(s).within(keys[k].edge) },
            keys[k].edge,
        )
        fun fit(k: Int, g: Ink, marks: List<Mark?>) = marks.all { it != null } && place(g, marks.filterNotNull(), keys[k], gap) != null
        // the glyphs: with all of a key's marks ([only] the symbols: the shortcuts left out), as large as they
        // are all kept apart (down to a band below [minMark]: none left out of it where they are kept apart a
        // little smaller, so it never jumps); asked of the class all where they fit at [minMark] by the glyphs'
        // least, the less the further below
        val floor = if (minMark >= 1.0) minMark else hopeless
        fun asks(k: Int, only: Boolean) = glyphAsks(
            floor, minMark,
            marks = { t -> if (only) marksAt(k, t, 0.0, symbol = true) else marksAt(k, t) },
            fits = { s, marks -> fit(k, keys[k].at(s), marks) },
        )
        // a key with both: no more than its symbols alone ask, so they are not let go with the shortcuts
        // rules B §8a: and each glyph within its room across
        val asked = keys.indices.map { k ->
            minOf(if (whole(k)) 1.0 else minOf(asks(k, false), asks(k, true)), keys[k].across())
        }
        val scales = keys.indices.groupBy { keys[it].glyphClass }.mapValues { (_, ks) -> ks.minOf { asked[it] } }
        // the marks: of the class (or both) that meets, as much smaller as lets them
        val markNeed = keys.indices.map { k ->
            if (whole(k)) return@map 1.0 to 1.0
            val g = keys[k].at(scales.getValue(keys[k].glyphClass))
            markNeed(floor, minMark) { t, u, symbol -> fit(k, g, marksAt(k, t, u, symbol)) }
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

    /**
     * [key] settled as [was] (the same key, its edges where they fell before they were put on whole
     * pixels) settled in [board]: its glyph scaled as there and moved as far; a mark that stayed as it was
     * where [key] has it (on its whole pixels), one only smaller as much smaller about its corner there,
     * one that moved where it went there, its corners then within [key]'s arcs (half a px from them at most); what was left out there left out.
     * Every choice is made on [was], whose geometry a px of keyboard moves by a px, never by a pixel's
     * rounding: one size of keyboard, one picture.
     */
    fun onto(key: Member, was: Member, board: Board, settled: Settled): Settled {
        val s = board.scales.getValue(key.glyphClass)
        val g0 = was.at(s)
        val glyph = key.at(s).moved(settled.glyph.left - g0.left, settled.glyph.top - g0.top)
        val marks = key.marks.mapIndexed { i, m ->
            settled.marks[i]?.let { c ->
                val t = board.markScale(m)
                if (t == 1.0 && c == was.marks[i].ink) return@let m.within(key.edge)
                // only smaller there (and slid off the arc): as much smaller about its corner where [key] has it and
                // slid as far, so a class nearing 1 nears the mark as it is
                val w = was.marks[i].scaled(t)
                val at = if (c == w.within(was.edge).ink) m.scaled(t).let { it.copy(ink = it.ink.moved(c.left - w.ink.left, c.top - w.ink.top)) } else m.copy(ink = c)
                // its corners within the arcs of [key]'s face as rounded, whatever it is
                at.within(key.edge)
            }
        }
        // each mapped on its own, a pixel's snap apart (a shortcut's icon laid out on whole pixels) can bring two
        // kept apart where [was] has them together again: kept apart once more where [key] has them
        val shown = marks.indices.filter { marks[it] != null }
        val apart = keptApart(shown.map { marks[it]!! }, key.edge)
        return Settled(glyph, marks.indices.map { i -> shown.indexOf(i).takeIf { it >= 0 }?.let { apart[it]?.ink } })
    }

    /**
     * [key] as [onto] settled it ([settled]), its glyph then moved to [glyph] (back within its room, rules B §8a):
     * as it was where no mark kept meets the glyph there; else the marks kept, as large as [board] has them, placed
     * again about the glyph where it is now, the glyph not moving, those still meeting it left out (060-render-p2 S7a).
     */
    fun again(key: Member, glyph: Ink, board: Board, settled: Settled, gap: Double): Settled {
        if (settled.marks.none { it != null && it.meets(glyph, gap) }) return settled.copy(glyph = glyph)
        // kept apart first, as [board] and [onto] do: two marks that start out meeting are parted, not one left out
        val kept = key.marks.indices.filter { settled.marks[it] != null }
        val apart = keptApart(kept.map { i -> key.marks[i].let { m -> m.scaled(board.markScale(m)).within(key.edge) } }, key.edge)
        val marks = key.marks.indices.map { i -> kept.indexOf(i).let { at -> if (at < 0) null else apart[at] } }
        return settle(key.copy(moves = false), glyph, marks, gap)
    }

    // this mark's size: [t] if of the class [symbol] (both if null), else [u]
    private fun Mark.sizeIn(t: Double, u: Double, symbol: Boolean?) = if (symbol == null || (drop == DROP_SYMBOL) == symbol) t else u

    // what a key asks of its glyph class with [marks] at a size: its need with them as large as they are
    // kept apart, all of it where they [fits] at [minMark] by the glyphs' least, the less the further below
    private fun glyphAsks(floor: Double, minMark: Double, marks: (Double) -> List<Mark?>, fits: (Double, List<Mark?>) -> Boolean): Double {
        val least = 1.0 - MAX_CLASS_SHRINK
        val live = marks(largest(floor) { t -> marks(t).all { it != null } }).filter { it != null }
        val need = largest(least) { s -> fits(s, live) }
        val saved = if (minMark >= 1.0) 1.0 else share(largest(minMark - HOPELESS_BAND) { t -> fits(least, marks(t)) }, minMark)
        return 1.0 - (1.0 - need) * saved
    }

    // a key's need of the symbols and of the shortcuts: both alike, the least that fits; from there the
    // symbols as large as fits, then the shortcuts (the more expendable) as large as fits with them, so a
    // class that does not meet is its size. Each from a size that fits, so it moves as the room does (a
    // class's choice of which gives way never flips it). Below [minMark], as both fade out of their need
    // ([share]), each moves to what it needs alone (the other left out), all of it where both are past
    // saving: a class still saved alone is never let go with the other. [fits] with the symbols [t] their
    // size, the shortcuts [u] (both [t] if [symbol] is null; a class at 0 left out)
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

    // how much of its need a key whose marks fit at [t] asks: all at [minMark], none [HOPELESS_BAND] below it
    private fun share(t: Double, minMark: Double) = ((t - (minMark - HOPELESS_BAND)) / HOPELESS_BAND).coerceIn(0.0, 1.0)

    // the largest of [least] to 1 (to a thousandth) where [fits]; [least] where none
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

    // this mark [t] its size, about its corner (an edge's middle: its top's middle)
    private fun Mark.scaled(t: Double): Mark {
        if (t == 1.0) return this
        val f = ink.inFrame(slot)
        val x = if (slot.centred) (f.left + f.right) / 2.0 else f.right
        return copy(ink = f.scaled(t, x, f.top).inFrame(slot).grown(grows, t))
    }

    // [key] with its glyph at [glyph]: its [marks] placed, those still meeting it left out the most expendable first
    // ([aside]: the key as its classes' needs were worked out, its glyph where it was then, where [glyph]
    // moving the least does not clear the marks)
    private fun settle(key: Member, glyph: Ink, marks: List<Mark?>, gap: Double, aside: Pair<Member, Ink>? = null): Settled {
        val kept = marks.toMutableList()
        while (true) {
            val live = kept.filterNotNull()
            val s = place(glyph, live, key, gap) ?: aside?.let { (m, g) -> place(g, live, m, gap) }
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
        // the four corners in a plain loop, no list made: this runs in the boards' searches, many times a build
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

    // this key not moving, its glyph if it moves as far from its marks' side (all of them on one) as its room goes,
    // smaller about that side
    private fun Member.aside(): Member {
        val still = copy(moves = false)
        if (!moves || glyph.left.isNaN() || marks.isEmpty()) return still
        // the room's side away from the marks; smaller about it, so it stays there
        // which way kept as a flag: a room of no width has its two sides at one coordinate
        val toRight = when {
            marks.all { it.slot.left } -> true
            marks.all { it.slot.right } -> false
            else -> return still
        }
        val dx = if (toRight) room.right - glyph.right else room.left - glyph.left
        return still.copy(glyph = glyph.moved(dx, 0.0), x = if (toRight) room.right else room.left)
    }

    // rules B §8a: what a label wider than its room asks of its class, the class as much smaller as its widest
    // needs, to [MAX_CLASS_SHRINK] with the rest (what is wider still shrunk alone before: SceneBuilder.fitted)
    private fun Member.across(): Double {
        if (!across || glyph.left.isNaN() || glyph.width <= room.width) return 1.0
        return largest(1.0 - MAX_CLASS_SHRINK) { s -> at(s).width <= room.width }
    }

    private fun Member.at(s: Double): Ink = if (s == 1.0) glyph else glyph.scaled(s, x, y).grown(grows, s)

    // [g] as drawn at [s]: out on each side by what [grows] has
    private fun Ink.grown(grows: Grows?, s: Double): Ink =
        grows?.at(s)?.let { d -> Ink(left - d.left, top - d.top, right + d.right, bottom + d.bottom) } ?: this

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
    // null where nothing clears, or where a mark's corners are not within [key]'s arcs (one taller than a short
    // key's face less its arcs)
    private fun place(glyph: Ink, marks: List<Mark>, key: Member, gap: Double): Settled? =
        placed(glyph, marks, key, gap)?.takeIf { s -> s.marks.all { cornersIn(it!!, key.edge) } }

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
