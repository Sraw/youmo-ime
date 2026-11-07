/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolishTest {

    // resources/voice/polish_golden.py: voice-eval's tags.py and fusion_exp.py's freeze rule on fixed texts
    private val golden = javaClass.getResourceAsStream("/voice/polish-golden.tsv")!!.bufferedReader().readLines()
        .filter { it.isNotEmpty() }.map { it.split('\t') }

    private val tags = VoicePolishTags(golden.first { it[0] == "ops" }.drop(1), golden.first { it[0] == "afters" }.drop(1))

    private fun cases(kind: String) = golden.filter { it[0] == kind }.also { assertTrue("$kind cases", it.isNotEmpty()) }

    private fun codePoints(s: String) = s.codePoints().toArray()

    private fun indices(s: String) = if (s.isEmpty()) IntArray(0) else s.split(' ').map { it.toInt() }.toIntArray()

    // polish_golden.py's fake(): labels from the character, where it is in the window and the window's length
    private fun fake(w: IntArray): Pair<IntArray, IntArray> {
        val reps = tags.ops.size - 2
        val op = IntArray(w.size) { i ->
            val r = (w[i] * 31 + i * 7 + w.size * 3) % 11
            if (r < 7) 0 else if (r == 7) 1 else 2 + w[i] % reps
        }
        val after = IntArray(w.size) { i ->
            val r = (w[i] * 17 + i * 5 + w.size) % 9
            if (r < 6) 0 else 1 + (w[i] + i) % (tags.afters.size - 1)
        }
        return op to after
    }

    @Test
    fun normsAsPython() {
        for ((_, input, out) in cases("norm")) {
            assertEquals(input, out, String(VoicePolishTags.norm(input), 0, VoicePolishTags.norm(input).size))
        }
    }

    @Test
    fun appliesAsPython() {
        for (c in cases("apply")) {
            assertEquals(c[1], c[4], tags.apply(codePoints(c[1]), indices(c[2]), indices(c[3])))
        }
    }

    @Test
    fun englishLettersAreKeptAndAFullStopGetsNoMarkAfter() {
        // the tagger on a OnePlus 6T (2026-11-06): 「I'm sorry.」 labelled del on the second r and 。
        // after the full stop, typed 「I'm sory」 once the end's marks went
        val text = VoicePolishTags.norm("I'm sorry.")
        val op = IntArray(text.size)
        op[6] = VoicePolishTags.DEL
        op[0] = tags.ops.indexOf("的")
        val after = IntArray(text.size)
        after[text.size - 1] = tags.afters.indexOf("。")
        val (gop, gafter) = tags.guarded(text, op, after)
        // no spaces passed: the letters and marks only
        assertEquals("I'msorry.", tags.apply(text, gop, gafter))
        // a Chinese character's labels still apply, a digit's too (「2个」 to 「两个」)
        val zh = VoicePolishTags.norm("嗯2个")
        val (zop, zafter) = tags.guarded(zh, intArrayOf(VoicePolishTags.DEL, tags.ops.indexOf("两"), 0), intArrayOf(0, 0, tags.afters.indexOf("。")))
        assertEquals("两个。", tags.apply(zh, zop, zafter))
    }

    @Test
    fun freezesAsFusionExp() {
        for (c in cases("stream")) {
            val text = codePoints(c[1])
            val step = c[2].toInt()
            val stream = VoicePolishStream(tags, ::fake, freezeAfter = c[3].toInt(), window = c[4].toInt())
            var p = step
            while (p < text.size) {
                stream.update(String(text, 0, p))
                p += step
            }
            assertEquals("${c[1]} step $step K ${c[3]} W ${c[4]}", c[5], stream.finish(c[1]))
        }
    }

    @Test
    fun showsTheFrozenPrefixPolishedAndTheRestAsHeard() {
        val calls = ArrayList<String>()
        val keepAll = VoicePolishStream.Tagger { w ->
            calls += String(w, 0, w.size)
            IntArray(w.size) to IntArray(w.size) { 1 } // keep, a comma after each
        }
        val stream = VoicePolishStream(tags, keepAll, freezeAfter = 2, window = 4)
        assertEquals(VoicePolishStream.Shown("", "一"), stream.update("一"))
        assertEquals(VoicePolishStream.Shown("一，", "二三"), stream.update("一二 三"))
        assertEquals(VoicePolishStream.Shown("一，二，", "三四"), stream.update("一二三四"))
        // tagged again only from what changed, on the last four at most
        assertEquals(listOf("一", "一二三", "一二三四"), calls)
        assertEquals("一，二，三，四，", stream.finish("一二三四"))
    }

    @Test
    fun aRevisedCharacterIsTaggedAgainFrozenOrNot() {
        var mark = 1
        val tagger = VoicePolishStream.Tagger { w -> IntArray(w.size) to IntArray(w.size) { mark } }
        val stream = VoicePolishStream(tags, tagger, freezeAfter = 1, window = 8)
        assertEquals(VoicePolishStream.Shown("我，想，", "去"), stream.update("我想去"))
        mark = 2 // 。
        // the recogniser wrote 想 again as 要: from there on tagged again, the frozen 想 too
        assertEquals(VoicePolishStream.Shown("我，要。", "去"), stream.update("我要去"))
        assertEquals("我，要。去。", stream.finish("我要去"))
    }

    @Test
    fun aShorterTextDropsWhatWentAndNormedMarksAreOne() {
        val tagger = VoicePolishStream.Tagger { w -> IntArray(w.size) to IntArray(w.size) { 1 } }
        val stream = VoicePolishStream(tags, tagger, freezeAfter = 1, window = 8)
        assertEquals(VoicePolishStream.Shown("我，想，", "去"), stream.update("我想去"))
        // the frozen 我 stays as it was
        assertEquals(VoicePolishStream.Shown("我，", ""), stream.update("我"))
        // . then ...: one 「…」 in place of the 「.」, tagged again; the frozen 好 keeps its labels
        assertEquals(VoicePolishStream.Shown("好，", "."), stream.update("好."))
        assertEquals(VoicePolishStream.Shown("好，", "…"), stream.update("好..."))
        assertEquals("好……，", stream.finish("好..."))
    }

    @Test
    fun labelsAndTheirListsAreChecked() {
        assertThrows(IllegalArgumentException::class.java) { VoicePolishTags(listOf("del", "keep"), listOf("")) }
        assertThrows(IllegalArgumentException::class.java) { VoicePolishTags(listOf("keep", "del"), listOf("，")) }
        assertThrows(IllegalArgumentException::class.java) { tags.apply(intArrayOf(1, 2), intArrayOf(0), intArrayOf(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { VoicePolishStream(tags, ::fake, freezeAfter = 4, window = 4) }
        val short = VoicePolishStream(tags, { IntArray(0) to IntArray(0) })
        assertThrows(IllegalStateException::class.java) { short.finish("好") }
        assertThrows(IllegalArgumentException::class.java) { VoicePolishVocab.best(FloatArray(5), n = 2, width = 3) }
        // ties: the first; a word of two code points is no character's
        assertArrayEquals(intArrayOf(1), VoicePolishVocab.best(floatArrayOf(0f, 2f, 2f), n = 1, width = 3))
        val vocab = VoicePolishVocab(listOf("[UNK]", "好吗", "好"), cls = 3, sep = 4, unk = 0)
        assertArrayEquals(longArrayOf(3, 2, 0, 4), vocab.encode(codePoints("好吗")))
    }

    @Test
    fun aJumpLongerThanAStepIsTaggedInSteps() {
        val lengths = ArrayList<Int>()
        val tagger = VoicePolishStream.Tagger { w -> lengths += w.size; IntArray(w.size) to IntArray(w.size) }
        val stream = VoicePolishStream(tags, tagger, freezeAfter = 2, window = 4)
        // ten characters at once: steps of window - freezeAfter, so none leaves the window untagged
        assertEquals("零一二三四五六七八九", stream.finish("零一二三四五六七八九"))
        assertEquals(listOf(2, 4, 4, 4, 4), lengths)
    }

    @Test
    fun nothingSaidIsNothing() {
        val stream = VoicePolishStream(tags, { error("not tagged") })
        assertEquals(VoicePolishStream.Shown("", ""), stream.update(" "))
        assertEquals("", stream.finish(""))
    }

    @Test
    fun vocabularyEncodesCodePointsBetweenClsAndSep() {
        val vocab = VoicePolishVocab(listOf("[PAD]", "[UNK]", "好", "😀"), cls = 4, sep = 5, unk = 1)
        assertArrayEquals(longArrayOf(4, 2, 3, 1, 5), vocab.encode(codePoints("好😀吗")))
        assertArrayEquals(intArrayOf(2, 0), VoicePolishVocab.best(floatArrayOf(0f, 1f, 3f, 5f, -1f, 2f), n = 2, width = 3))
    }

    @Test
    fun theSpaceBetweenTwoEnglishWordsIsKeptPolished() {
        val raw = "用 Google Maps 和 3 D 打印"
        val normed = VoicePolishTags.norm(raw)
        val spaced = VoicePolishTags.spaces(raw, normed)
        // before M and D only: a space beside a Chinese character is not one between words
        assertEquals(listOf("M", "D"), normed.indices.filter { spaced[it] }.map { String(Character.toChars(normed[it])) })
        val keep = IntArray(normed.size)
        assertEquals("用Google Maps和3 D打印", tags.apply(normed, keep, keep, spaced))
        // a word deleted before: no space at the start
        val op = IntArray(normed.size).also { for (i in 1..6) it[i] = VoicePolishTags.DEL }
        assertEquals("用Maps和3 D打印", tags.apply(normed, op, keep, spaced))
    }
}
