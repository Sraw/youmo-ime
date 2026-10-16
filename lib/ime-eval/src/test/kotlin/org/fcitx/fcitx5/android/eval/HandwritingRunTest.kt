/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.handwriting.HandwritingDecoder
import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.handwriting.handwritingDataBytes
import org.fcitx.fcitx5.android.engine.handwriting.read
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HandwritingRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The engine's handwriting test words: 谈 and 淡 alike to the pad, 谈话 a word; 人 likelier than 山 alone, 山 after 高. */
    private val data = handwritingData

    private val run = HandwritingRun(data)

    private fun decoder() = run.decoder(HandwritingDecoder.INK_WEIGHT, HandwritingDecoder.ALTERNATIVES)

    // 谈话, its 谈 read as 淡 first; 山 after 高, where the ink reads 人 first
    private val rows = listOf(
        HandwritingRun.Row("", "谈话", listOf(read("淡", "谈", step = 0.3), read("话"))),
        HandwritingRun.Row("高", "山", listOf(read("人", "山", step = 0.5))),
    )

    private fun HandwritingRun.Tally.counts() = listOf(chars, final, asWritten, inTen)

    @Test
    fun theInkAloneIsRightWhereItsFirstReadingIs() {
        assertEquals(listOf(3, 1, 1, 3), run.ink(rows).counts())
    }

    @Test
    fun aSentenceIsRightOnceWrittenWhereALaterCharacterPutsAnEarlierOneRight() {
        val read = run.sentences(rows, decoder())
        // 淡 shows until 话 is written; 山 after 高 from the first
        assertEquals(listOf(3, 3, 2, 3), read.counts())
        assertEquals("final 100.00%  as written  66.67%  last in ten 100.00%  (3 characters)", read.toString())
        val both = HandwritingRun.Tally().apply { add(read); add(run.ink(rows)) }
        assertEquals(listOf(6, 4, 3, 6), both.counts())
    }

    @Test
    fun aPickPutsThatOfTheBestSentencesFirst() {
        // the second best each time: 谈, then 淡话; 人, not 山
        assertEquals(listOf(3, 1, 2, 3), run.sentences(rows, decoder()) { _, _ -> 1 }.counts())
        // past the best: the decoder's
        assertEquals(listOf(3, 3, 2, 3), run.sentences(rows, decoder()) { _, _ -> 9 }.counts())
    }

    /** Picks [word] on the last of its [slices], before then not done: a large model taking more than the default's pause. */
    private class Late(override val slices: Int, private val word: String) : SentenceRefiner {
        var calls = 0

        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? =
            if (++calls < slices) null else readings.indexOf(word).takeIf { it >= 0 } ?: SentenceRefiner.NONE
    }

    @Test
    fun highPutsTheLargeModelsPickFirstOnceItIsGivenItsSlices() {
        // 山 after 高 as the decoder reads it: the large model picks 人, the second, on its last slice
        val late = Late(SentenceRefiner.SLICES + 8, "人")
        assertEquals(listOf(1, 0, 0, 1), run.sentences(rows.drop(1), decoder(), HandwritingRun.refined(late) { _, _ -> 0 }).counts())
        // one that has nothing to say keeps the small model's pick
        val none = Late(1, "无")
        assertEquals(listOf(1, 1, 1, 1), run.sentences(rows.drop(1), decoder(), HandwritingRun.refined(none) { _, _ -> 0 }).counts())
        // and one that never picks in its slices: given no more than them, the small model's pick stands
        val never = object : SentenceRefiner {
            var calls = 0
            override val slices = 3

            override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
                calls++
                return null
            }
        }
        assertEquals(listOf(1, 1, 1, 1), run.sentences(rows.drop(1), decoder(), HandwritingRun.refined(never) { _, _ -> 0 }).counts())
        assertTrue(never.calls > 0)
    }

    @Test
    fun theCommandReadsTheRowsByTheInkThenAsSentences() {
        val data = File(tmp.root, "pinyin.data").apply { writeBytes(handwritingDataBytes) }
        // the rows above, as the training sources write them
        val file = File(tmp.root, "rows.tsv").apply { writeText("casia\t\t谈话\t淡:0 谈:-0.3\t话:0\ncasia\t高\t山\t人:0 山:-0.5\n") }
        fun lines(vararg options: String): List<String> {
            val out = StringBuilder()
            assertEquals(0, runCli(arrayOf("handwriting", data.path, file.path, *options), out, StringBuilder()))
            return out.lines().filter { it.isNotEmpty() }
        }
        // no model given: the ink alone, then the sentence at the default ink weight, and nothing more
        val all = lines()
        assertEquals(2, all.size)
        assertEquals("ink alone            ${HandwritingRun.Tally(3, 1, 1, 3)}", all[0])
        assertTrue(all[1], all[1].startsWith("sentence, ink ${HandwritingDecoder.INK_WEIGHT}") && all[1].endsWith("${HandwritingRun.Tally(3, 3, 2, 3)}"))
        // a sentence line for each weight; --limit reads the first rows alone
        assertEquals(3, lines("--weights", "1.0,2.0").size)
        assertEquals("ink alone            ${HandwritingRun.Tally(2, 1, 1, 2)}", lines("--limit", "1")[0])
    }

    @Test
    fun aCharacterPastTheBmpIsOneCharacterAndOnePosition() {
        val rows = listOf(HandwritingRun.Row("", "𠀀天", listOf(read("𠀀"), read("天"))))
        assertEquals(listOf("𠀀", "天"), rows[0].characters)
        assertEquals(listOf(2, 2, 2, 2), run.ink(rows).counts())
        assertEquals(listOf(2, 2, 2, 2), run.sentences(rows, decoder()).counts())
    }

    @Test
    fun rowsAreReadAsTheTrainingSourcesWriteThem() {
        val file = File(tmp.root, "rows.tsv").apply {
            writeText(
                listOf(
                    "casia\t\t谈话\t淡:-0.1 谈:-1.5\t话:-0.2",
                    // a reading that is a colon itself: the score is after the last
                    "casia\t他说\t说:\t说:-0.1\t::-0.3 ;:-2",
                    // one past the BMP, two chars, is one position
                    "casia\t\t𠀀天\t𠀀:-0.1\t天:-0.2",
                    // a position short of the characters, or none at all: no row
                    "casia\t\t谈话\t淡:-0.1",
                    "casia\t\t谈",
                ).joinToString("\n", postfix = "\n"),
            )
        }
        val read = HandwritingRun.read(file.path)
        assertEquals(listOf("谈话", "说:", "𠀀天"), read.map { it.text })
        assertEquals(listOf("", "他说", ""), read.map { it.before })
        assertEquals(listOf("淡", "谈"), read[0].positions[0].texts)
        assertEquals(listOf(-0.1, -1.5), read[0].positions[0].scores.toList())
        assertEquals(listOf(":", ";"), read[1].positions[1].texts)
        assertEquals(listOf(-0.3, -2.0), read[1].positions[1].scores.toList())
        assertEquals(2, read[2].positions.size)
    }
}
