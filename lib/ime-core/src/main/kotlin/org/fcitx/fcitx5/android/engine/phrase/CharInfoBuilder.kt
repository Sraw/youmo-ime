/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.data.BitPacked
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.phrase.CharInfo.Section
import org.fcitx.fcitx5.android.engine.stroke.Strokes

/**
 * Compiles [CharInfo] on the build host (ime-dict-tool's `charinfo`) and in tests, from:
 *
 * - Unihan's kRSUnicode (the Kangxi radical, `'` after a simplified one: 说 `149'.7`) and
 *   kTotalStrokes (the mainland's count first), [unihan];
 * - CJKRadicals.txt, each radical's character ([radicals]): `149'` 讠;
 * - 汉语拆字字典's ways to take a character apart ([chaizi]): 江 `水 工` `氵 工`;
 * - rime-stroke's stroke orders ([strokes]): the first's length for a character Unihan counts no
 *   strokes of; all of them to check the mainland's against;
 * - `lexicon/stroke-order.tsv`, the stroke orders as the mainland writes them ([orders]), first taken
 *   from cnchar (MIT) and kept by this project since: rime-stroke's first order of a character is
 *   Taiwan's as often as not (出 丨乛丨乛丨, not 乛丨丨乛丨; 必 丶乛丶丶丿, not 丶乛丶丿丶), and the user wants the
 *   mainland's standard (2026-11-02). [build] fails on an order whose strokes are not as many as
 *   Unihan counts, or that is none of rime-stroke's orders of the character, unless its line says why
 *   (and on a line that says why of neither):
 *   that is how cnchar's slips were found (皙 without 白's 丿, 德's 十 a 一丿). A character with the
 *   mainland's order counts its strokes as that order does (Unihan counts 范 as Taiwan's, 9).
 *
 * The radical is written as the mainland does where the character's parts show it so (江 氵, 打 扌,
 * 草 艹: [VARIANTS]), else as CJKRadicals has it (和 口, 说 讠): Unicode's Kangxi radicals, not the
 * mainland's 201 (no table of those under a licence the app can take). Of a character's ways to take it
 * apart, the first with the radical so written (江 氵 工, not 水 工; 说 讠 兑, not 言 兑), else the first.
 */
class CharInfoBuilder {

    // code point to its radical (kRSUnicode's number and marks: 149') and strokes
    private val radicalOf = HashMap<Int, String>()
    private val totalStrokes = HashMap<Int, Int>()
    private val strokeOrders = HashMap<Int, Int>()

    // code point to rime-stroke's orders of it, all of them
    private val rimeOrders = HashMap<Int, MutableSet<String>>()

    // code point to its stroke order as the mainland writes it, a [Strokes.KEYS] letter a stroke; the
    // line it is on, and the code points whose line says why it differs from Unihan's or rime-stroke's
    private val orders = HashMap<Int, String>()
    private val orderLines = HashMap<Int, Int>()
    private val explained = HashSet<Int>()
    private var ordersSource = ""

    // radical (85, 149') to its character's code point
    private val radicalChars = HashMap<String, Int>()

    // code point to its ways apart, each its parts' code points, in the order listed
    private val ways = HashMap<Int, MutableList<IntArray>>()

    /** Unihan's `U+6C5F<TAB>kRSUnicode<TAB>85.3` lines (Unihan_IRGSources.txt); other fields are skipped. */
    fun unihan(lines: Sequence<String>, source: String) = apply {
        lines.forEachIndexed { n, line ->
            if (line.startsWith('#') || line.isBlank()) return@forEachIndexed
            val f = line.split('\t')
            if (f.size < 3 || (f[1] != RS && f[1] != TOTAL)) return@forEachIndexed
            val cp = codePoint(f[0]) ?: throw SourceException(source, n + 1, "not a code point: ${f[0]}")
            val first = f[2].trim().substringBefore(' ')
            if (f[1] == RS) {
                val radical = first.substringBefore('.')
                if (radical.trimEnd('\'').toIntOrNull() == null) throw SourceException(source, n + 1, "not a radical: $first")
                radicalOf[cp] = radical
            } else {
                totalStrokes[cp] = first.toIntOrNull()?.takeIf { it > 0 } ?: throw SourceException(source, n + 1, "not a count: $first")
            }
        }
    }

    /** CJKRadicals.txt's `149'; 2EC8; 8BA0` lines: a radical, its radical sign, its character. */
    fun radicals(lines: Sequence<String>, source: String) = apply {
        lines.forEachIndexed { n, line ->
            val body = line.substringBefore('#').trim()
            if (body.isEmpty()) return@forEachIndexed
            val f = body.split(';').map { it.trim() }
            val cp = f.getOrNull(2)?.let { codePoint("U+$it") }
            if (f.size != 3 || cp == null) throw SourceException(source, n + 1, "not a radical line: $line")
            radicalChars[f[0]] = cp
        }
    }

    /** 汉语拆字字典's `江<TAB>水 工<TAB>氵 工` lines; a way of a part not one character, or of the character itself, is skipped. */
    fun chaizi(lines: Sequence<String>) = apply {
        for (line in lines) {
            val f = line.split('\t')
            val c = f[0].trim()
            if (f.size < 2 || c.codePointCount(0, c.length) != 1) continue
            val cp = c.codePointAt(0)
            val known = ways.getOrPut(cp) { ArrayList() }
            for (way in f.drop(1)) {
                val parts = way.trim().split(' ').filter { it.isNotEmpty() }
                if (parts.size < 2 || parts.any { it.codePointCount(0, it.length) != 1 || it == c }) continue
                val cps = IntArray(parts.size) { parts[it].codePointAt(0) }
                if (known.none { it.contentEquals(cps) }) known += cps
            }
            if (known.isEmpty()) ways.remove(cp)
        }
    }

    /**
     * One of rime-stroke's orders of [codePoint], in [Strokes.KEYS]' letters, its first first: the first
     * counts the strokes of a character Unihan has no count of; all check the mainland's order ([orders]).
     */
    fun strokes(codePoint: Int, order: String) = apply {
        if (order.isEmpty()) return@apply
        strokeOrders.putIfAbsent(codePoint, order.length)
        rimeOrders.getOrPut(codePoint) { HashSet() } += order
    }

    /**
     * `lexicon/stroke-order.tsv`'s `行<TAB>332112[<TAB>why]` lines (`#` a comment): a character's strokes in
     * the order 《现代汉语通用字笔顺规范》 gives, by the standard's numbers (1 横, 2 竖, 3 撇, 4 点, 5 折), and,
     * for one [build] would otherwise fail on, why its count is not Unihan's or its order none of
     * rime-stroke's.
     *
     * @throws SourceException on a line that is not one
     */
    fun orders(lines: Sequence<String>, source: String) = apply {
        ordersSource = source
        lines.forEachIndexed { n, line ->
            if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
            val f = line.trimEnd('\r').split('\t')
            val c = f[0]
            if (c.codePointCount(0, c.length) != 1) throw SourceException(source, n + 1, "not one character: $c")
            val order = f.getOrNull(1).orEmpty()
            if (order.isEmpty() || order.any { it !in '1'..'5' }) throw SourceException(source, n + 1, "not a stroke order (1 to 5 a stroke): $order")
            val cp = c.codePointAt(0)
            if (cp in orders) throw SourceException(source, n + 1, "$c twice")
            orders[cp] = order.map { Strokes.KEYS[it - '1'] }.joinToString("")
            orderLines[cp] = n + 1
            if (f.getOrNull(2).orEmpty().isNotBlank()) explained += cp
        }
    }

    /** [codePoint]'s radical and parts, as [build] chooses them; null for a character Unihan gives no radical. */
    fun entry(codePoint: Int): CharInfo.Entry? {
        val radical = radicalOf[codePoint] ?: return null
        val number = radical.trimEnd('\'').toInt()
        val own = radicalChars[radical] ?: radicalChars[number.toString()] ?: return null
        // a simplified radical is written as CJKRadicals has it (说 讠 兑, not 言 兑)
        val variants = if (radical.endsWith('\'')) setOf(own) else VARIANTS[number].orEmpty()
        val way = ways[codePoint]?.let { all -> all.firstOrNull { w -> w.any { it in variants } } ?: all.first() }
        val shown = way?.firstOrNull { it in variants } ?: own
        val order = orders[codePoint]
        val strokes = order?.length ?: totalStrokes[codePoint] ?: strokeOrders[codePoint] ?: 0
        return CharInfo.Entry(String(Character.toChars(shown)), strokes, way?.map { String(Character.toChars(it)) }, order)
    }

    /**
     * @throws SourceException naming the stroke orders Unihan counts or rime-stroke writes otherwise with
     * no note why, and the notes with nothing to say why of (a note is no place for a line's history)
     */
    fun build(): DataFile.Writer {
        val odd = orders.keys.sortedBy { orderLines.getValue(it) }.mapNotNull { cp ->
            val order = orders.getValue(cp)
            val c = String(Character.toChars(cp))
            val count = totalStrokes[cp]
            val why = when {
                count != null && count != order.length -> "$c ${order.length} strokes, Unihan $count"
                rimeOrders[cp]?.contains(order) == false -> "$c ${Strokes.shown(order)}, rime-stroke ${rimeOrders.getValue(cp).joinToString("/", transform = Strokes::shown)}"
                else -> null
            }
            when {
                cp in explained && why == null -> cp to "$c a note, but its order is as Unihan counts and rime-stroke writes"
                cp !in explained && why != null -> cp to "$why, with no note why"
                else -> null
            }
        }
        if (odd.isNotEmpty()) {
            throw SourceException(
                ordersSource, orderLines.getValue(odd[0].first),
                "${odd.size} stroke orders unlike Unihan's count or rime-stroke's: " + odd.joinToString("; ") { it.second },
            )
        }
        val chars = radicalOf.keys.sorted().mapNotNull { cp -> entry(cp)?.let { cp to it } }
        val shown = chars.map { it.second.radical.codePointAt(0) }.distinct().sorted()
        val index = shown.withIndex().associate { (i, cp) -> cp to i }
        val taken = chars.filter { it.second.parts != null }
        val starts = IntArray(taken.size + 1)
        taken.forEachIndexed { i, (_, e) -> starts[i + 1] = starts[i] + e.parts!!.size }
        val parts = taken.flatMap { (_, e) -> e.parts!!.map { it.codePointAt(0) } }.toIntArray()
        val ordered = chars.filter { it.second.order != null }
        val orderStarts = IntArray(ordered.size + 1)
        ordered.forEachIndexed { i, (_, e) -> orderStarts[i + 1] = orderStarts[i] + e.order!!.length }
        val strokes = ordered.flatMap { (_, e) -> e.order!!.map { Strokes.KEYS.indexOf(it) } }.toIntArray()
        return DataFile.Writer(DataFile.KIND_CHAR_INFO, CharInfo.VERSION)
            .add(Section.CHARS, BitPacked.encode(chars.size) { chars[it].first })
            .add(Section.RADICALS, BitPacked.encode(shown.toIntArray()))
            .add(Section.RADICAL, BitPacked.encode(chars.size) { index.getValue(chars[it].second.radical.codePointAt(0)) })
            .add(Section.STROKES, BitPacked.encode(chars.size) { chars[it].second.strokes })
            .add(Section.PART_CHARS, BitPacked.encode(taken.size) { taken[it].first })
            .add(Section.PART_START, BitPacked.encode(starts))
            .add(Section.PARTS, BitPacked.encode(parts))
            .add(Section.ORDER_CHARS, BitPacked.encode(ordered.size) { ordered[it].first })
            .add(Section.ORDER_START, BitPacked.encode(orderStarts))
            .add(Section.ORDERS, BitPacked.encode(strokes))
    }

    private fun codePoint(field: String): Int? =
        field.takeIf { it.startsWith("U+") }?.substring(2)?.toIntOrNull(HEX)?.takeIf { Character.isValidCodePoint(it) }

    companion object {
        private const val RS = "kRSUnicode"
        private const val TOTAL = "kTotalStrokes"
        private const val HEX = 16

        /**
         * Kangxi radicals by number, each as the mainland writes it on the side or top of a character
         * (人 亻, 水 氵, 艸 艹, 辵 辶, 邑 and 阜 阝): shown where the character's parts have it.
         */
        val VARIANTS: Map<Int, Set<Int>> = mapOf(
            9 to "亻", 18 to "刂", 61 to "忄", 64 to "扌", 66 to "攵", 85 to "氵", 86 to "灬", 87 to "爫",
            93 to "牜", 94 to "犭", 96 to "王", 113 to "礻", 122 to "罒罓", 125 to "耂", 130 to "月", 140 to "艹",
            145 to "衤", 146 to "覀", 162 to "辶", 163 to "阝", 170 to "阝",
        ).mapValues { (_, s) -> s.codePoints().toArray().toSet() }
    }
}
