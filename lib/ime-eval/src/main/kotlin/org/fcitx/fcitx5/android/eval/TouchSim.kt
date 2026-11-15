/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.KeyBoxes
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import java.util.Random
import java.util.zip.CRC32

/**
 * Taps simulated with a place (061-neighbour-correction §5): a hand taps the meant key's centre
 * plus N(0, σ) on each axis and a shift, on a keyboard laid out in dp as a phone 400 dp wide has
 * it (a QWERTY key [KEY_W] by [KEY_H]; σ and the shift in its width, the same spread in millimetres
 * on every keyboard). Clean: every key lands in its own box, the input as typed; slip: one key (by
 * the sample's hash) lands in a key next to it, the input then having that key.
 */
object TouchSim {
    const val KEY_W = 40f
    const val KEY_H = 56f

    /** A keyboard's typing keys, each what it types and where. */
    class Board(val keys: Map<Char, KeyBoxes.Box>) {
        val boxes = KeyBoxes(keys)
    }

    // [rows] of keys, each its characters, where it starts and each key's width (in key widths)
    private fun rows(vararg rows: Triple<String, Float, Float>): Board {
        val keys = LinkedHashMap<Char, KeyBoxes.Box>()
        rows.forEachIndexed { r, (chars, offset, width) ->
            chars.forEachIndexed { i, c ->
                val left = (offset + i * width) * KEY_W
                keys[c] = KeyBoxes.Box(left, r * KEY_H, left + width * KEY_W, (r + 1) * KEY_H)
            }
        }
        return Board(keys)
    }

    private fun grouped(letters: NineKeyLetters, groups: String) = groups.split(' ').map { letters.key(it[0]) }.joinToString("")

    private fun g14(groups: String) = grouped(NineKeyLetters.G14, groups)

    /**
     * `qwerty`, `qwerty-ms` (微软双拼's `;` ending the middle row), `nine` (nine.xml's digits with
     * letters), `k14` (each key its code), `k18` (each key its code, as GroupedKeyboard lays the 18
     * keys out: a row's keys alike, a seventh, a sixth and 0.14 of the width, one letter or two each).
     */
    fun board(name: String): Board = when (name) {
        "qwerty" -> rows(Triple("qwertyuiop", 0f, 1f), Triple("asdfghjkl", 0.5f, 1f), Triple("zxcvbnm", 1.5f, 1f))
        "qwerty-ms" -> rows(Triple("qwertyuiop", 0f, 1f), Triple("asdfghjkl;", 0f, 1f), Triple("zxcvbnm", 1.5f, 1f))
        "nine" -> rows(Triple("23", 1.6f + 2.2f, 2.2f), Triple("456", 1.6f, 2.2f), Triple("789", 1.6f, 2.2f))
        "k14" -> rows(Triple(g14("q e t u o"), 0f, 2f), Triple(g14("a d g j l"), 0f, 2f), Triple(g14("z c b m"), 1f, 2f))
        "k18" -> rows(
            Triple(grouped(NineKeyLetters.G18, "q w r y u i p"), 0f, ROW / 7),
            Triple(grouped(NineKeyLetters.G18, "a s f h j l"), 0f, ROW / 6),
            Triple(grouped(NineKeyLetters.G18, "z x v b m"), (ROW - 5 * K18_LAST) / 2, K18_LAST),
        )
        else -> throw IllegalArgumentException("no keyboard $name: qwerty, qwerty-ms, nine, k14, k18")
    }

    /** σ and a shift across ([dx]) and down ([dy]), in key widths; [halves]: keys left of the middle tapped that far right, the others that far left (two thumbs reaching in). */
    data class Hand(val sigma: Float, val dx: Float = 0f, val dy: Float = 0f, val halves: Float = 0f)

    /** `s<σ>` or `s<σ>/<dx>/<dy>/<halves>`; `s0.2down`: σ 0.2, 0.15 down. */
    fun hand(name: String): Hand = when (name) {
        "s0.2down" -> Hand(SIGMA, dy = DOWN)
        else -> name.removePrefix("s").split('/').map { it.toFloat() }.let { p ->
            Hand(p[0], p.getOrElse(1) { 0f }, p.getOrElse(2) { 0f }, p.getOrElse(3) { 0f })
        }
    }

    /** `<keyboard>:<hand>:<clean|slip>[:<seed>]`: [samples] tapped so; a sample none of whose keys is on the keyboard left out. */
    fun convert(samples: List<Sample>, spec: String): List<Sample> {
        val p = spec.split(':')
        require(p.size in 3..4 && p[2] in setOf("clean", "slip")) { "--touch <keyboard>:<hand>:<clean|slip>[:<seed>], not $spec" }
        val board = board(p[0])
        val hand = hand(p[1])
        val slip = p[2] == "slip"
        val seed = p.getOrNull(3)?.toLong() ?: 1L
        val middle = board.keys.values.maxOf { it.right } / 2
        return samples.mapIndexedNotNull { index, s ->
            val random = Random(seed * SEED_STEP + index)
            val keyed = s.input.indices.filter { s.input[it] in board.keys }
            if (keyed.isEmpty()) return@mapIndexedNotNull null
            val at = if (slip) keyed[pick(s.expected + "touch", keyed.size)] else -1
            val input = StringBuilder(s.input)
            val touches = s.input.indices.map { i ->
                val c = s.input[i]
                val box = board.keys[c] ?: return@map null
                val around = board.boxes.around(c).map { it.char }
                // a slip with nowhere to land (no key beside it): the sample left out
                if (i == at && around.isEmpty()) return@mapIndexedNotNull null
                var tries = 0
                while (true) {
                    if (++tries > MAX_TRIES) return@mapIndexedNotNull null
                    val shift = if (hand.halves != 0f) (if (box.cx < middle) hand.halves else -hand.halves) else hand.dx
                    val x = box.cx + (shift + random.nextGaussian().toFloat() * hand.sigma) * KEY_W
                    val y = box.cy + (hand.dy + random.nextGaussian().toFloat() * hand.sigma) * KEY_W
                    val hit = if (i == at) around.firstOrNull { board.keys.getValue(it).has(x, y) } else c.takeIf { box.has(x, y) }
                    if (hit != null) {
                        input.setCharAt(i, hit)
                        return@map board.boxes.touch(hit, x, y)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            s.copy(input = input.toString(), touches = touches, tag = if (slip) "slip" else s.tag)
        }
    }

    private fun KeyBoxes.Box.has(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

    // a row's width in QWERTY keys, and the 18 keys' last row's (GroupedKeyboard's Shape: 0.14 of the row)
    private const val ROW = 10f
    private const val K18_LAST = 1.4f
    private const val SIGMA = 0.2f
    private const val DOWN = 0.15f
    private const val SEED_STEP = 1_000_003L
    private const val MAX_TRIES = 3_000_000

    private fun pick(key: String, size: Int): Int {
        val crc = CRC32().apply { update(key.toByteArray()) }
        return (crc.value % size).toInt()
    }
}
