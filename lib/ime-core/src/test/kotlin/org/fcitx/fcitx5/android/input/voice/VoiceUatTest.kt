/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * UAT cases of voice and the user's words (uat/12), as the deterministic chain: what a stream is
 * told to listen for and never type ([VoiceHotwords.ofUser]). Whether the model then hears the
 * word is acoustics: the voice-eval benchmark and VO-14's device smoke.
 */
class VoiceUatTest {

    private val lan = UserWord("澜境花园", "lan jing hua yuan", Kind.ADDED)

    private fun listened(vararg words: UserWord, habits: Boolean = true) = VoiceHotwords.ofUser(words.toList(), habits).hotwords.split('/').filter { it.isNotEmpty() }

    @Test
    @Uat("VO-14")
    fun aWordAddedByHandIsListenedForHardest() {
        assertEquals(listOf("澜 境 花 园 :${VoiceHotwords.ADDED_SCORE}"), listened(lan))
    }

    @Test
    @Uat("VO-17")
    fun onlyTheWordAddedIsListenedFor() {
        // the stream gets 澜境花园 alone: nothing for 今天天气很好 or 你好 to be bent towards
        val heard = listened(lan)
        assertEquals(1, heard.size)
        assertFalse(heard.toString(), heard.any { "天" in it || "你" in it })
    }

    @Test
    @Uat("VO-18")
    fun anImportedWordIsNotListenedForTillAddedByHand() {
        // an imported word is the import layer's, no user word: that Engines.userWords leaves it out is
        // EnginesImportTest's (its import of 汪晓明); here only what follows, no user word listened for
        // added by hand: listened for at the bonus that hears it
        assertEquals(listOf("澜 境 花 园 :${VoiceHotwords.ADDED_SCORE}"), listened(lan))
    }

    @Test
    @Uat("VO-20")
    fun aNameTypedTwiceIsListenedFor() {
        // typed twice by pinyin it is the user's: listened for, at the everyday bonus
        assertEquals(listOf("汪 小 明"), listened(UserWord("汪小明", "wang xiao ming", Kind.LEARNED, 2f)))
    }

    @Test
    @Uat("VO-21")
    fun aWordDeletedOrBlockedIsNotListenedFor() {
        val wang = UserWord("汪小明", "wang xiao ming", Kind.LEARNED, 2f)
        // deleted: no longer among the user's words (EnginesUserWordsTest's removals), so nothing to hear
        val blocked = VoiceHotwords.ofUser(listOf(wang.copy(kind = Kind.BLOCKED)))
        assertEquals("", blocked.hotwords)
        assertEquals(listOf("汪小明"), blocked.blockedWords)
        // and never typed: a hearing with it standing passed over
        assertEquals(1, VoiceBlocking.pick(listOf("汪小明明天来", "王小明明天来"), blocked.blockedWords, emptyMap()))
    }

    @Test
    @Uat("VO-22")
    fun habitsOffListensForNoneOfTheirs() {
        val wang = UserWord("汪小明", "wang xiao ming", Kind.LEARNED, 2f)
        assertEquals(emptyList<String>(), listened(wang, habits = false))
        // words read while it was on, used after it went off: theirs dropped too
        assertEquals("", VoiceHotwords.ofUser(listOf(wang)).withHabits(false).hotwords)
        assertTrue(listened(wang).isNotEmpty())
    }

    @Test
    @Uat("VO-10")
    fun aBlockedWordIsNotTypedFromVoice() {
        val blocked = VoiceHotwords.ofUser(listOf(UserWord("天气", "tian qi", Kind.BLOCKED))).blockedWords
        // the engine splitting 天气 as a word: the hearing with it passed over for the next
        val words = mapOf("今天天气很好" to intArrayOf(0, 2, 4, 6))
        val language = object : VoiceChoice.Language {
            override suspend fun boundaries(texts: List<String>) = words
            override suspend fun logProbs(texts: List<String>): FloatArray? = null
        }
        fun heard(vararg nbest: String) = VoiceChoice.Heard(nbest.toList(), FloatArray(0), nbest.first())
        fun choose(h: VoiceChoice.Heard, again: () -> VoiceChoice.Heard): String {
            var outcome: Result<String>? = null
            val block: suspend () -> String = { VoiceChoice.choose(h, blocked, language) { again() } }
            block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
            return checkNotNull(outcome).getOrThrow()
        }
        assertEquals("今天天其很好", choose(heard("今天天气很好", "今天天其很好")) { error("searched again") })
        // each hearing with it: heard again with it blocked in the search
        assertEquals("今天天器很好", choose(heard("今天天气很好")) { heard("今天天器很好") })
    }
}
