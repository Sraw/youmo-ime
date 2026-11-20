/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.KeyBoxes
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import java.util.Random
import java.util.zip.CRC32

/** Taps simulated with a place: the meant key's centre plus N(0, σ) and a shift; slip moves one key into a neighbour. */
object TouchSim {
    const val KEY_W = 40f
    const val KEY_H = 56f

    /** A keyboard's typing keys, each what it types and where. */
    class Board(val keys: Map<Char, KeyBoxes.Box>) {
        val boxes = KeyBoxes(keys)
    }

    // [rows] of keys [height] high: each its characters (a space a place, no key), its start and key widths
    private fun rows(vararg rows: Triple<String, Float, Float>, height: Float = KEY_H): Board {
        val keys = LinkedHashMap<Char, KeyBoxes.Box>()
        rows.forEachIndexed { r, (chars, offset, width) ->
            chars.forEachIndexed { i, c ->
                val left = (offset + i * width) * KEY_W
                if (c != ' ') keys[c] = KeyBoxes.Box(left, r * height, left + width * KEY_W, (r + 1) * height)
            }
        }
        return Board(keys)
    }

    private fun grouped(letters: NineKeyLetters, groups: String) = groups.split(' ').map { letters.key(it[0]) }.joinToString("")

    private fun g14(groups: String) = grouped(NineKeyLetters.G14, groups)

    /** The keyboard [name] in dp: `qwerty`, `qwerty-ms`, `nine`, `k14`, `k18`, `nine-sp`, `zy9`, `zy` (zhuyin's five rows each 0.8 as high). */
    fun board(name: String): Board = when (name) {
        "qwerty" -> rows(Triple("qwertyuiop", 0f, 1f), Triple("asdfghjkl", 0.5f, 1f), Triple("zxcvbnm", 1.5f, 1f))
        "qwerty-ms" -> rows(Triple("qwertyuiop", 0f, 1f), Triple("asdfghjkl;", 0f, 1f), Triple("zxcvbnm", 1.5f, 1f))
        "nine" -> rows(Triple("23", 1.6f + 2.2f, 2.2f), Triple("456", 1.6f, 2.2f), Triple("789", 1.6f, 2.2f))
        "nine-sp" -> rows(
            Triple(grouped(NineKeyLetters.NINE_SP, "a d"), 1.6f + 2.2f, 2.2f),
            Triple(grouped(NineKeyLetters.NINE_SP, "g j m"), 1.6f, 2.2f),
            Triple(grouped(NineKeyLetters.NINE_SP, "p t w"), 1.6f, 2.2f),
        )
        "zy9" -> (0 until ZhuyinKeys.GROUPS).map(ZhuyinKeys::groupCode).joinToString("").let { g ->
            rows(Triple(g.substring(0, 3), 1.4f, 2.4f), Triple(g.substring(3, 6), 1.4f, 2.4f), Triple(g.substring(6, 9), 1.4f, 2.4f), Triple(g.substring(9), 1.4f, 2.4f), height = ZHUYIN_ROW)
        }
        "zy" -> listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./").map { row ->
            row.map { q -> ZhuyinKeys.DACHEN_QWERTY.getValue(q).let { c -> if (c in ZhuyinKeys.SYMBOLS) ZhuyinKeys.symbolCode(c) else ' ' } }.joinToString("")
        }.let { r -> rows(Triple(r[0], 0f, ROW / 11), Triple(r[1], 0f, 1f), Triple(r[2], 0f, 1f), Triple(r[3], 0f, 0.85f), height = ZHUYIN_ROW) }
        "k14" -> rows(Triple(g14("q e t u o"), 0f, 2f), Triple(g14("a d g j l"), 0f, 2f), Triple(g14("z c b m"), 1f, 2f))
        "k18" -> rows(
            Triple(grouped(NineKeyLetters.G18, "q w r y u i p"), 0f, ROW / 7),
            Triple(grouped(NineKeyLetters.G18, "a s f h j l"), 0f, ROW / 6),
            Triple(grouped(NineKeyLetters.G18, "z x v b m"), (ROW - 5 * K18_LAST) / 2, K18_LAST),
        )
        else -> throw IllegalArgumentException("no keyboard $name: qwerty, qwerty-ms, nine, k14, k18, nine-sp, zy9, zy")
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
                if (c !in board.keys) return@map null
                val onto = if (i == at) board.boxes.around(c).map { it.char } else listOf(c)
                // a slip with nowhere to land (no key beside it): the sample left out
                val (hit, touch) = tap(board, c, onto, hand, random, middle) ?: return@mapIndexedNotNull null
                input.setCharAt(i, hit)
                touch
            }
            s.copy(input = input.toString(), touches = touches, tag = if (slip) "slip" else s.tag)
        }
    }

    /** [samples] with each tap's place across its key where the app sends one on [letters]' keyboard; NaN elsewhere. */
    fun placed(samples: List<Sample>, letters: NineKeyLetters): List<Sample> {
        if (letters != NineKeyLetters.G14 && letters != NineKeyLetters.G18) return samples
        return samples.map { s ->
            val touches = s.touches ?: return@map s
            s.copy(
                taps = s.input.indices.map { i ->
                    val t = touches.getOrNull(i)
                    if (t != null && letters.isCode(s.input[i])) Taps.placed((t.dx + HALF).toDouble().coerceIn(0.0, 1.0)) else Float.NaN
                },
            )
        }
    }

    /** A tap of [hand] aimed at [c], landing in one of [onto]: the key hit and the tap; null if none lands so in [MAX_TRIES]. */
    fun tap(board: Board, c: Char, onto: List<Char>, hand: Hand, random: Random, middle: Float = board.keys.values.maxOf { it.right } / 2): Pair<Char, Touch>? {
        val box = board.keys.getValue(c)
        val boxes = onto.mapNotNull { d -> board.keys[d]?.let { d to it } }
        if (boxes.isEmpty()) return null
        repeat(MAX_TRIES) {
            val shift = if (hand.halves != 0f) (if (box.cx < middle) hand.halves else -hand.halves) else hand.dx
            val x = box.cx + (shift + random.nextGaussian().toFloat() * hand.sigma) * KEY_W
            val y = box.cy + (hand.dy + random.nextGaussian().toFloat() * hand.sigma) * KEY_W
            val hit = boxes.firstOrNull { it.second.has(x, y) }?.first
            if (hit != null) return hit to checkNotNull(board.boxes.touch(hit, x, y))
        }
        return null
    }

    private fun KeyBoxes.Box.has(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

    // a row's width in QWERTY keys, and the 18 keys' last row's (GroupedKeyboard's Shape: 0.14 of the row)
    private const val ROW = 10f
    private const val HALF = 0.5f
    private const val K18_LAST = 1.4f
    private const val ZHUYIN_ROW = KEY_H * 0.8f
    private const val SIGMA = 0.2f
    private const val DOWN = 0.15f
    private const val SEED_STEP = 1_000_003L
    private const val MAX_TRIES = 3_000_000

    private fun pick(key: String, size: Int): Int {
        val crc = CRC32().apply { update(key.toByteArray()) }
        return (crc.value % size).toInt()
    }
}
