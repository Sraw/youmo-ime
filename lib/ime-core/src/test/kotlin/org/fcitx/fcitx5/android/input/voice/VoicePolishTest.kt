/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolishTest {

    // contract: a fixture written by polish_golden.py (resources/voice/polish-golden.tsv), which mutation cannot see.
    // resources/voice/polish_golden.py: voice-eval's tags.py and fusion_exp.py's freeze rule on fixed texts
    private val golden = javaClass.getResourceAsStream("/voice/polish-golden.tsv")!!.bufferedReader().readLines()
        .filter { it.isNotEmpty() }.map { it.split('\t') }

    private val tags = VoicePolishTags(golden.first { it[0] == "ops" }.drop(1), golden.first { it[0] == "afters" }.drop(1), listOf("none"))

    private fun cases(kind: String) = golden.filter { it[0] == kind }.also { assertTrue("$kind cases", it.isNotEmpty()) }

    private fun codePoints(s: String) = s.codePoints().toArray()

    private fun indices(s: String) = if (s.isEmpty()) IntArray(0) else s.split(' ').map { it.toInt() }.toIntArray()

    // polish_golden.py's fake(): labels from the character, where it is in the window and the window's length
    private fun fake(w: IntArray): VoicePolishStream.Labels {
        val reps = tags.ops.size - 2
        val op = IntArray(w.size) { i ->
            val r = (w[i] * 31 + i * 7 + w.size * 3) % 11
            if (r < 7) 0 else if (r == 7) 1 else 2 + w[i] % reps
        }
        val after = IntArray(w.size) { i ->
            val r = (w[i] * 17 + i * 5 + w.size) % 9
            if (r < 6) 0 else 1 + (w[i] + i) % (tags.afters.size - 1)
        }
        return VoicePolishStream.Labels(op, after)
    }

    @Test
    fun normsAsPython() {
        for ((_, input, out) in cases("norm")) {
            assertEquals(input, out, String(VoicePolishTags.norm(input), 0, VoicePolishTags.norm(input).size))
        }
    }

    @Uat("VO-35")
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
        tags.guarded(text, op, after)
        // no spaces passed: the letters and marks only (guarded changed the labels in place)
        assertEquals("I'msorry.", tags.apply(text, op, after))
        // a Chinese character's labels still apply, a digit's too (「2个」 to 「两个」)
        val zh = VoicePolishTags.norm("嗯2个")
        val zop = intArrayOf(VoicePolishTags.DEL, tags.ops.indexOf("两"), 0)
        val zafter = intArrayOf(0, 0, tags.afters.indexOf("。"))
        tags.guarded(zh, zop, zafter)
        assertEquals("两个。", tags.apply(zh, zop, zafter))
        // the stream guards what the model says: 「I'm sorry.」 polished as it was said
        val stream = VoicePolishStream(tags, { t ->
            VoicePolishStream.Labels(IntArray(t.size) { if (t[it] == 'r'.code) VoicePolishTags.DEL else 0 }, IntArray(t.size) { if (t[it] == '.'.code) tags.afters.indexOf("。") else 0 })
        })
        assertEquals("I'm sorry.", stream.finish("I'm sorry."))
        val bare = VoicePolishStream(tags, { t ->
            VoicePolishStream.Labels(IntArray(t.size) { if (t[it] == 'r'.code) VoicePolishTags.DEL else 0 }, IntArray(t.size))
        }, guard = false)
        assertEquals("I'm soy.", bare.finish("I'm sorry."))
    }

    @Test
    fun freezesAsFusionExp() {
        for (c in cases("stream")) {
            val text = codePoints(c[1])
            val step = c[2].toInt()
            val stream = VoicePolishStream(tags, ::fake, freezeAfter = c[3].toInt(), window = c[4].toInt(), guard = false)
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
            VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size) { 1 }) // keep, a comma after each
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
        val tagger = VoicePolishStream.Tagger { w -> VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size) { mark }) }
        val stream = VoicePolishStream(tags, tagger, freezeAfter = 1, window = 8)
        assertEquals(VoicePolishStream.Shown("我，想，", "去"), stream.update("我想去"))
        mark = 2 // 。
        // the recogniser wrote 想 again as 要: from there on tagged again, the frozen 想 too
        assertEquals(VoicePolishStream.Shown("我，要。", "去"), stream.update("我要去"))
        assertEquals("我，要。去。", stream.finish("我要去"))
    }

    @Test
    fun aShorterTextDropsWhatWentAndNormedMarksAreOne() {
        val tagger = VoicePolishStream.Tagger { w -> VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size) { 1 }) }
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
        assertThrows(IllegalArgumentException::class.java) { VoicePolishTags(listOf("del", "keep"), listOf(""), listOf("none")) }
        assertThrows(IllegalArgumentException::class.java) { VoicePolishTags(listOf("keep", "del"), listOf("，"), listOf("none")) }
        assertThrows(IllegalArgumentException::class.java) { tags.apply(intArrayOf(1, 2), intArrayOf(0), intArrayOf(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { VoicePolishStream(tags, ::fake, freezeAfter = 4, window = 4) }
        val short = VoicePolishStream(tags, { VoicePolishStream.Labels(IntArray(0), IntArray(0)) })
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
        val tagger = VoicePolishStream.Tagger { w -> lengths += w.size; VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size)) }
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

    // the number head (060-voice-tagger.md 数字头): num classes as N.json has them
    private val numTags = VoicePolishTags(
        listOf("keep", "del"),
        listOf("", "，", "。"),
        listOf("none", "value", "digits", "date", "time", "money", "percent", "model", "ratio"),
    )

    private fun classes(s: String) = s.map { if (it == '.') 0 else it.digitToInt() }.toIntArray()

    @Test
    fun adjacentMarkedCharactersAreOneNumberOfTheirMostClass() {
        val text = codePoints("涨了百分之三点五共十二个")
        val none = IntArray(text.size)
        // 百分之三点五 percent but 五 value: most are percent
        assertEquals("涨了3.5%共12个", numTags.apply(text, none, none, num = classes("..666661.11.")))
        // a mark after a character ends the number there: two runs, 点五 no number
        val after = IntArray(text.size).also { it[5] = 1 }
        assertEquals("涨了3%，点五共12个", numTags.apply(text, none, after, num = classes("..666661.11.")))
        // none given: as the tagger without the head
        assertEquals("涨了百分之三点五共十二个", numTags.apply(text, none, none))
        assertThrows(IllegalArgumentException::class.java) { numTags.apply(text, none, none, num = IntArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { VoicePolishTags(listOf("keep", "del"), listOf(""), listOf("value")) }
    }

    @Test
    fun aDeletedCharacterIsInNoNumberAndLeavesItWhole() {
        // 「幺三八，幺二三四」: the recognizer's pause comma deleted inside a phone number
        val text = codePoints("幺三八，幺二三四")
        val op = IntArray(text.size).also { it[3] = VoicePolishTags.DEL }
        val none = IntArray(text.size)
        assertEquals("1381234", numTags.apply(text, op, none, num = classes("22202222")))
        // kept, the comma breaks the run though marked
        assertEquals("138，1234", numTags.apply(text, none, none, num = classes("22222222")))
    }

    @Test
    fun aNumbersCharacterIsNeverDeletedNorAnIdiomMarkedANumber() {
        // N-kd3b (060-voice-tagger.md): 「一点一点」 lost an 一 to a del, 「十全十美」 written 10全十美
        val slowly = codePoints("一点一点黑")
        val op = intArrayOf(VoicePolishTags.DEL, VoicePolishTags.DEL, 0, 0, VoicePolishTags.DEL)
        val none = IntArray(slowly.size)
        val after = none.copyOf()
        numTags.guarded(slowly, op, after)
        // the number's characters kept; any other still goes
        assertEquals("一点一点", numTags.apply(slowly, op, after))
        val perfect = codePoints("十全十美三个")
        val num = classes("1.1.1.")
        numTags.guarded(perfect, IntArray(perfect.size), IntArray(perfect.size), num)
        // the idiom no number, the 三 after it still one
        assertEquals("十全十美3个", numTags.apply(perfect, IntArray(perfect.size), IntArray(perfect.size), num = num))
        // a number's character replaced still is: only deleting one is undone (二个 written 两个)
        val two = codePoints("二个")
        val twoOp = intArrayOf(tags.ops.indexOf("两"), 0)
        tags.guarded(two, twoOp, IntArray(two.size))
        assertEquals("两个", tags.apply(two, twoOp, IntArray(two.size)))
        // the stream guards the model's numbers too
        val stream = VoicePolishStream(numTags, { w ->
            VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size), IntArray(w.size) { if (w[it] == '十'.code) 1 else 0 })
        })
        assertEquals("十全十美，10个", stream.finish("十全十美，十个"))
    }

    @Test
    fun anIdiomCutByAStreamWindowsStartIsStillNoNumber() {
        // a dictation longer than a window: the second step's window starts at 32, inside 十全十美 at 30..33,
        // and its tail tagged without its head was written 十全10美
        val before = "好".repeat(30)
        val after = "好".repeat(70)
        val stream = VoicePolishStream(numTags, { w ->
            VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size), IntArray(w.size) { if (w[it] == '十'.code) 1 else 0 })
        })
        assertEquals(before + "十全十美" + after, stream.finish(before + "十全十美" + after))
        // a number at the window's start, an idiom ending just before it, still is one: only an idiom
        // across the start unmarks (the 十 at 32 is first tagged in the second window, with 全十美 before it)
        val numbered = VoicePolishStream(numTags, { w ->
            VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size), IntArray(w.size) { if (w[it] == '十'.code && it + 1 < w.size && w[it + 1] == '个'.code) 1 else 0 })
        })
        val idiomBefore = "好".repeat(28) + "十全十美"
        assertEquals(idiomBefore + "10个" + after, numbered.finish(idiomBefore + "十个" + after))
    }

    @Test
    fun eachIdiomIsListedOnce() {
        val idioms = VoicePolishTags.IDIOMS
        assertEquals(idioms.groupBy { it }.filterValues { it.size > 1 }.keys.toString(), idioms.size, idioms.toSet().size)
    }

    @Test
    fun theStreamFreezesNumberClassesWithTheRest() {
        var kind = 3
        val tagger = VoicePolishStream.Tagger { w ->
            VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size), IntArray(w.size) { if (w[it] == '年'.code) 0 else kind })
        }
        val stream = VoicePolishStream(numTags, tagger, freezeAfter = 1, window = 8)
        assertEquals(VoicePolishStream.Shown("2026", "年"), stream.update("二零二六年"))
        // tagged again as no number: the frozen digits keep their date
        kind = 0
        assertEquals("2026年", stream.finish("二零二六年"))
        assertEquals("2026", stream.frozenPrefix(4))
    }

    @Test
    fun anEnglishWordIsNoPartOfANumber() {
        // the tagger marking 「Windows 11」's digits: they keep their space and stay as typed
        val raw = "Windows 11"
        val text = VoicePolishTags.norm(raw)
        val none = IntArray(text.size)
        val num = IntArray(text.size).also { it[7] = 7; it[8] = 7 }
        assertEquals("Windows 11", numTags.apply(text, none, none, VoicePolishTags.spaces(raw, text), num))
    }

    // what is typed as it is said is cut after VoiceLive.ENDS: each ends a number, so no cut is inside one
    @Test
    fun everyMarkATalkIsTypedUpToEndsANumber() {
        for (c in VoiceLive.ENDS) assertTrue("$c", "$c" in VoicePolishTags.NUMBER_BREAKS)
    }

    // review F5866
    @Test
    fun aNumberGoingOnPastTheFrozenEndShowsWholeInTheRest() {
        val tagger = VoicePolishStream.Tagger { w -> VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size), IntArray(w.size) { 3 }) }
        val stream = VoicePolishStream(numTags, tagger, freezeAfter = 1, window = 8)
        // 二零二 frozen, 六 not: not 「202」 then 「六」
        assertEquals(VoicePolishStream.Shown("", "二零二六"), stream.update("二零二六"))
        assertEquals("2026", stream.finish("二零二六"))
    }

    // a raw text kept whole, as the stream polishes it: spaces and marks' widths from the raw text
    private fun keptWhole(raw: String, t: VoicePolishTags = tags, num: IntArray? = null): String {
        val text = VoicePolishTags.norm(raw)
        val keep = IntArray(text.size)
        return t.apply(text, keep, keep, VoicePolishTags.spaces(raw, text), num, VoicePolishTags.asciiMarks(raw, text))
    }

    // review F5885/F6280/F6490 (English marks and their spaces), F6513 (a full width mark said in Chinese stays)
    @Test
    @Uat("VO-36")
    fun aMarkKeepsTheWidthTheRecognizerWroteItIn() = assertRows(
        "an English sentence's marks stay ASCII, the space after its comma kept (F5885)" to {
            assertEquals("Yes, I know?好的？", keptWhole("Yes, I know? 好的？"))
        },
        "ASCII English stays ASCII (F6513)" to { assertEquals("Hello, world", keptWhole("Hello, world")) },
        "a full width comma after an English word in Chinese stays full width (F6513)" to {
            assertEquals("我买了iPhone，很好用", keptWhole("我买了iPhone，很好用"))
        },
        "a full width ！ after an English word stays (F6513)" to { assertEquals("我喜欢Python！", keptWhole("我喜欢Python！")) },
        "after a number written from Chinese, the mark follows the number, not the word before it (F6513)" to {
            val num = IntArray(VoicePolishTags.norm("买了iPhone十五，").size).also { it[8] = 1; it[9] = 1 }
            assertEquals("买了iPhone15，", keptWhole("买了iPhone十五，", numTags, num))
        },
        "an ASCII comma after a number written from Chinese is the number's, full width: the word before it is not what it follows" to {
            val num = IntArray(VoicePolishTags.norm("买了iPhone十五,").size).also { it[8] = 1; it[9] = 1 }
            assertEquals("买了iPhone15，", keptWhole("买了iPhone十五,", numTags, num))
        },
        "after digits the full width comma stays (F6513)" to { assertEquals("Windows 11，", keptWhole("Windows 11，")) },
        "a non-breaking space is one to spaces as to norm (F6490)" to { assertEquals("Windows 11", keptWhole("Windows\u00a011")) },
    )

    // review F6280: nothing after 。 either (。。 would read as an ellipsis)
    @Test
    @Uat("VO-36")
    fun nothingIsWrittenAfterAFullStop() {
        val stop = VoicePolishTags.norm("好。")
        val after = intArrayOf(0, tags.afters.indexOf("。"))
        tags.guarded(stop, IntArray(stop.size), after)
        assertEquals(0, after[1])
    }

    // review F6272: the polished prefix shown is cached while its characters, spaces and marks' widths hold
    @Test
    fun theShownPrefixFollowsAChangeInsideIt() {
        fun stream() = VoicePolishStream(tags, { w -> VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size)) }, freezeAfter = 1, window = 8)
        assertRows(
            "a space coming between two frozen letters shows" to {
                val s = stream()
                assertEquals(VoicePolishStream.Shown("ab", "c"), s.update("abc"))
                assertEquals(VoicePolishStream.Shown("a b", "c"), s.update("a bc"))
                assertEquals("a bc", s.finish("a bc"))
            },
            "a frozen character revised shows revised" to {
                val s = stream()
                s.update("abc")
                assertEquals(VoicePolishStream.Shown("xb", "c"), s.update("xbc"))
            },
            "a frozen mark recognised again in ASCII shows ASCII" to {
                val s = stream()
                assertEquals(VoicePolishStream.Shown("Hi，", "a"), s.update("Hi，a"))
                assertEquals(VoicePolishStream.Shown("Hi,", " a"), s.update("Hi, a"))
            },
        )
    }
}
