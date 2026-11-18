/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What [SyllableGraph] says of the slips apart from 简拼 in the middle (061-sp-abbrev §3.6): [SyllableGraph.withoutSlips]
 * drops only the slips, [SyllableGraph.readsSlipWithin] counts them as a walk over the edges does, and
 * [SyllableGraph.readsWhole] reads neither. On random keys of 双拼 on all four keyboards, tapped anywhere on their keys.
 */
class SyllableGraphTest {

    // an edge as where it goes and what it reads, to compare two graphs' edges
    private fun SyllableGraph.edgeText(e: Int): String {
        val m = matches(e)
        return "${from(e)}-${to(e)} ${kind(e)} ${isSlip(e)} " + (0 until m.size).joinToString(",") { "${Syllables.spelling(m.syllable(it))}:${m.flags(it)}" }
    }

    private fun SyllableGraph.edgeSet(): Set<String> = (0 until edgeCount).mapTo(HashSet()) { edgeText(it) }

    // the edges [keep] says, of those reached from the start along them
    private fun SyllableGraph.reachedBy(keep: (Int) -> Boolean): Set<String> {
        val reached = BooleanArray(end + 1).also { it[start] = true }
        val kept = HashSet<String>()
        for (at in start..end) {
            if (!reached[at]) continue
            for (e in edges(at)) if (keep(e)) {
                kept += edgeText(e)
                reached[to(e)] = true
            }
        }
        return kept
    }

    // whether [to] is reached from [from] along whole syllables as typed, edge by edge
    private fun SyllableGraph.wholeByEdges(from: Int, to: Int): Boolean {
        if (from >= to) return false
        val ok = BooleanArray(end + 1).also { it[from] = true }
        for (at in from until to) {
            if (!ok[at]) continue
            for (e in edges(at)) {
                val typed = (kind(e) == Kind.SYLLABLE || kind(e) == Kind.LETTER) && !isAbbreviation(e) && !isSlip(e)
                if (typed && to(e) <= to) ok[to(e)] = true
            }
        }
        return ok[to]
    }

    private fun touches(keys: String, board: KeyBoxes, random: Random): Array<Touch?> =
        keys.map { c -> if (random.nextBoolean()) TouchBoxes.tap(board, c) else TouchBoxes.tap(board, c, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f) }
            .toTypedArray()

    private class Seen(var slips: Int = 0, var alone: Int = 0, var whole: Int = 0)

    private fun check(what: String, segmenter: Segmenter, keys: String, board: KeyBoxes, random: Random, seen: Seen) {
        val g = segmenter.segment(keys, null, touches(keys, board, random))
        val slips = (0 until g.edgeCount).any(g::isSlip)
        assertEquals("$what $keys", slips, g.hasSlips)
        val w = g.withoutSlips()
        if (!slips) assertSame("$what $keys", g, w)
        // no slip left, every key read alone still there that the start reaches without slips
        assertEquals("$what $keys", g.reachedBy { !g.isSlip(it) }, w.edgeSet())
        assertFalse("$what $keys", w.hasSlips)
        if (slips) seen.slips++
        if (w.hasAbbreviations) seen.alone++
        for (a in g.start..g.end) for (b in a..g.end) {
            val byEdges = (a until b).any { at -> g.edges(at).any(g::isSlip) }
            assertEquals("$what $keys $a-$b", byEdges, g.readsSlipWithin(a, b))
            val whole = g.wholeByEdges(a, b)
            assertEquals("$what $keys $a-$b", whole, g.readsWhole(a, b))
            if (whole && a == g.start && b == g.end) seen.whole++
        }
    }

    @Test
    fun slipsAreToldApartFromKeysReadAlone() {
        val random = Random(617)
        val seen = Seen()
        val full = ShuangpinSegmenter(ShuangpinScheme.XIAOHE, neighbours = true)
        val grouped = listOf(NineKeyLetters.G18 to TouchBoxes.G18, NineKeyLetters.G14 to TouchBoxes.G14, NineKeyLetters.NINE_SP to TouchBoxes.NINE_SP)
            .map { (letters, board) -> Triple(letters, board, GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, letters, neighbours = true)) }
        repeat(300) {
            val keys = (1..random.nextInt(2, 9)).map { 'a' + random.nextInt(26) }.joinToString("")
            check("全键盘", full, keys, TouchBoxes.QWERTY, random, seen)
            for ((letters, board, segmenter) in grouped) check("$letters", segmenter, keys.map(letters::key).joinToString(""), board, random, seen)
        }
        // enough of each: graphs with a slip, graphs that keep keys read alone without them, codes read whole
        assertTrue("${seen.slips} with slips", seen.slips > 300)
        assertTrue("${seen.alone} with keys read alone", seen.alone > 600)
        assertTrue("${seen.whole} read whole", seen.whole > 200)
    }
}
