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
 * A key's shortcut is in its top left corner ([KeyMarks]): the label clears that the same way
 * mirrored ([ofTopLeft]), right or down. A function key's icon ([ofIcon]) only right, its down
 * shortcut being in the bottom left corner.
 *
 * A letter key's ([letters], the floating and one-handed keyboards at 紧凑): the letter stays, a
 * row's letters on one baseline at one size; its symbol (top right) and its shortcut (top left) move
 * into their corners, then are smaller, a symbol down to [MIN_SYMBOL_DP], an icon to [MIN_ICON_DP];
 * what still meets, every letter of the keyboard is as much smaller about its baseline.
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

    /**
     * [of] for a [mark] in the top left corner: the label clears it by being right of it or below it,
     * shrunk about its ink's bottom right, the mark about its top left.
     */
    fun ofTopLeft(label: Ink, mark: Ink, face: Ink, gap: Float, markShrinks: Boolean): Yield {
        val y = of(label.mirrored(), mark.mirrored(), face.mirrored(), gap, markShrinks)
        return if (y.dx == 0f) y else y.copy(dx = -y.dx)
    }

    /**
     * A function key's [icon] (backspace's, shift's) clear of its shortcuts' marks in its left
     * corners, [top] and [bottom], either null for none: right of those it meets, as far as the face
     * goes, then smaller about the middle of its right side, a fifth at most. Never down or up: with
     * a mark in each corner, out of one's way was into the other's (handwriting's and 紧凑's low
     * backspace, the bin on the icon's tip).
     */
    fun ofIcon(icon: Ink, top: Ink?, bottom: Ink?, face: Ink, gap: Float): Yield {
        val need = listOfNotNull(top, bottom).filter {
            icon.left < it.right + gap && icon.right > it.left && icon.top < it.bottom + gap && icon.bottom > it.top - gap
        }.maxOfOrNull { it.right + gap - icon.left } ?: return Yield.None
        val room = (face.right - gap - icon.right).coerceAtLeast(0f)
        if (need <= room) return Yield(need, 0f, 1f, 1f)
        val scale = 1f - ((need - room) / icon.width).coerceAtMost(MAX_LABEL_SHRINK)
        return Yield(room, 0f, scale, 1f)
    }

    // left for right: the top left corner's yield worked out as the top right's
    private fun Ink.mirrored() = Ink(-right, top, -left, bottom)

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
     * clear of its rounded edges) and [symbolDp], its size (a text's, an icon's height); its top left
     * corner's [shortcut], [shortcutDp] in size, an icon or not ([shortcutIcon]).
     */
    data class LetterKey(
        val letter: Ink, val baseline: Float, val symbol: Ink?, val room: Ink, val symbolDp: Float,
        val shortcut: Ink? = null, val shortcutDp: Float = 0f, val shortcutIcon: Boolean = true,
    )

    /** A corner's mark moved by [dx], [dy], then scaled by [scale] about its outer top corner (right, or left on the left). */
    data class Corner(val dx: Float, val dy: Float, val scale: Float) {
        companion object {
            val None = Corner(0f, 0f, 1f)
        }
    }

    /**
     * Every letter scaled by [scale] about its ink's middle on its baseline; each key's top right
     * corner by [corners]', its top left by [leftCorners]'.
     */
    data class Letters(val scale: Float, val corners: List<Corner>, val leftCorners: List<Corner>)

    /** A symbol no smaller than this: 6 dp, about the cap height of a phone's smallest notification text. */
    const val MIN_SYMBOL_DP = 6f

    /** An icon no smaller than this: 7 dp, a Material icon's strokes still apart (at 6 the clipboard is a blot). */
    const val MIN_ICON_DP = 7f

    /** The letters a quarter smaller at most: their row, the keyboard's, the same size still. */
    const val MAX_LETTER_SHRINK = 0.25f

    /** How the letters of [keys], one keyboard's, and their symbols keep [gap] apart. */
    fun letters(keys: List<LetterKey>, gap: Float): Letters {
        // what each letter would need, its marks as far in and small as they go; the most needed, for all
        val needs = keys.flatMap { k ->
            listOf(
                k.symbol?.let { need(k.letter, k.baseline, it, k.room, gap, minScale(MIN_SYMBOL_DP, k.symbolDp)) } ?: 1f,
                k.shortcut?.let {
                    need(k.letter.mirrored(), k.baseline, it.mirrored(), k.room.mirrored(), gap, minScale(k))
                } ?: 1f,
            )
        }
        val scale = (needs.minOrNull() ?: 1f).coerceAtLeast(1f - MAX_LETTER_SHRINK)
        // each mark then only as far in and small as the letter, so much smaller, leaves it
        val corners = keys.map { k ->
            val symbol = k.symbol ?: return@map Corner.None
            corner(scaled(k.letter, scale, mid(k.letter), k.baseline), symbol, k.room, gap, minScale(MIN_SYMBOL_DP, k.symbolDp))
        }
        val leftCorners = keys.map { k ->
            val shortcut = k.shortcut ?: return@map Corner.None
            val letter = scaled(k.letter, scale, mid(k.letter), k.baseline).mirrored()
            corner(letter, shortcut.mirrored(), k.room.mirrored(), gap, minScale(k)).let { if (it.dx == 0f) it else it.copy(dx = -it.dx) }
        }
        return Letters(scale, corners, leftCorners)
    }

    // how much smaller [letter] has to be to clear [mark] as far in and small as it goes, in the top right
    private fun need(letter: Ink, baseline: Float, mark: Ink, room: Ink, gap: Float, minScale: Float): Float {
        val placed = placed(mark, corner(letter, mark, room, gap, minScale))
        return letterScale(letter, placed, gap, mid(letter), baseline)
    }

    private fun minScale(k: LetterKey): Float = minScale(if (k.shortcutIcon) MIN_ICON_DP else MIN_SYMBOL_DP, k.shortcutDp)

    private fun minScale(min: Float, dp: Float): Float = if (dp <= 0f) 1f else (min / dp).coerceAtMost(1f)

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
