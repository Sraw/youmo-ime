/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.ReadingSource
import org.fcitx.fcitx5.android.engine.data.syl
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.input.voice.VoiceEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记住 judged on the stretches voice told ([Engines.voiceStretches]), not the clause, and what is said kept only where learning goes on. */
class EnginesVoiceStretchesTest : EnginesFixture() {

    init {
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("推导", -5f, 0f).entry("推导", syl("tui", "dao"))
            .unigram("这个", -3f, 0f).entry("这个", syl("zhe", "ge"))
            // 攻势 far less likely than 公式 alone, far likelier after 这个
            .unigram("公式", -4.7f, 0f).entry("公式", syl("gong", "shi"))
            .unigram("攻势", -9f, 0f).entry("攻势", syl("gong", "shi"))
            .bigram("这个", "攻势", -0.5f, 0f)
            .apply {
                for ((c, s, t) in listOf(Triple('公', "gong", 1), Triple('攻', "gong", 1), Triple('式', "shi", 4), Triple('势', "shi", 4))) {
                    charTone(c.code, Syllables.id(s), t)
                    charReading(ReadingSource.MANDARIN, c.code, Syllables.id(s), t)
                }
            }
            .build()
            .toByteArray()
    }

    // voice said 推导这个, then 公式 in a talk of its own (with [stretches], each told its stretch), 公式 deleted and
    // 攻势 typed: what is asked. On 推导这个公式, the clause, the model takes 攻势; on 公式 alone, voice's stretch, never
    private fun Engines.asked(stretches: Boolean = true, between: Engines.() -> Unit = {}): VoiceEdits.Ask {
        context(PINYIN, "", said = true)
        for ((said, before) in listOf("推导这个" to "推导这个", "公式" to "推导这个公式")) {
            if (stretches) voiceStretches(listOf(said))
            voiceSaid(said, before, hinted = false)
        }
        between()
        repeat(2) { assertFalse("the app's to delete", onEvent(PINYIN, EngineEvent.BACKSPACE, 0).handled) }
        type(PINYIN, "gongshi")
        val k = candidates(PINYIN, 0, 50).indexOfFirst { it.text == "攻势" }
        assertTrue(k >= 0)
        val commit = onEvent(PINYIN, EngineEvent.PICK, k).commit
        assertEquals("攻势", commit)
        val ask = voicePutIn(commit).ask?.let { voiceConfirm("推导这个$commit", "") }
        assertEquals("攻势", ask?.word)
        return ask!!
    }

    private fun engines() = Engines(::load, MemoryFiles(), warmUps = inPlace)

    @Test
    @Uat("VO-59")
    fun `a word typed again is judged on the stretch voice told, else on its clause`() = assertRows(
        "the stretch 公式 alone: only 总是换 would put it in" to { engines().use { assertEquals(true, it.asked().always) } },
        "no stretches told: the clause, where the model takes it" to { engines().use { assertEquals(false, it.asked(stretches = false).always) } },
    )

    @Test
    @Uat("VO-59")
    fun `past so many characters the oldest talks go, the latest kept`() {
        val long = listOf("嗯".repeat(3999))
        assertRows(
            "a long talk before: it goes, the two after it kept" to {
                engines().use { e ->
                    e.voiceStretches(long)
                    assertEquals(true, e.asked().always)
                }
            },
            "a long talk after: the two before it go" to {
                engines().use { e -> assertEquals(false, e.asked { voiceStretches(long) }.always) }
            },
        )
    }

    @Test
    @Uat("VO-59")
    fun `what is said is kept only while learning goes on, and forgotten as it ends`() {
        // 公式's stretch told, then [then]: whether 记住 still judges on it
        fun keeps(then: Engines.() -> Unit): Boolean = engines().use { e ->
            e.voiceStretches(listOf("公式"))
            e.then()
            e.asked(stretches = false).always
        }
        assertRows(
            "as it is" to { assertTrue(keeps {}) },
            "the window ended (another field)" to { assertFalse(keeps { voiceEditsEnd() }) },
            "learning cleared" to { assertFalse(keeps { learning(null, LearningOp.ClearLearned(all = true)) }) },
            "the last minutes cleared" to { assertFalse(keeps { learning(null, LearningOp.ClearRecent(LearningOp.Span.TEN_MINUTES)) }) },
            "a field that does not learn after it" to { assertFalse(keeps { context(PINYIN, "", learning = false) }) },
            "told in a field that does not learn" to {
                engines().use { e ->
                    e.context(PINYIN, "", learning = false)
                    e.voiceStretches(listOf("公式"))
                    assertEquals(false, e.asked(stretches = false).always)
                }
            },
            "told with the habits off" to {
                engines().use { e ->
                    e.useHabits = false
                    e.voiceStretches(listOf("公式"))
                    e.useHabits = true
                    assertEquals(false, e.asked(stretches = false).always)
                }
            },
        )
    }
}
