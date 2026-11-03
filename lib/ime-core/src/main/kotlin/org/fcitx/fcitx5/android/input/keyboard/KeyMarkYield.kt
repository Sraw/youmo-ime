/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * How a key's label and what its top right corner draws (a shortcut's icon or text, a letter's
 * symbol) keep out of each other's way, measured by their ink, not their views: a view is the line's
 * height and advance, and on 大千's keys it met the icon where the glyphs did not, moving symbols
 * that touched nothing. Nothing while they do not meet.
 *
 * A key's label ([of], 大千's narrow keys): it moves left or down, whichever clears it in less;
 * neither alone: each way as far as it goes, then a text mark smaller towards its corner (an icon is
 * small already), then the label smaller towards its bottom left, the way that leaves the less to
 * shrink taken, only its move.
 *
 * A letter key's ([letters], the floating and one-handed keyboards at 紧凑): the letter stays, a
 * row's letters on one baseline at one size; its symbol moves into the corner, then is smaller, down
 * to [MIN_SYMBOL_DP]; what still meets, every letter of the keyboard is as much smaller about its
 * baseline.
 */
object KeyMarkYield {

    /** A box's sides, in one view's coordinates. */
    data class Ink(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** The label moved by [dx], [dy], then scaled by [labelScale] about its ink's bottom left; the mark by [markScale] about its top right. */
    data class Yield(val dx: Float, val dy: Float, val labelScale: Float, val markScale: Float) {
        companion object {
            val None = Yield(0f, 0f, 1f, 1f)
        }
    }

    /** A mark's text a quarter smaller at most: less, a symbol of 9 dp (紧凑's) is hard to read. */
    const val MAX_MARK_SHRINK = 0.25f

    /** The label a fifth smaller at most. */
    const val MAX_LABEL_SHRINK = 0.2f

    /**
     * How [label] yields to [mark] in [face], kept [gap] apart; [markShrinks] for a text mark. The
     * mark is the top right corner's: the label clears it by being left of it or below it.
     */
    fun of(label: Ink, mark: Ink, face: Ink, gap: Float, markShrinks: Boolean): Yield {
        if (!meets(label, mark, gap)) return Yield.None
        val over = label.right - (mark.left - gap)
        val under = mark.bottom + gap - label.top
        val leftRoom = (label.left - face.left - gap).coerceAtLeast(0f)
        val downRoom = (face.bottom - gap - label.bottom).coerceAtLeast(0f)
        // one way clears it: the shorter of them (a wide label under a text mark's bottom edge: down a
        // little, not across the key), left when both are as short
        if (over <= leftRoom && (over <= under || under > downRoom)) return Yield(-over, 0f, 1f, 1f)
        if (under <= downRoom) return Yield(0f, under, 1f, 1f)
        // neither: one way as far as it goes and the rest shrunk, the way leaving less (moved both
        // ways, the label sat askew in its key and was still shrunk)
        val left = shrunk(label, mark, -leftRoom, 0f, gap, markShrinks)
        val down = shrunk(label, mark, 0f, downRoom, gap, markShrinks)
        val downLess = down.labelScale > left.labelScale ||
            down.labelScale == left.labelScale && down.markScale > left.markScale
        return if (downLess) down else left
    }

    private fun shrunk(label: Ink, mark: Ink, dx: Float, dy: Float, gap: Float, markShrinks: Boolean): Yield {
        val moved = Ink(label.left + dx, label.top + dy, label.right + dx, label.bottom + dy)
        // shrunk about its top right, it clears the label by its left side or its bottom, whichever needs less
        val markScale = if (!markShrinks) 1f else maxOf(
            (mark.right - (moved.right + gap)) / mark.width,
            (moved.top - gap - mark.top) / mark.height,
        ).coerceIn(1f - MAX_MARK_SHRINK, 1f)
        val shrunk = Ink(mark.right - mark.width * markScale, mark.top, mark.right, mark.top + mark.height * markScale)
        // what is left, about its bottom left: its right side in by width × the shrink, its top down by height × it
        val still = minOf((moved.right - (shrunk.left - gap)) / moved.width, (shrunk.bottom + gap - moved.top) / moved.height)
        val labelScale = if (still > 0f) 1f - still.coerceAtMost(MAX_LABEL_SHRINK) else 1f
        return Yield(dx, dy, labelScale, markScale)
    }

    // [label] runs into [mark], top right of it, closer than [gap]
    private fun meets(label: Ink, mark: Ink, gap: Float): Boolean =
        label.right > mark.left - gap && label.top < mark.bottom + gap &&
            label.left < mark.right && label.bottom > mark.top

    /**
     * A letter key's, in its face's coordinates: its letter's ink and [baseline], the ink of what
     * its top right corner draws (null for nothing there), the [room] that may move into (the face,
     * clear of its rounded edge) and [symbolDp], its size (a text's, an icon's height).
     */
    data class LetterKey(val letter: Ink, val baseline: Float, val symbol: Ink?, val room: Ink, val symbolDp: Float)

    /** The corner's symbol moved by [dx], [dy], then scaled by [scale] about its top right. */
    data class Corner(val dx: Float, val dy: Float, val scale: Float) {
        companion object {
            val None = Corner(0f, 0f, 1f)
        }
    }

    /** Every letter scaled by [scale] about its ink's middle on its baseline; each key's corner by [corners]'. */
    data class Letters(val scale: Float, val corners: List<Corner>)

    /** A symbol no smaller than this: 6 dp, about the cap height of a phone's smallest notification text. */
    const val MIN_SYMBOL_DP = 6f

    /** The letters a quarter smaller at most: their row, the keyboard's, the same size still. */
    const val MAX_LETTER_SHRINK = 0.25f

    /** How the letters of [keys], one keyboard's, and their symbols keep [gap] apart. */
    fun letters(keys: List<LetterKey>, gap: Float): Letters {
        // what each letter would need, its symbol as far in and small as it goes; the most needed, for all
        val needs = keys.map { k ->
            val symbol = k.symbol ?: return@map 1f
            val placed = placed(symbol, corner(k.letter, symbol, k.room, gap, minScale(k)))
            letterScale(k.letter, placed, gap, mid(k.letter), k.baseline)
        }
        val scale = (needs.minOrNull() ?: 1f).coerceAtLeast(1f - MAX_LETTER_SHRINK)
        // each symbol then only as far in and small as the letter, so much smaller, leaves it
        val corners = keys.map { k ->
            val symbol = k.symbol ?: return@map Corner.None
            corner(scaled(k.letter, scale, mid(k.letter), k.baseline), symbol, k.room, gap, minScale(k))
        }
        return Letters(scale, corners)
    }

    private fun minScale(k: LetterKey): Float = (MIN_SYMBOL_DP / k.symbolDp).coerceAtMost(1f)

    private fun mid(ink: Ink): Float = (ink.left + ink.right) / 2f

    // [symbol] out of [letter]'s way: right or up, whichever clears it in less; neither alone: into
    // [room]'s corner, then smaller about its top right, to [minScale]
    private fun corner(letter: Ink, symbol: Ink, room: Ink, gap: Float, minScale: Float): Corner {
        if (!meets(letter, symbol, gap)) return Corner.None
        val over = letter.right + gap - symbol.left
        val under = symbol.bottom - (letter.top - gap)
        val rightRoom = (room.right - symbol.right).coerceAtLeast(0f)
        val upRoom = (symbol.top - room.top).coerceAtLeast(0f)
        if (over <= rightRoom && (over <= under || under > upRoom)) return Corner(over, 0f, 1f)
        if (under <= upRoom) return Corner(0f, -under, 1f)
        val top = symbol.top - upRoom
        val right = symbol.right + rightRoom
        val scale = maxOf((right - (letter.right + gap)) / symbol.width, (letter.top - gap - top) / symbol.height)
        return Corner(rightRoom, -upRoom, scale.coerceIn(minScale, 1f))
    }

    private fun placed(symbol: Ink, c: Corner): Ink {
        val right = symbol.right + c.dx
        val top = symbol.top + c.dy
        return Ink(right - symbol.width * c.scale, top, right, top + symbol.height * c.scale)
    }

    private fun scaled(ink: Ink, scale: Float, x: Float, y: Float): Ink =
        Ink(x + (ink.left - x) * scale, y + (ink.top - y) * scale, x + (ink.right - x) * scale, y + (ink.bottom - y) * scale)

    // how much smaller [letter] about ([x], [y]) clears [symbol]: by its right side or its top, whichever needs less
    private fun letterScale(letter: Ink, symbol: Ink, gap: Float, x: Float, y: Float): Float {
        if (!meets(letter, symbol, gap)) return 1f
        val byRight = if (letter.right > x) (symbol.left - gap - x) / (letter.right - x) else 0f
        val byTop = if (letter.top < y) (y - (symbol.bottom + gap)) / (y - letter.top) else 0f
        return maxOf(byRight, byTop).coerceIn(0f, 1f)
    }
}
