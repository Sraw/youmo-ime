/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** uat/12-voice.md's 语音先问再记 on the app's data: a name voice wrote, deleted and typed again by each way of typing, a common word or not. */
class VoiceRememberUatTest {

    private enum class Way { PINYIN_KEYS, WITH_MARK, NINE_KEYS, PEN }

    // [heard] voice's, after 我想 (a 。 after it with WITH_MARK); deleted to its start and [typed] by [way]: the word asked about
    private fun asked(heard: String, typed: String, keys: String, digits: String, way: Way): String? {
        val e = UatEngines.fresh()
        e.context(PINYIN, "", said = true)
        val said = "我想$heard" + if (way == Way.WITH_MARK) "。" else ""
        e.voiceSaid(said, said, hinted = false)
        repeat(said.length - 2) { e.onEvent(PINYIN, EngineEvent.BACKSPACE, 0) }
        val commit = when (way) {
            Way.PEN -> {
                var last = -1
                for (c in typed) {
                    val ink = UatEngines.ink(e, PINYIN, listOf(c.toString()), last)
                    last = ink.piece
                    e.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = ink)
                    e.inkSent(ink, PINYIN)
                }
                e.onEvent(PINYIN, EngineEvent.CHAR, ' '.code).commit
            }
            Way.NINE_KEYS -> e.typed(digits.map(Keyboard::pinyinNineKey), typed)
            else -> e.typed(keys.map { it.code }, typed)
        }
        assertEquals("$way typed", typed, commit)
        return e.voicePutIn(commit).ask?.let { e.voiceConfirm("我想$commit", "") }?.word
    }

    private fun Engines.typed(keys: List<Int>, word: String): String {
        keys.forEach { onEvent(PINYIN, EngineEvent.CHAR, it) }
        val shown = candidates(PINYIN, 0, CANDIDATES).map { it.text }
        val k = shown.indexOf(word)
        assertTrue("$word among ${shown.take(20)}", k >= 0)
        return onEvent(PINYIN, EngineEvent.PICK, k).commit
    }

    // whether voice, with [typed] the user's as [kind], would put it in for [heard] (the rule the question is asked by)
    private fun voiceTakes(heard: String, typed: String, kind: UserWord.Kind = UserWord.Kind.ADDED): Boolean {
        val e = UatEngines.fresh()
        val word = UserWord(typed, checkNotNull(e.pinyinOf(typed)), kind, count = LEARNED_TAKES)
        val spans = e.voiceHomophones(listOf(word)).candidates("我想$heard")
        return spans.isNotEmpty() && e.voiceHomophonesAllowed(spans).all { it }
    }

    // what voice's homophones write for [heard] with [typed] the user's as [kind]: after recognition, each stretch replaced in place
    private fun voiceWrites(heard: String, typed: String, kind: UserWord.Kind = UserWord.Kind.ADDED): String {
        val e = UatEngines.fresh()
        val word = UserWord(typed, checkNotNull(e.pinyinOf(typed)), kind, count = LEARNED_TAKES)
        val hold = e.voiceHomophones(listOf(word)).hold()
        var out: Result<String>? = null
        suspend { hold.replace(heard) { e.voiceHomophonesAllowed(it) } }.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return checkNotNull(out).getOrThrow()
    }

    @Test
    @Uat("VO-41", "VO-43", "VO-45")
    fun aNameTypedAgainIsAskedAboutCommonOrNot() = assertRows(
        *Way.entries.flatMap { way ->
            listOf(
                "$way: 丹丹 typed again as 单单, read alike: asked" to {
                    assertEquals("单单", asked("丹丹", "单单", "dandan", "326326", way))
                },
                // the user's report: 莉莉, a common word of the dictionary, was never asked about
                "$way: 莉莉 typed again as 丽丽, read alike, 莉莉 a common word: asked" to {
                    assertEquals("丽丽", asked("莉莉", "丽丽", "lili", "5454", way))
                },
            )
        }.toTypedArray(),
        "voice's rule with 单单 added puts it in for 丹丹" to { assertTrue(voiceTakes("丹丹", "单单")) },
        "voice's rule with 丽丽 added puts it in for 莉莉, a common word" to { assertTrue(voiceTakes("莉莉", "丽丽")) },
        "voice's rule with 丽丽 learned puts it in for 莉莉 too" to { assertTrue(voiceTakes("莉莉", "丽丽", UserWord.Kind.LEARNED)) },
        // replaced after recognition, a reduplicated word adds no character: 方方方方 came of the hotword
        "丽丽 added writes 我想莉莉 as 我想丽丽, no character more" to { assertEquals("我想丽丽", voiceWrites("我想莉莉", "丽丽")) },
        "方方 added writes 我想芳芳 as 我想方方" to { assertEquals("我想方方", voiceWrites("我想芳芳", "方方")) },
        // the two gates left, so neither is asked about either: a name inside a longer word, a common word cut
        "徐婧 added leaves 徐静 of 徐静蕾, a longer word" to { assertEquals("我喜欢徐静蕾的电影", voiceWrites("我喜欢徐静蕾的电影", "徐婧")) },
        "何芮 added leaves 和瑞 of 和瑞典, a common word across its edge" to { assertEquals("我想和瑞典人聊天", voiceWrites("我想和瑞典人聊天", "何芮")) },
        // char by char 是那个 reads as 是哪个 (那 and 哪 share a reading); the word 那个 inside it does not
        "是哪个 learned leaves 是那个, a word inside it read otherwise" to { assertEquals("我想是那个", voiceWrites("我想是那个", "是哪个", UserWord.Kind.LEARNED)) },
    )

    private companion object {
        const val CANDIDATES = 200

        // a learned word takes part in voice once typed more than LEARNED_MIN times
        const val LEARNED_TAKES = VoiceHomophones.LEARNED_MIN + 1
    }
}
