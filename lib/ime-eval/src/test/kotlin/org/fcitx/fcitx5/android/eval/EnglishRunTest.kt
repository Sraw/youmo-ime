/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.random.Random

/** What the English figures of the commits are counted by: the simulated user, and its slips. */
class EnglishRunTest {

    // words far apart on the keys, none typed a slip of another: hte, a slip of the, is no word here
    private val data = EnglishData.load(
        ByteBuffer.wrap(
            EnglishDataBuilder()
                .unigram(EnglishDataBuilder.UNKNOWN, -7f, 0f)
                .unigram(EnglishText.START, -9f, -0.5f)
                .unigram("the", -1.3f, -0.3f)
                .unigram("and", -1.5f, 0f)
                .unigram("don't", -2.9f, 0f)
                .unigram("cat", -4.2f, 0f)
                .bigram(EnglishText.START, "the", -1.5f, 0f)
                .bigram("the", "cat", -1.5f, 0f)
                .build()
                .toByteArray(),
        ),
    )

    private fun run(vararg sentences: String) = EnglishRun(data).run(sentences.toList(), noise = 0.0, seed = 1, threads = 1)

    @Test
    fun cleanWordsAreCommittedAsMeantThoughPunctuationEndsThem() {
        // dont typed for don't, put back by the session; the comma and the full stop are no part of the word
        val result = run("the cat, and don't the cat.")
        assertEquals(6, result.words)
        assertEquals(6, result.right)
        assertEquals(6, result.top3)
        // cat, each time after the: the→cat, and the second time the user's pair too
        assertEquals(2, result.predicted)
        assertEquals(0, result.slipped)
        assertEquals(0, result.broken)
        assertEquals(emptyList<String>(), result.misses)
    }

    // hte meant, corrected to the: the user's backspace takes it back, and makes hte theirs
    @Test
    fun aCorrectionTakenBackIsNotMadeAgain() {
        val result = run("the hte and hte.")
        assertEquals(4, result.words)
        assertEquals(3, result.right)
        assertEquals(1, result.broken)
        assertEquals(listOf("hte\thte\tthe"), result.misses)
    }

    // as in the app, a backspace after a correction punctuation made takes nothing back: hte is corrected again
    @Test
    fun aCorrectionPunctuationMadeStands() {
        val result = run("the hte, hte.")
        assertEquals(3, result.words)
        assertEquals(1, result.right)
        assertEquals(2, result.broken)
        assertEquals(listOf("hte\thte\tthe", "hte\thte\tthe"), result.misses)
    }

    @Test
    fun slipsAreOfEachSentencesOwnSeedOnAnyThreads() {
        val sentences = List(6) { "the cat and don't the cat." }
        val one = EnglishRun(data).run(sentences, noise = 0.3, seed = 5, threads = 1)
        val three = EnglishRun(data).run(sentences, noise = 0.3, seed = 5, threads = 3)
        // or the two could agree by nothing slipping
        assertTrue(one.slipped > 0)
        assertEquals(one.misses, three.misses)
        assertEquals(
            listOf(one.right, one.top3, one.predicted, one.slipped, one.fixed),
            listOf(three.right, three.top3, three.predicted, three.slipped, three.fixed),
        )
    }

    @Test
    fun aSlipIsTheSameForTheSameSeedAndNeverLeavesNothing() {
        assertEquals(EnglishRun.slip("hello", 0.5, Random(7)), EnglishRun.slip("hello", 0.5, Random(7)))
        assertEquals("hello", EnglishRun.slip("hello", 0.0, Random(7)))
        // every key slipping: both keys left out now and then, typed as they are instead
        for (seed in 0L until 200L) assertTrue("$seed", EnglishRun.slip("ab", 1.0, Random(seed)).isNotEmpty())
    }

    @Test
    fun theRowIsTheSameInAnyLocale() {
        val result = EnglishRun.Result().apply {
            words = 3
            right = 2
        }
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val row = result.row("clean")
            assertTrue(row, "66.7%" in row)
        } finally {
            Locale.setDefault(before)
        }
    }
}
