/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Where the 14 keys were tapped, weighed in the graph (design k14-tap-position §2.2): how far the
 * letters each syllable reads its keys as go against the taps, the spelling typed for it judged.
 */
class T9SegmenterTapsTest {

    private val g14 = NineKeyLetters.G14
    private val segmenter = T9Segmenter(abbreviations = false, letters = g14)

    private fun keys(spelling: String) = g14.typings(spelling).single()

    // [syllable]'s against on the edge of kind [kind] over the whole of [input]
    private fun against(graph: SyllableGraph, syllable: String, kind: Kind = Kind.SYLLABLE): Float {
        val id = Syllables.id(syllable)
        for (e in graph.edges(graph.start)) {
            if (graph.kind(e) != kind || graph.to(e) != graph.end) continue
            val i = graph.matches(e).indexOf(id)
            if (i >= 0) return graph.against(e, i)
        }
        error("no $kind $syllable in\n$graph")
    }

    @Test
    fun aLetterOnTheOtherSideOfItsKeyCostsByHowFarTheTapWentPastTheCentre() {
        // BN UI: b and u left, n and i right
        val graph = segmenter.segment(keys("ni"), floatArrayOf(1f, 0.5f))
        assertEquals(0f, against(graph, "ni"), 0f)
        assertEquals(0f, against(graph, "nu"), 0f)
        assertEquals(1f, against(graph, "bu"), 1e-6f)
        assertEquals(1f, against(graph, "bi"), 1e-6f)
        val both = segmenter.segment(keys("ni"), floatArrayOf(0.75f, 0.75f))
        assertEquals(0f, against(both, "ni"), 0f)
        assertEquals(1f, against(both, "bu"), 1e-6f)
        assertEquals(0.5f, against(both, "bi"), 1e-6f)
        // the start of a syllable at the end too
        val started = segmenter.segment(keys("hao").take(1), floatArrayOf(0.25f))
        assertEquals(0.5f, against(started, "hao", Kind.PARTIAL), 1e-6f)
        assertEquals(0f, against(started, "gao", Kind.PARTIAL), 0f)
    }

    @Test
    fun noPlaceOrTheCentreReadsAsNothingTapped() {
        val input = keys("nihao")
        val plain = segmenter.segment(input).toString()
        assertEquals(plain, segmenter.segment(input, null).toString())
        for (taps in listOf(FloatArray(5) { Float.NaN }, FloatArray(5) { 0.5f })) {
            val graph = segmenter.segment(input, taps)
            assertEquals(plain, graph.toString())
            for (e in 0 until graph.edgeCount) for (i in 0 until graph.matches(e).size) assertEquals(0f, graph.against(e, i), 0f)
        }
        // the nine keys have no place, whatever is passed
        val nine = T9Segmenter(abbreviations = false)
        val graph = nine.segment("64", floatArrayOf(1f, 1f))
        for (e in 0 until graph.edgeCount) for (i in 0 until graph.matches(e).size) assertEquals(0f, graph.against(e, i), 0f)
    }

    @Test
    fun theLettersJudgedAreThoseTyped() {
        // üe typed ue: UI's u, the left
        val lue = segmenter.segment(keys("lue"), floatArrayOf(0.5f, 0.25f, 0.5f))
        assertEquals(0f, against(lue, "lve"), 0f)
        assertEquals(0.5f, against(segmenter.segment(keys("lue"), floatArrayOf(0.5f, 0.75f, 0.5f)), "lve"), 1e-6f)
        // z for zhi, with z↔zh: ZX's z and UI's i are judged, as for zi
        val fuzzy = T9Segmenter(setOf(Fuzzy.Z_ZH), abbreviations = false, letters = g14)
        for (taps in listOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(0.75f, 0.25f))) {
            val graph = fuzzy.segment(keys("zi"), taps)
            assertEquals(taps.toList().toString(), against(graph, "zi"), against(graph, "zhi"), 0f)
        }
        assertNotEquals(0f, against(fuzzy.segment(keys("zi"), floatArrayOf(1f, 1f)), "zhi"))
    }

    @Test
    fun aSyllableOnTheSameKeysWithTheSameFlagsHasOneSpelling() {
        // which spelling's letters are judged must not hang on the order they are indexed in
        assertEquals(0, T9Segmenter(letters = g14).letterTies)
        assertEquals(0, T9Segmenter(Fuzzy.entries.toSet(), letters = g14).letterTies)
    }

    @Test
    fun aLetterTypedAsItselfHasNoPlace() {
        // B chosen off BN's long press: b alone, nothing to weigh there
        val graph = segmenter.segment("B" + keys("ni").drop(1), floatArrayOf(1f, 0.5f))
        assertEquals(0f, against(graph, "bu"), 0f)
    }
}
