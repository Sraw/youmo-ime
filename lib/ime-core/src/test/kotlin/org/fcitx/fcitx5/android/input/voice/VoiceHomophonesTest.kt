/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.runtime.UserWord.Kind.ADDED
import org.fcitx.fcitx5.android.engine.runtime.UserWord.Kind.BLOCKED
import org.fcitx.fcitx5.android.engine.runtime.UserWord.Kind.LEARNED
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones.Companion.reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHomophonesTest {

    // syllables by made-up ids, a character's readings as a dictionary would have them
    private val xu = 1
    private val jing = 2
    private val gong = 3
    private val shi = 4
    private val yu = 5
    private val zhang = 6
    private val chang = 7
    private val he = 8
    private val rui = 9
    private val dian = 10

    private val chars: Map<Char, IntArray> = mapOf(
        '徐' to intArrayOf(reading(xu, 2)),
        '许' to intArrayOf(reading(xu, 3)),
        '静' to intArrayOf(reading(jing, 4)),
        '婧' to intArrayOf(reading(jing, 4)),
        '靖' to intArrayOf(reading(jing, 4)),
        '公' to intArrayOf(reading(gong, 1)),
        '攻' to intArrayOf(reading(gong, 1)),
        '式' to intArrayOf(reading(shi, 4)),
        '势' to intArrayOf(reading(shi, 4)),
        '雨' to intArrayOf(reading(yu, 3)),
        '长' to intArrayOf(reading(chang, 2), reading(zhang, 3)),
        '掌' to intArrayOf(reading(zhang, 3)),
        '和' to intArrayOf(reading(he, 2), reading(he, 4)),
        '何' to intArrayOf(reading(he, 2)),
        '瑞' to intArrayOf(reading(rui, 4)),
        '芮' to intArrayOf(reading(rui, 4)),
        '典' to intArrayOf(reading(dian, 3)),
    )

    // the user's word as typed: its characters' readings of its syllables, one tone each here
    private fun readings(w: UserWord): List<IntArray> = w.text.map { c -> chars.getValue(c).take(1).toIntArray() }

    private fun homophones(vararg words: UserWord) =
        VoiceHomophones.of(words.toList(), ::readings) { chars[it] }

    private open class FakeDictionary(
        val words: Set<String> = setOf("公式", "攻势", "徐静雨", "瑞典"),
        val common: Set<String> = setOf("公式", "瑞典"),
        val read: Map<String, IntArray> = emptyMap(),
    ) : VoiceHomophones.Dictionary {
        override fun isWord(text: String) = text in words
        override fun isCommon(text: String) = text in common
        override fun wordReading(text: String) = read[text]
    }

    // the stretches the dictionary allows, scored by [logProbs]: by default the model has no say, so each bonus puts its word in
    private fun replaced(
        h: VoiceHomophones,
        text: String,
        dictionary: VoiceHomophones.Dictionary = FakeDictionary(),
        blocked: List<String> = emptyList(),
        logProbs: (List<String>) -> FloatArray = { FloatArray(it.size) },
    ): String = h.pick(text, h.candidates(text).filter { VoiceHomophones.allowed(it, dictionary) }, blocked, logProbs)

    private fun learned(text: String, count: Float) = UserWord(text, "", LEARNED, count)

    private fun added(text: String) = UserWord(text, "", ADDED)

    // what one text becomes with the user's words, the dictionary FakeDictionary's unless given: a row each
    private fun row(why: String, words: List<UserWord>, heard: String, out: String, dictionary: VoiceHomophones.Dictionary = FakeDictionary()) =
        why to { assertEquals(why, out, replaced(homophones(*words.toTypedArray()), heard, dictionary)) }

    @Test
    fun `what reads as a word of the user's becomes it, but where the dictionary or their own words say otherwise`() {
        val otherwise = FakeDictionary(words = setOf("徐静"), common = emptySet(), read = mapOf("徐静" to intArrayOf(reading(xu, 2), reading(jing, 3))))
        val sameToneless = FakeDictionary(words = setOf("徐静"), common = emptySet(), read = mapOf("徐静" to intArrayOf(reading(xu, 2), reading(jing, 0))))
        val h = listOf(added("徐婧"), added("何芮"), added("婧攻"))
        assertRows(
            row("a learned word typed more than three times is put in", listOf(learned("徐婧", 4f)), "找一下徐静，", "找一下徐婧，"),
            row("an added word is put in at once", listOf(added("徐婧")), "找一下徐静，", "找一下徐婧，"),
            row("a learned word typed three times takes no part", listOf(learned("徐婧", 3f)), "找一下徐静，", "找一下徐静，"),
            row("the tones must match too: 许 is xu3, 徐婧 xu2", listOf(added("徐婧")), "许静来了", "许静来了"),
            row("a word the user typed themselves, however seldom, is never replaced", listOf(added("徐婧"), learned("徐静", 1f)), "徐静来了", "徐静来了"),
            row("nor one inside a longer word of theirs", listOf(added("徐婧"), learned("徐静静", 1f)), "徐静静", "徐静静"),
            row("an added word is put in over a common word too: the user's say", listOf(added("攻势")), "这个公式", "这个攻势"),
            row("and a learned one, typed more than three times", listOf(learned("攻势", 4f)), "这个公式", "这个攻势"),
            row("nothing inside a longer word of the dictionary", listOf(added("徐婧")), "徐静雨来了", "徐静雨来了"),
            row("nothing across a common word's edge", listOf(added("何芮")), "和瑞典", "和瑞典"),
            row("no word crossing it: put in", listOf(added("何芮")), "和瑞来了", "何芮来了"),
            row("a dictionary word read in other tones is left alone", listOf(added("徐婧")), "徐静", "徐静", otherwise),
            row("a dictionary word whose tone is not known reads as any", listOf(added("徐婧")), "徐静", "徐婧", sameToneless),
            row("a character of several readings matches by any: 长 chang2 or zhang3", listOf(added("掌掌")), "长长", "掌掌"),
            row("of words read alike, the added first", listOf(learned("徐靖", 9f), added("徐婧")), "徐静", "徐婧"),
            // given least typed first: the order is VoiceHomophones.of's own, not its caller's
            row("of learned words read alike, the most typed first", listOf(learned("徐婧", 5f), learned("徐靖", 9f)), "徐静", "徐靖"),
            row("several in one segment", h, "徐静和和瑞", "徐婧和何芮"),
            row("of overlapping ones (徐静, 静公) the first from the start", h, "徐静公", "徐婧公"),
            row("next to digits and letters", h, "3徐静OK", "3徐婧OK"),
            row("a single character is no stretch", h, "a徐", "a徐"),
            row("a word a table remembered is never put in: its code is no reading", listOf(learned("徐婧", 9f).copy(table = "wbx")), "徐静", "徐静"),
            row("yet it is the user's own word, never replaced", listOf(added("徐婧"), learned("徐静", 9f).copy(table = "wbx")), "徐静来了", "徐静来了"),
        )
    }

    @Test
    fun `a word added to the words read is what reading them all again with it would be`() {
        val ws = listOf(added("何芮"), learned("徐靖", 9f), learned("公式", 1f))
        val texts = listOf("徐静", "找一下徐静，和瑞", "徐靖来了", "徐婧来了", "公式", "许静")
        // as Engines' settings read it: none for a character its pinyin has no reading of
        val unread = listOf(intArrayOf(reading(xu, 2)), intArrayOf())
        fun same(why: String, base: List<UserWord>, readsAs: List<IntArray>? = null) = why to {
            val w = added("徐婧")
            val read = { u: UserWord -> if (readsAs != null && u === w) readsAs else readings(u) }
            val whole = VoiceHomophones.of(base + w, read) { chars[it] }
            val quick = VoiceHomophones.of(base, read) { chars[it] }.withAdded(w.text, read(w))
            assertEquals(why, texts.map { replaced(whole, it) }, texts.map { replaced(quick, it) })
        }
        assertRows(
            same("a new word: after those added, before the learned", ws),
            same("a learned word added: moved to the added, ahead of 徐靖", ws + learned("徐婧", 5f)),
            same("a word without a reading for each character: one of theirs, never put in", ws, unread),
            same("the same, learned before: no longer put in for its learning", ws + learned("徐婧", 5f), unread),
        )
    }

    @Test
    fun `no single characters, nor blocked words, take part`() {
        assertSame(VoiceHomophones.NONE, homophones(added("婧"), UserWord("徐婧", "", BLOCKED)))
        assertTrue(VoiceHomophones.NONE.candidates("徐静").isEmpty())
    }

    // log10 P as the model would give it: a text's own, else [default]
    private fun model(vararg scores: Pair<String, Float>, default: Float = 0f): (List<String>) -> FloatArray {
        val of = scores.toMap()
        return { texts -> FloatArray(texts.size) { of[texts[it]] ?: default } }
    }

    @Test
    fun `the word model decides, against a bonus by where the word came from`() {
        fun row(why: String, word: UserWord, sub: Float, out: String) = why to {
            assertEquals(why, out, replaced(homophones(word), "这个公式", logProbs = model("这个公式" to -10f, "这个攻势" to sub)))
        }
        assertRows(
            row("added: 1.4 less likely, within its 1.5: put in", added("攻势"), -11.4f, "这个攻势"),
            row("added: 1.6 less likely: left as heard", added("攻势"), -11.6f, "这个公式"),
            row("added: exactly 1.5 less likely is no gain: left", added("攻势"), -11.5f, "这个公式"),
            row("learned (typed more than 3 times): 1.2 less likely is past its 1", learned("攻势", 4f), -11.2f, "这个公式"),
            row("learned: 0.8 less likely, within its 1: put in", learned("攻势", 4f), -10.8f, "这个攻势"),
            row("always put in: the model's doubt does not count", added("攻势").copy(voiceAlways = true), -99f, "这个攻势"),
            row("more likely as the user's word: put in", learned("攻势", 4f), -9f, "这个攻势"),
        )
    }

    @Test
    fun `of two stretches only the ones the model takes go in`() {
        val h = homophones(added("徐婧"), added("何芮"))
        // 何芮 is 5 less likely wherever it goes: 徐婧 alone, not both nor neither
        val doubtsHeRui = { texts: List<String> -> FloatArray(texts.size) { if ("何芮" in texts[it]) -5f else 0f } }
        assertRows(
            "both in when the model has no say" to { assertEquals("徐婧和何芮", replaced(h, "徐静和和瑞")) },
            "the one it doubts left out, the other in" to { assertEquals("徐婧和和瑞", replaced(h, "徐静和和瑞", logProbs = doubtsHeRui)) },
        )
    }

    @Test
    fun `none goes in that makes a blocked word`() {
        val h = homophones(added("徐婧"))
        assertRows(
            "婧来 blocked: 徐婧 before 来 would make it" to { assertEquals("徐静来了", replaced(h, "徐静来了", blocked = listOf("婧来"))) },
            "a blocked word apart from it stops nothing" to { assertEquals("徐婧来了，来了", replaced(h, "徐静来了，来了", blocked = listOf("来了"))) },
            "an always word neither" to {
                val always = homophones(added("徐婧").copy(voiceAlways = true))
                assertEquals("徐静来了", replaced(always, "徐静来了", blocked = listOf("婧来")))
            },
        )
    }

    @Test
    fun `always words go in unasked, the others scored with them in`() {
        val h = homophones(added("徐婧").copy(voiceAlways = true), added("何芮"))
        val scored = ArrayList<List<String>>()
        val asked = { texts: List<String> -> scored += texts; FloatArray(texts.size) }
        assertEquals("找徐婧", replaced(h, "找徐静", logProbs = asked))
        assertTrue("only always words: the model not asked", scored.isEmpty())
        assertEquals("徐婧和何芮", replaced(h, "徐静和和瑞", logProbs = asked))
        assertEquals(listOf(listOf("徐婧和和瑞", "徐婧和何芮")), scored)
    }

    @Test
    fun `a sentence of many stretches is scored a bounded number of ways`() {
        val h = homophones(added("徐婧"), added("婧攻"), added("何芮"))
        val scored = ArrayList<Int>()
        val count = { texts: List<String> -> scored += texts.size; FloatArray(texts.size) { i -> if (i == 0) 0f else -9f } }
        assertRows(
            "20 apart: the first 16 alone, no pairs" to {
                val text = List(20) { "徐静" }.joinToString("，")
                assertEquals(text, replaced(h, text, logProbs = count))
                assertEquals(VoiceHomophones.MAX_CHOICES + 1, scored.last())
            },
            "20 apart, the model liking 徐婧 everywhere, the earlier the better: one place goes in, the 17th to 20th never tried" to {
                val text = List(20) { "徐静" }.joinToString("，")
                val tried = ArrayList<String>()
                val likes = { texts: List<String> -> tried += texts; FloatArray(texts.size) { i -> texts[i].split("，").withIndex().sumOf { (at, w) -> if (w == "徐婧") 40.0 - at else 0.0 }.toFloat() } }
                assertEquals(listOf("徐婧") + List(19) { "徐静" }, replaced(h, text, logProbs = likes).split("，"))
                for (place in 16 until 20) assertTrue("place ${place + 1} tried", tried.none { it.split("，")[place] == "徐婧" })
            },
            "3 apart: 3 alone, 3 pairs, all three" to {
                assertEquals("徐静，和瑞，徐静", replaced(h, "徐静，和瑞，徐静", logProbs = count))
                assertEquals(1 + 3 + 3 + 1, scored.last())
            },
            "overlapping (徐静, 静公) never together" to {
                assertEquals("徐静公", replaced(h, "徐静公", logProbs = count))
                assertEquals(1 + 2, scored.last())
            },
        )
    }

    @Test
    fun `a word added to the words read takes the bonus asked for`() {
        val h = homophones(added("何芮"))
        val doubts = model("徐静来了" to 0f, "徐婧来了" to -3f)
        val ws = h.withAdded("徐婧", readings(added("徐婧")))
        assertRows(
            "added: 3 less likely is past 1.5" to { assertEquals("徐静来了", replaced(ws, "徐静来了", logProbs = doubts)) },
            "always: in" to { assertEquals("徐婧来了", replaced(h.withAdded("徐婧", readings(added("徐婧")), VoiceHomophones.ALWAYS), "徐静来了", logProbs = doubts)) },
            "made always over one added: in" to { assertEquals("徐婧来了", replaced(ws.withAdded("徐婧", readings(added("徐婧")), VoiceHomophones.ALWAYS), "徐静来了", logProbs = doubts)) },
        )
    }
}
