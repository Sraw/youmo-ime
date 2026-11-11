/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

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
        VoiceHomophones.of(words.toList(), ::readings) { needed -> chars.mapValues { (_, r) -> r.filter { it in needed }.toIntArray() }.filterValues { it.isNotEmpty() } }

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
        now { h.replace(text) { spans -> BooleanArray(spans.size) { VoiceHomophones.allowed(spans[it], dictionary) } } }

    private fun learned(text: String, count: Float) = UserWord(text, "", LEARNED, count)

    private fun added(text: String) = UserWord(text, "", ADDED)

    @Test
    fun `a learned word typed more than three times, or one added, is put in for what reads as it`() {
        assertEquals("找一下徐婧，", replaced(homophones(learned("徐婧", 4f)), "找一下徐静，"))
        assertEquals("找一下徐婧，", replaced(homophones(added("徐婧")), "找一下徐静，"))
        assertEquals("找一下徐静，", replaced(homophones(learned("徐婧", 3f)), "找一下徐静，"))
    }

    @Test
    fun `the tones must match too`() {
        // 许 is xu3, 徐婧 xu2
        assertEquals("许静来了", replaced(homophones(added("徐婧")), "许静来了"))
    }

    @Test
    fun `a word the user has themselves is never replaced`() {
        // they typed 徐静 once too: theirs, however often
        assertEquals("徐静来了", replaced(homophones(added("徐婧"), learned("徐静", 1f)), "徐静来了"))
        // nor one inside a longer word of theirs
        assertEquals("徐静静", replaced(homophones(added("徐婧"), learned("徐静静", 1f)), "徐静静"))
    }

    @Test
    fun `a common word of the dictionary is left alone`() {
        assertEquals("这个公式", replaced(homophones(added("攻势")), "这个公式"))
    }

    @Test
    fun `nothing inside a longer word of the dictionary, nor across a common one's edge`() {
        assertEquals("徐静雨来了", replaced(homophones(added("徐婧")), "徐静雨来了"))
        assertEquals("和瑞典", replaced(homophones(added("何芮")), "和瑞典"))
        // 和瑞 alone: no word crosses it
        assertEquals("何芮来了", replaced(homophones(added("何芮")), "和瑞来了"))
    }

    @Test
    fun `a dictionary word read otherwise is left alone`() {
        val dictionary = FakeDictionary(words = setOf("徐静"), common = emptySet(), read = mapOf("徐静" to intArrayOf(reading(xu, 2), reading(jing, 3))))
        assertEquals("徐静", replaced(homophones(added("徐婧")), "徐静", dictionary))
        val same = FakeDictionary(words = setOf("徐静"), common = emptySet(), read = mapOf("徐静" to intArrayOf(reading(xu, 2), reading(jing, 0))))
        assertEquals("徐婧", replaced(homophones(added("徐婧")), "徐静", same))
    }

    @Test
    fun `a character of several readings matches by any`() {
        // 长 chang2 or zhang3: the user's 掌掌 zhang3 zhang3
        assertEquals("掌掌", replaced(homophones(added("掌掌")), "长长"))
    }

    @Test
    fun `of words read alike, the added first, then the most typed`() {
        assertEquals("徐婧", replaced(homophones(learned("徐靖", 9f), added("徐婧")), "徐静"))
        // learned come most typed first (Engines.userWords)
        assertEquals("徐靖", replaced(homophones(learned("徐靖", 9f), learned("徐婧", 5f)), "徐静"))
    }

    @Test
    fun `no single characters, nor blocked words, take part`() {
        assertSame(VoiceHomophones.NONE, homophones(added("婧"), UserWord("徐婧", "", BLOCKED)))
        assertTrue(VoiceHomophones.NONE.candidates("徐静").isEmpty())
    }

    @Test
    fun `the engine not answering leaves the text as heard, and a heard text keeps its length`() {
        val h = homophones(added("徐婧"))
        assertEquals("徐静", now { h.replace("徐静") { null } })
        val heard = now { h.replace(VoiceLive.Heard("徐静，你好", 3)) { BooleanArray(it.size) { true } } }
        assertEquals("徐婧，你好", heard.whole)
        assertEquals(3, heard.settled)
    }

    // the fakes never suspend: it runs to its end at once
    private fun <T> now(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return outcome!!.getOrThrow()
    }
}
