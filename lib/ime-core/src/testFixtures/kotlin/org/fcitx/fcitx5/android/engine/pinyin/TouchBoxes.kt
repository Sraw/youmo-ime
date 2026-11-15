/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * Keyboards as [KeyBoxes], in dp as on a phone 400 dp wide (a QWERTY key 40 by 56, the design's σ a quarter of its width): what tests and
 * ime-eval tap on (061-neighbour-correction §5.1).
 */
object TouchBoxes {
    const val KEY_W = 40f
    const val KEY_H = 56f

    // [rows] of keys, each row its keys' characters, where it starts and each key's width (in key widths)
    private fun rows(vararg rows: Triple<String, Float, Float>): KeyBoxes {
        val keys = LinkedHashMap<Char, KeyBoxes.Box>()
        rows.forEachIndexed { r, (chars, offset, width) ->
            chars.forEachIndexed { i, c ->
                val left = (offset + i * width) * KEY_W
                keys[c] = KeyBoxes.Box(left, r * KEY_H, left + width * KEY_W, (r + 1) * KEY_H)
            }
        }
        return KeyBoxes(keys)
    }

    /** Full pinyin's and 双拼's 26 keys. */
    val QWERTY = rows(Triple("qwertyuiop", 0f, 1f), Triple("asdfghjkl", 0.5f, 1f), Triple("zxcvbnm", 1.5f, 1f))

    /** 微软双拼's: `;` ends the middle row. */
    val QWERTY_SEMICOLON = rows(Triple("qwertyuiop", 0f, 1f), Triple("asdfghjkl;", 0f, 1f), Triple("zxcvbnm", 1.5f, 1f))

    /** The nine keys' digits with letters (nine.xml: a side column of 1.6 key widths, each digit 2.2): no 1, no 0. */
    val NINE = rows(Triple("23", 1.6f + 2.2f, 2.2f), Triple("456", 1.6f, 2.2f), Triple("789", 1.6f, 2.2f))

    /** The 14 keys, each key its code. */
    val G14 = rows(
        Triple(g14("q e t u o"), 0f, 2f),
        Triple(g14("a d g j l"), 0f, 2f),
        Triple(g14("z c b m"), 1f, 2f),
    )

    /**
     * 拼音's 18 keys, each key its code: a row's keys as wide as each other, the rows not (seven, six and five keys,
     * the bottom row's as wide as the top's, centred), as ime-eval's k18 has them: keys of uneven widths.
     */
    val G18 = rows(
        Triple(g18("q w r y u i p"), 0f, 10f / 7),
        Triple(g18("a s f h j l"), 0f, 10f / 6),
        Triple(g18("z x v b m"), (10f - 5 * 10f / 7) / 2, 10f / 7),
    )

    /** A tap [dx] and [dy] (in its width and height) from the centre of the key [c] of [boxes]. */
    fun tap(boxes: KeyBoxes, c: Char, dx: Float = 0f, dy: Float = 0f): Touch {
        // the key's size and the keys around it, the place then set
        return (boxes.touch(c, 0f, 0f) ?: error("no key $c")).copy(dx = dx, dy = dy)
    }

    /** [n] places, a [touch] at [at] alone. */
    fun touchesAt(n: Int, at: Int, touch: Touch): Array<Touch?> = arrayOfNulls<Touch>(n).also { it[at] = touch }
}
