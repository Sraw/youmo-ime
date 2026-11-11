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
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

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

    private fun replaced(h: VoiceHomophones, text: String, dictionary: VoiceHomophones.Dictionary = FakeDictionary()): String =
        now { h.hold().replace(text) { spans -> BooleanArray(spans.size) { VoiceHomophones.allowed(spans[it], dictionary) } } }

    private fun replaced(hold: VoiceHomophones.Hold, text: String, dictionary: VoiceHomophones.Dictionary = FakeDictionary()): String =
        now { hold.replace(text) { spans -> BooleanArray(spans.size) { VoiceHomophones.allowed(spans[it], dictionary) } } }

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
            row("a common word of the dictionary is left alone", listOf(added("攻势")), "这个公式", "这个公式"),
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
        )
    }

    @Test
    fun `no single characters, nor blocked words, take part`() {
        assertSame(VoiceHomophones.NONE, homophones(added("婧"), UserWord("徐婧", "", BLOCKED)))
        assertTrue(VoiceHomophones.NONE.candidates("徐静").isEmpty())
    }

    @Test
    fun `the engine not answering leaves the text as heard, and a heard text keeps its length`() {
        val h = homophones(added("徐婧"))
        assertEquals("徐静", now { h.hold().replace("徐静") { null } })
        val heard = now { h.hold().replace(VoiceLive.Heard("徐静，你好", 3)) { BooleanArray(it.size) { true } } }
        assertEquals("徐婧，你好", heard.whole)
        assertEquals(3, heard.settled)
    }

    @Test
    fun `what a hold put in stays put in, the engine silent after or the text split otherwise`() {
        val hold = homophones(added("徐婧"), added("何芮")).hold()
        var asked = 0
        assertEquals("找徐婧，", now { hold.replace("找徐静，") { asked++; BooleanArray(it.size) { true } } })
        // a timeout on the next step: what was put in stays, the new stretch stays as heard
        assertEquals("找徐婧，和瑞", now { hold.replace("找徐静，和瑞") { asked++; null } })
        // the raw text (the polish failed): no marks, the run another, 徐婧 still put in; silent now, not asked
        assertEquals("找徐婧和瑞", now { hold.replace("找徐静和瑞") { asked++; BooleanArray(it.size) { true } } })
        assertEquals(2, asked)
    }

    @Test
    fun `a stretch the engine said no to stays as heard, the same put in elsewhere`() {
        val hold = homophones(added("何芮")).hold()
        // 和瑞典: a common word over its edge; 和瑞 alone put in
        assertEquals("和瑞典，何芮", replaced(hold, "和瑞典，和瑞"))
        assertEquals("和瑞典，何芮。", replaced(hold, "和瑞典，和瑞。"))
    }

    @Test
    fun `a new place of a stretch put in is asked, not put in unasked`() {
        val hold = homophones(added("何芮"), added("徐婧")).hold()
        assertEquals("何芮来了，", replaced(hold, "和瑞来了，"))
        assertEquals("何芮来了，和瑞典", replaced(hold, "和瑞来了，和瑞典"))
        assertEquals("找徐婧，", replaced(hold, "找徐静，"))
        assertEquals("找徐婧，徐静雨来了", replaced(hold, "找徐静，徐静雨来了"))
    }

    @Test
    fun `a stretch put in where it was typed already stays, though the engine says no as the text grows`() {
        val hold = homophones(added("何芮")).hold()
        val yes = VoiceHomophones.Lexicon { spans -> BooleanArray(spans.size) { VoiceHomophones.allowed(spans[it], FakeDictionary()) } }
        // 何 typed already (settled 3), then 典 makes 和瑞典 a common word over its edge: 何芮 stays,
        // or tap to talk would type it all again
        assertEquals("好，何芮", now { hold.replace(VoiceLive.Heard("好，和瑞", 3), yes) }.whole)
        assertEquals("好，何芮典", now { hold.replace(VoiceLive.Heard("好，和瑞典", 3), yes) }.whole)
        // not typed yet: the engine's no stands
        val fresh = homophones(added("何芮")).hold()
        assertEquals("好，何芮", now { fresh.replace(VoiceLive.Heard("好，和瑞", 2), yes) }.whole)
        assertEquals("好，和瑞典", now { fresh.replace(VoiceLive.Heard("好，和瑞典", 2), yes) }.whole)
    }

    @Test
    fun `a character typed already stays put in when the recognizer rewrites the rest of its stretch`() {
        val hold = homophones(added("何芮")).hold()
        val no = VoiceHomophones.Lexicon { spans -> BooleanArray(spans.size) { false } }
        val yes = VoiceHomophones.Lexicon { spans -> BooleanArray(spans.size) { true } }
        // 何 typed (settled 3); the tail heard again as 平: no stretch of the word left, 何 stays typed
        assertEquals("好，何芮", now { hold.replace(VoiceLive.Heard("好，和瑞", 3), yes) }.whole)
        assertEquals("好，何平", now { hold.replace(VoiceLive.Heard("好，和平", 3), no) }.whole)
    }

    @Test
    fun `a new utterance does not keep the last one's typed stretches`() {
        val hold = homophones(added("何芮")).hold()
        val yes = VoiceHomophones.Lexicon { spans -> BooleanArray(spans.size) { true } }
        val no = VoiceHomophones.Lexicon { spans -> BooleanArray(spans.size) { false } }
        assertEquals("好，何芮", now { hold.replace(VoiceLive.Heard("好，和瑞", 3), yes) }.whole)
        // the next utterance has 和瑞 at the same place and the engine says no: left as heard
        assertEquals("好，和瑞典", now { hold.replace(VoiceLive.Heard("好，和瑞典", 3, utterance = 1), no) }.whole)
    }

    @Test
    fun `a stretch is asked again only as what the dictionary reads around it changes`() {
        val hold = homophones(added("徐婧")).hold()
        var asked = 0
        val text = "徐静" + "来".repeat(11)
        // the run grows past the words that could cross 徐静: not asked again
        // a cache that skipped asking but put in the wrong word would pass a count alone
        assertEquals("徐婧" + "来".repeat(11), now { hold.replace(text) { asked++; BooleanArray(it.size) { true } } })
        assertEquals("徐婧" + "来".repeat(14), now { hold.replace(text + "来来来") { asked++; BooleanArray(it.size) { true } } })
        assertEquals(1, asked)
    }

    // the fakes never suspend: it runs to its end at once
    private fun <T> now(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return outcome!!.getOrThrow()
    }
}
