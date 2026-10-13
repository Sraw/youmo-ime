/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

class PrunerTest {

    // a after a: 0.042857 is just what backing off gives c, (1 - 0.9 - 0.042857) / (1 - 0.3 - 0.3) of 0.3,
    // so a c tells the model nothing and a b a lot; b a is the same, but the trigram b a b needs it
    private val model = ArpaModel.read(
        """
        \data\
        ngram 1=4
        ngram 2=4
        ngram 3=1

        \1-grams:
        -8	<s>	0
        ${log10(0.4)}	a	0
        ${log10(0.3)}	b	0
        ${log10(0.3)}	c	0

        \2-grams:
        ${log10(0.9)}	a b	0
        ${log10(0.042857)}	a c	0
        ${log10(0.5)}	<s> a	0
        ${log10(0.4)}	b a	0

        \3-grams:
        ${log10(0.95)}	b a b

        \end\
        """.trimIndent().reader().buffered(),
        "lm",
    )

    private fun prune(threshold: Double) = Pruner(model, threshold, model.words.indexOf("<s>"), 0.1).prune()

    private fun ArpaModel.id(word: String) = words.indexOf(word)

    private fun ArpaModel.has(vararg ngram: String) = when (ngram.size) {
        2 -> bigrams.indexOf(NgramKey.of(id(ngram[0]), id(ngram[1]))) >= 0
        else -> trigrams.indexOf(NgramKey.of(id(ngram[0]), id(ngram[1]), id(ngram[2]))) >= 0
    }

    /** What each context gives all the words, which must make one whatever was pruned. */
    private fun assertEachContextSumsToOne(m: ArpaModel) {
        val ids = m.words.indices
        for (v in ids) assertEquals(m.words[v], 1.0, ids.sumOf { 10.0.pow(m.log10(v, it)) }, 1e-6)
        for (u in ids) for (v in ids) {
            assertEquals("${m.words[u]} ${m.words[v]}", 1.0, ids.sumOf { 10.0.pow(m.log10(u, v, it)) }, 1e-6)
        }
    }

    @Test
    fun nothingGoesAtNoThresholdAndTheWeightsStillMakeEachContextWhole() {
        val kept = prune(0.0)
        assertEquals(4, kept.bigrams.size)
        assertEquals(1, kept.trigrams.size)
        assertEachContextSumsToOne(kept)
    }

    @Test
    fun whatBackingOffGivesAnywayGoesAndWhatItWouldGetWrongStays() {
        val pruned = prune(1e-4)
        assertTrue(pruned.has("a", "b"))
        assertTrue(!pruned.has("a", "c"))
        // a c was what backing off gives: the model says the same without it
        assertEquals(log10(0.042857), pruned.log10(pruned.id("a"), pruned.id("c")), 1e-4)
        assertEachContextSumsToOne(pruned)
    }

    @Test
    fun aBigramStaysWhileATrigramKeptHasItAsItsContext() {
        val pruned = prune(1e-4)
        assertTrue(pruned.has("b", "a", "b"))
        assertTrue(pruned.has("b", "a"))
    }

    // <s> a tells the model 0.0204 nats, weighed by how often a word starts its sentence (0.1): 0.002.
    // At <s>'s own unigram (1e-8) it would go at 1e-4 too, at SRILM's 1 stay at 5e-3
    @Test
    fun aSentenceStartWeighsAsOftenAsAWordStartsOne() {
        assertTrue(prune(1e-4).has("<s>", "a"))
        assertTrue(!prune(5e-3).has("<s>", "a"))
    }

    @Test
    fun atAHighThresholdOnlyTheUnigramsAreLeftAndTheyAreUntouched() {
        val pruned = prune(1.0)
        assertEquals(0, pruned.bigrams.size)
        assertEquals(0, pruned.trigrams.size)
        assertEquals(model.unigramProb.toList(), pruned.unigramProb.toList())
        assertEachContextSumsToOne(pruned)
    }
}
