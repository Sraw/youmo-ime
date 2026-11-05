/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.eval.VoicePostRun.Mark
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoicePostRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun wordsAreHanCharactersOrLatinRunsEachWithTheMarkAfterIt() {
        val t = VoicePostRun.tokens("我买了3.5斤 Apple，好吗？ok.")
        assertEquals(listOf("我", "买", "了", "3.5", "斤", "apple", "好", "吗", "ok"), t.words)
        assertEquals(listOf(null, null, null, null, null, Mark.COMMA, null, Mark.QUESTION, Mark.PERIOD), t.after)
    }

    @Test
    fun aSecondMarkInARowIsNotCountedAgain() {
        assertEquals(listOf(Mark.PERIOD), VoicePostRun.tokens("好。，").after)
    }

    @Test
    fun theMarksInsideAreScoredByKindTheEndApart() {
        val s = VoicePostRun.Score()
        // ref: 。 inside, ， inside, 。 at the end; hyp: ， where the 。 was, nothing where the ， was, no end mark
        VoicePostRun.score("今天下雨。我们不去，在家。", "今天下雨，我们不去在家", s)
        assertEquals(0, s.wordErrors)
        assertEquals(0, s.tp.sum())
        assertEquals(1, s.fp[Mark.COMMA.ordinal])
        assertEquals(1, s.fn[Mark.PERIOD.ordinal])
        assertEquals(1, s.fn[Mark.COMMA.ordinal])
        assertEquals(1, s.refEnds)
        assertEquals(0, s.hypEnds)
        assertEquals(0, s.endsSame)
        // written: 3 marks missed or wrong of 10 words and 3 marks
        assertEquals(3, s.writtenErrors)
        assertEquals(13, s.refWritten)
    }

    @Test
    fun theReferencesNumbersAreReadAsTheAppTypesThem() {
        val s = VoicePostRun.Score()
        VoicePostRun.score("二零二六年", "2026年", s)
        assertEquals(0, s.wordErrors)
    }

    @Test
    fun doubledMarksAreCounted() {
        val s = VoicePostRun.Score()
        VoicePostRun.score("好的。", "好的。。", s)
        assertEquals(1, s.doubled)
    }

    @Test
    fun alignmentPairsWordsAndMarksWhatIsMissing() {
        assertEquals(listOf(0 to 0, 1 to null, 2 to 1), VoicePostRun.align(listOf("a", "b", "c"), listOf("a", "c")))
        assertEquals(listOf(0 to 0, null to 1, 1 to 2), VoicePostRun.align(listOf("a", "c"), listOf("a", "b", "c")))
    }

    @Test
    fun aFileIsReadAndScoredStretchesJoinedAsTheAppJoinsThem() {
        val file = tmp.newFile("seg-x.jsonl")
        file.writeText(
            """{"wav": "1.wav", "ref": "你好，世界。", "stretches": [{"start": 0.5, "end": 1.2, "nbest": ["你好。"], "scores": [0], "app": "你好，"}, """ +
                """{"start": 2.0, "end": 2.6, "nbest": ["世界"], "scores": [0], "app": "世界。"}]}""" + "\n",
        )
        val clips = VoicePostRun.read(file, "app")
        assertEquals(500L, clips.single().stretches.first().startMs)
        val out = StringBuilder()
        VoicePostRun.run(listOf(file.path), "app", null, out)
        val row = out.lines()[1].split('\t')
        assertEquals(listOf("x", "1", "2", "0.00%", "0.00%"), row.take(5))
        val top = StringBuilder()
        val misses = tmp.newFile("misses.tsv")
        VoicePostRun.run(listOf(file.path), "top", misses.path, top)
        // the first hypotheses: 你好。世界 — the words right, a period where the comma was and no end mark: 2 of 6
        assertEquals(listOf("0.00%", "33.33%"), top.lines()[1].split('\t').subList(3, 5))
        assertEquals(listOf("set\twav\tref\thyp", "x\t1.wav\t你好，世界。\t你好。世界"), misses.readLines())
        // every clip typed, a miss or not
        val texts = tmp.newFile("texts.tsv")
        VoicePostRun.run(listOf(file.path), "app", null, StringBuilder(), texts = texts.path)
        assertEquals(listOf("set\twav\tref\thyp", "x\t1.wav\t你好，世界。\t你好，世界。"), texts.readLines())
    }

    @Test
    fun theWrittenReferenceIsScoredAgainstWhereThereIsOne() {
        val file = tmp.newFile("seg-w.jsonl")
        file.writeText(
            """{"wav": "1.wav", "ref": "嗯我我去", "written": "我去。", "stretches": [{"start": 0, "end": 1, "nbest": ["我去。"], "scores": [0], "app": "我去。"}]}""" + "\n",
        )
        val out = StringBuilder()
        VoicePostRun.run(listOf(file.path), "app", null, out)
        assertEquals(listOf("0.00%", "0.00%"), out.lines()[1].split('\t').subList(3, 5))
    }

    @Test
    fun aClipWithoutAWrittenReferenceInASetWithThemIsLeftOutAndCounted() {
        val file = tmp.newFile("seg-u.jsonl")
        file.writeText(
            """{"wav": "1.wav", "ref": "我去", "written": "我去。", "stretches": [{"start": 0, "end": 1, "nbest": ["我去。"], "scores": [0], "app": "我去。"}]}""" + "\n" +
                """{"wav": "2.wav", "ref": "他来", "stretches": [{"start": 0, "end": 1, "nbest": ["她来。"], "scores": [0], "app": "她来。"}]}""" + "\n",
        )
        val out = StringBuilder()
        VoicePostRun.run(listOf(file.path), "app", null, out)
        val row = out.lines()[1].split('\t')
        assertEquals(listOf("1", "0.00%"), listOf(row[1], row[3]))
        assertEquals("1", row.last())
    }

    @Test
    fun aSetWithoutMarksScoresNoMarks() {
        val s = VoicePostRun.Score()
        VoicePostRun.score("我去", "我去", s)
        assertEquals("-", VoicePostRun.row("x", s).split('\t')[8])
    }
}
