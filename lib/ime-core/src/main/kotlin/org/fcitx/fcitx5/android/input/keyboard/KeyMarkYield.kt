/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.KeyMarks.Slot
import kotlin.math.abs

/**
 * How a key's main glyph (its letter, label or icon) and the marks in its slots ([KeyMarks]: its
 * symbol, its shortcuts) keep out of each other's way, measured by their ink, not their views: a view
 * is the line's height and advance, and on 大千's keys it met the icon where the glyphs did not,
 * moving symbols that touched nothing. Nothing moves while nothing meets.
 *
 * Every slot is worked out as the top right one, the key mirrored side for side or top for bottom
 * ([inFrame]): a mark moves out towards its corner (a corner's sideways or up, whichever clears in
 * less; the edge's middle only up), then is smaller about its corner, to its least ([Mark.minScale]:
 * a symbol 6 dp, an icon 7 dp, below which they are not read).
 *
 * A key's label or icon ([key]): the marks moved first, then it moves itself (any way, the least),
 * then the marks smaller, then it is smaller about its middle, a fifth at most ([MAX_LABEL_SHRINK]).
 * A keyboard's letters ([letters]): they stay where they are, a row's on one baseline at one size;
 * the marks move and are smaller first; what still meets, every letter is as much smaller about its
 * baseline, a quarter at most ([MAX_LETTER_SHRINK]).
 *
 * Nothing of that enough: the marks still meeting the glyph or each other are left out, the most
 * expendable first ([Mark.drop]: a function key's down shortcut, then a shortcut, a symbol last; the
 * swipes still work). Whatever is drawn never meets anything else drawn on its key.
 */
object KeyMarkYield {

    /** A box's sides, in one view's coordinates. */
    data class Ink(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun moved(dx: Float, dy: Float) = Ink(left + dx, top + dy, right + dx, bottom + dy)

        /** Scaled by [s] about ([x], [y]). */
        fun scaled(s: Float, x: Float, y: Float) = Ink(x + (left - x) * s, y + (top - y) * s, x + (right - x) * s, y + (bottom - y) * s)

        /** Whether it and [o] come closer than [gap]; touching, with no gap asked, is apart. */
        fun meets(o: Ink, gap: Float): Boolean =
            left < o.right + gap && o.left < right + gap && top < o.bottom + gap && o.top < bottom + gap
    }

    /**
     * A mark's [ink] in its [slot], no smaller than [minScale] of it; [drop], how soon it is left out
     * where nothing else clears it (the higher the sooner; [DROP_SYMBOL] the last).
     */
    data class Mark(val ink: Ink, val slot: Slot, val minScale: Float = 1f, val drop: Int = DROP_SHORTCUT)

    /** Where a key's glyph and marks end up: [glyph]'s new ink; each mark's in order, null for left out. */
    data class Settled(val glyph: Ink, val marks: List<Ink?>)

    const val DROP_SYMBOL = 1
    const val DROP_SHORTCUT = 2

    /** A function key's down shortcut: the first left out, the up one the one swiped more. */
    const val DROP_DOWN_SHORTCUT = 3

    /** A symbol no smaller than this: 6 dp, about the cap height of a phone's smallest notification text. */
    const val MIN_SYMBOL_DP = 6f

    /** An icon no smaller than this: 7 dp, a Material icon's strokes still apart (at 6 the clipboard is a blot). */
    const val MIN_ICON_DP = 7f

    /** A label or a key's icon a fifth smaller at most. */
    const val MAX_LABEL_SHRINK = 0.2f

    /** The letters a quarter smaller at most: their row, the keyboard's, the same size still. */
    const val MAX_LETTER_SHRINK = 0.25f

    /** A mark of [dp] (a text's size, an icon's height) no smaller than [min] dp: as a scale of it. */
    fun minScale(min: Float, dp: Float): Float = if (dp <= 0f) 1f else (min / dp).coerceAtMost(1f)

    // the label's shrink tried in steps of a hundredth
    private const val STEP = 0.01f

    /**
     * A key's [glyph] (a label, an icon) and its [marks], within [room] (its face, clear of the
     * rounded edges), kept [gap] apart.
     */
    fun key(glyph: Ink, marks: List<Mark>, room: Ink, gap: Float): Settled {
        val apart = keptApart(marks)
        val live = apart.filterNotNull()
        val s = keyApart(glyph, live, room, gap)
        val settled = s.marks.iterator()
        return Settled(s.glyph, apart.map { if (it == null) null else settled.next() })
    }

    // [key]'s, [marks] apart from each other
    private fun keyApart(glyph: Ink, marks: List<Mark>, room: Ink, gap: Float): Settled {
        if (marks.none { it.ink.meets(glyph, gap) }) return Settled(glyph, marks.map { it.ink })
        // the marks moved only, then smaller too: the glyph moved clear of them
        for (shrink in listOf(false, true)) {
            val placed = marks.map { into(glyph, it, room, gap, if (shrink) it.minScale else 1f) }
            clearOf(glyph, placed, room, gap)?.let { return Settled(it, placed) }
        }
        // the glyph smaller about its middle, the least that clears with a move
        val cx = (glyph.left + glyph.right) / 2f
        val cy = (glyph.top + glyph.bottom) / 2f
        var s = 1f - STEP
        while (s >= 1f - MAX_LABEL_SHRINK - STEP / 2) {
            val small = glyph.scaled(s, cx, cy)
            val placed = marks.map { into(small, it, room, gap, it.minScale) }
            clearOf(small, placed, room, gap)?.let { return Settled(it, placed) }
            s -= STEP
        }
        val small = glyph.scaled(1f - MAX_LABEL_SHRINK, cx, cy)
        return Settled(small, dropped(small, marks, marks.map { into(small, it, room, gap, it.minScale) }))
    }

    /** A letter key's: its [letter]'s ink and [baseline], its [marks], the [room] they move in. */
    data class LetterKey(val letter: Ink, val baseline: Float, val marks: List<Mark>, val room: Ink)

    /** Every letter scaled by [scale] about its ink's middle on its baseline; each key's [keys] settled. */
    data class Letters(val scale: Float, val keys: List<Settled>)

    /** How a keyboard's letters, [keys], and their marks keep [gap] apart. */
    fun letters(keys: List<LetterKey>, gap: Float): Letters {
        val apart = keys.map { keptApart(it.marks) }
        val live = apart.map { it.filterNotNull() }
        // what each letter would need, its marks as far in and small as they go; the most needed, for all,
        // but a quarter at most: a mark that needs more is left out, its letter kept
        val need = keys.flatMapIndexed { k, key ->
            live[k].map { m -> letterScale(key.letter, into(key.letter, m, key.room, gap, m.minScale), m.slot, gap, mid(key.letter), key.baseline) }
        }.filter { it >= 1f - MAX_LETTER_SHRINK - STEP / 2 }.minOrNull() ?: 1f
        val scale = need.coerceAtLeast(1f - MAX_LETTER_SHRINK)
        // each mark then only as far in and small as the letter, so much smaller, leaves it
        return Letters(scale, keys.mapIndexed { k, key ->
            val letter = if (scale < 1f) key.letter.scaled(scale, mid(key.letter), key.baseline) else key.letter
            val settled = dropped(letter, live[k], live[k].map { into(letter, it, key.room, gap, it.minScale) }).iterator()
            Settled(letter, apart[k].map { if (it == null) null else settled.next() })
        })
    }

    // [placed] (of [marks]) less those still meeting [glyph] (none: only each other) or each other, the most
    // expendable first
    private fun dropped(glyph: Ink?, marks: List<Mark>, placed: List<Ink>): List<Ink?> {
        val out = placed.toMutableList<Ink?>()
        while (true) {
            val clash = out.indices.filter { i ->
                val a = out[i] ?: return@filter false
                glyph?.let { a.meets(it, 0f) } == true || out.indices.any { j -> j != i && out[j]?.meets(a, 0f) == true }
            }
            if (clash.isEmpty()) return out
            out[clash.maxBy { marks[it].drop }] = null
        }
    }

    // [marks] apart from each other: those meeting smaller about their corners (an edge's middle, its
    // middle), all as much, the least that parts them, no smaller than each's own; what that doesn't
    // part left out, the most expendable first (no move parts them: each moves only into its corner)
    private fun keptApart(marks: List<Mark>): List<Mark?> {
        val meeting = marks.indices.filter { i -> marks.indices.any { j -> j != i && marks[i].ink.meets(marks[j].ink, 0f) } }
        if (meeting.isEmpty()) return marks
        val least = meeting.minOf { marks[it].minScale }
        var s = 1f - STEP
        while (s >= least - STEP / 2) {
            val small = marks.mapIndexed { i, m -> if (i in meeting) m.smaller(maxOf(s, m.minScale)) else m }
            if (allApart(small.map { it.ink })) return small
            s -= STEP
        }
        val kept = dropped(null, marks, marks.map { it.ink })
        return marks.mapIndexed { i, m -> m.takeIf { kept[i] != null } }
    }

    // this mark [s] its size about its corner, its smallest the same as it was
    private fun Mark.smaller(s: Float): Mark {
        val f = ink.inFrame(slot)
        val x = if (slot.centred) mid(f) else f.right
        return copy(ink = f.scaled(s, x, f.top).inFrame(slot), minScale = minScale / s)
    }

    private fun allApart(inks: List<Ink>): Boolean = inks.indices.all { i -> inks.indices.all { j -> j <= i || !inks[i].meets(inks[j], 0f) } }

    // [glyph] moved the least, within [room] (or no further out of it than it was), clear of [marks]
    // and they of each other; null where no move does
    private fun clearOf(glyph: Ink, marks: List<Ink>, room: Ink, gap: Float): Ink? {
        if (!allApart(marks)) return null
        val meeting = marks.filter { it.meets(glyph, gap) }
        if (meeting.isEmpty()) return glyph
        val dxs = listOf(0f) + meeting.flatMap { listOf(it.right + gap - glyph.left, it.left - gap - glyph.right) }
        val dys = listOf(0f) + meeting.flatMap { listOf(it.bottom + gap - glyph.top, it.top - gap - glyph.bottom) }
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

    // [mark] out of [glyph]'s way, within [room]: towards its corner, the least, then smaller to [minScale]
    private fun into(glyph: Ink, mark: Mark, room: Ink, gap: Float, minScale: Float): Ink {
        if (!mark.ink.meets(glyph, gap)) return mark.ink
        val s = mark.slot
        return corner(glyph.inFrame(s), mark.ink.inFrame(s), room.inFrame(s), gap, minScale, s.centred).inFrame(s)
    }

    // the top right [mark] out of [glyph]'s way: right or up, whichever clears it in less ([centred]: up
    // only); neither alone: into [room]'s corner, then smaller about its top right (top middle), to [minScale]
    private fun corner(glyph: Ink, mark: Ink, room: Ink, gap: Float, minScale: Float, centred: Boolean): Ink {
        val over = glyph.right + gap - mark.left
        val under = mark.bottom - (glyph.top - gap)
        val rightRoom = if (centred) 0f else (room.right - mark.right).coerceAtLeast(0f)
        val upRoom = (mark.top - room.top).coerceAtLeast(0f)
        val sideways = !centred && over <= rightRoom
        val upFits = under <= upRoom
        if (sideways && (over <= under || !upFits)) return mark.moved(over, 0f)
        if (upFits) return mark.moved(0f, -under)
        val moved = mark.moved(rightRoom, -upRoom)
        val byLeft = if (centred) 0f else (moved.right - (glyph.right + gap)) / moved.width
        val byBottom = (glyph.top - gap - moved.top) / moved.height
        val scale = maxOf(byLeft, byBottom).coerceIn(minScale, 1f)
        val x = if (centred) mid(moved) else moved.right
        return moved.scaled(scale, x, moved.top)
    }

    private fun mid(ink: Ink): Float = (ink.left + ink.right) / 2f

    // how much smaller [letter] about ([x], [y]) clears [mark] in [slot]: by the side facing it or its top
    // (bottom), whichever needs less
    private fun letterScale(letter: Ink, mark: Ink, slot: Slot, gap: Float, x: Float, y: Float): Float {
        if (!letter.meets(mark, gap)) return 1f
        val l = letter.inFrame(slot)
        val m = mark.inFrame(slot)
        val o = Ink(x, y, x, y).inFrame(slot)
        val byRight = if (!slot.centred && l.right > o.right) (m.left - gap - o.right) / (l.right - o.right) else 0f
        val byTop = if (l.top < o.top) (o.top - (m.bottom + gap)) / (o.top - l.top) else 0f
        return maxOf(byRight, byTop).coerceIn(0f, 1f)
    }
}
