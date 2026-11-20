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
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.input.voice.VoiceEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音先问再记 through Engines: its commits and Backspaces followed, the word asked about added and taken off. */
class EnginesVoiceEditsTest : EnginesFixture() {

    init {
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("我", -2f, 0f).entry("我", syl("wo"))
            .unigram("找", -3f, 0f).entry("找", syl("zhao"))
            .unigram("徐", -4f, 0f).entry("徐", syl("xu"))
            .unigram("婧", -4f, 0f).entry("婧", syl("jing"))
            .unigram("靖", -4.5f, 0f).entry("靖", syl("jing"))
            // 单, read dan, and shan as a surname: what is added is read as typed
            .unigram("单", -4f, 0f).entry("单", syl("dan")).entry("单", syl("shan"))
            // 攻势 far less likely than 公式: the word model never takes it after 这个
            .unigram("公式", -4.7f, 0f).entry("公式", syl("gong", "shi"))
            .unigram("攻势", -9f, 0f).entry("攻势", syl("gong", "shi"))
            .apply {
                for ((c, s, t) in listOf(
                    Triple('我', "wo", 3), Triple('找', "zhao", 3), Triple('徐', "xu", 2), Triple('许', "xu", 3),
                    Triple('静', "jing", 4), Triple('婧', "jing", 4), Triple('靖', "jing", 4), Triple('单', "dan", 1), Triple('单', "shan", 4),
                    Triple('公', "gong", 1), Triple('攻', "gong", 1), Triple('式', "shi", 4), Triple('势', "shi", 4),
                )) {
                    charTone(c.code, Syllables.id(s), t)
                    charReading(ReadingSource.MANDARIN, c.code, Syllables.id(s), t)
                }
            }
            .build()
            .toByteArray()
    }

    // voice's [said], its last two deleted, [keys] typed in pinyin and [word] picked: what is asked
    private fun Engines.retyped(
        said: String = "我找徐静",
        learning: Boolean = true,
        keys: String = "xujing",
        word: String = "徐婧",
        hinted: Boolean = false,
        n: Int = 2,
    ): VoiceEdits.Ask? {
        voiceSaid(said, said, hinted)
        repeat(n) { assertFalse("the app's to delete", onEvent(PINYIN, EngineEvent.BACKSPACE, 0).handled) }
        return typedAgain(said.dropLast(n), keys, word, learning)
    }

    // [keys] typed in pinyin after [kept], [word] picked a candidate at a time: what is asked
    private fun Engines.typedAgain(kept: String, keys: String, word: String, learning: Boolean = true): VoiceEdits.Ask? {
        type(PINYIN, keys)
        var picked = ""
        var commit = ""
        while (commit.isEmpty()) {
            val rest = word.substring(picked.length)
            val shown = candidates(PINYIN, 0, 50).map { it.text }
            val k = shown.indexOf(rest).takeIf { it >= 0 } ?: shown.indexOfFirst { it.isNotEmpty() && rest.startsWith(it) }
            assertTrue("$rest among $shown", k >= 0)
            picked += shown[k]
            commit = onEvent(PINYIN, EngineEvent.PICK, k, learning).commit
        }
        assertEquals(word, commit)
        return voicePutIn(commit).ask?.let { voiceConfirm(kept + commit, "") }
    }

    // the handwriting pad's reading of [piece] offered with its key (EnginesHandwritingTest)
    private fun Engines.write(piece: Int, text: String) = onEvent(
        PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(piece, InkReading(listOf(text), DoubleArray(1))),
    )

    private fun added(e: Engines) = e.userWords(UserWord.Kind.ADDED).map { it.text }

    @Test
    @Uat("VO-41", "WD-59")
    fun `a word typed again is asked about, added on 记住 and taken off on its 撤销`() {
        val dir = MemoryFiles()
        Engines(::load, dir, warmUps = inPlace).use { e ->
            e.context(PINYIN, "", said = true)
            // the steps of one scenario, in order: each needs the one before
            val ask = e.retyped()
            // asked, read as typed: voice's rules with it added would put it in
            assertEquals("徐婧", ask?.word)
            assertEquals("xu jing", ask?.pinyin)
            assertNotNull(ask)
            assertTrue(e.voiceRemember(ask!!))
            assertEquals(listOf("徐婧"), added(e))
            // 撤销: off the added words, nothing forgotten in the learning record
            e.voiceForget(ask)
            assertEquals(emptyList<String>(), added(e))
            assertFalse(records(dir).any { it is LearnRecord.Forget && it.text == "徐婧" })
        }
    }

    @Test
    @Uat("VO-41")
    fun `a word is added read as its syllables were typed (review S2)`() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.context(PINYIN, "", said = true)
            val ask = e.retyped("我找单静", keys = "shanjing", word = "单婧")
            // 单 typed shan: asked, read shan jing (its readings, dan and shan, leave it open)
            assertEquals("单婧", ask?.word)
            assertEquals("shan jing", ask?.pinyin)
            assertTrue(e.voiceRemember(ask!!))
            assertEquals(listOf("shan jing"), e.userWords(UserWord.Kind.ADDED).map { it.pinyin })
        }
    }

    @Test
    @Uat("VO-44")
    fun `asked again at the place, the first word added gives way (review M4)`() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.context(PINYIN, "", said = true)
            val first = e.retyped()!!
            assertTrue(e.voiceRemember(first))
            // the word deleted again and another of the same reading typed: voice judged without the first, ranked first
            repeat(2) { e.onEvent(PINYIN, EngineEvent.BACKSPACE, 0) }
            val second = e.typedAgain("我找", "xujing", "徐靖")
            assertEquals("徐靖", second?.word)
            assertEquals("徐婧", second?.replaces)
            assertTrue(e.voiceRemember(second!!))
            assertEquals(listOf("徐靖"), added(e))
        }
    }

    // 许静, read xu3 jing4, has no part in 徐静 (xu2): voice's words not empty, what is asked judged on them with it added
    private fun Engines.addedUnrelated() = assertTrue(addWord("许静", "xu jing"))

    @Test
    @Uat("VO-41")
    fun `with an unrelated word added before, a word typed again is asked about the same`() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.addedUnrelated()
            e.context(PINYIN, "", said = true)
            val ask = e.retyped()
            assertEquals("徐婧", ask?.word)
            assertEquals("xu jing", ask?.pinyin)
            assertTrue(e.voiceRemember(ask!!))
            assertEquals(listOf("许静", "徐婧"), added(e))
        }
    }

    @Test
    @Uat("VO-44")
    fun `with an unrelated word added before, asked again at the place, the first word added gives way`() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.addedUnrelated()
            e.context(PINYIN, "", said = true)
            assertTrue(e.voiceRemember(e.retyped()!!))
            repeat(2) { e.onEvent(PINYIN, EngineEvent.BACKSPACE, 0) }
            val second = e.typedAgain("我找", "xujing", "徐靖")
            assertEquals("徐靖", second?.word)
            assertEquals("徐婧", second?.replaces)
            assertTrue(e.voiceRemember(second!!))
            assertEquals(listOf("许静", "徐靖"), added(e))
        }
    }

    @Test
    @Uat("VO-44")
    fun `a word that does not add leaves the first be (review S8)`() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.context(PINYIN, "", said = true)
            assertTrue(e.voiceRemember(e.retyped()!!))
            val bad = VoiceEdits.Ask("徐靖", "xu", "徐静", replaces = "徐婧", hint = false, cells = emptySet())
            assertFalse(e.voiceRemember(bad))
            assertEquals(listOf("徐婧"), added(e))
        }
    }

    @Test
    @Uat("VO-45")
    fun `a word written again on the pad is asked about, read as the characters are`() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.context(PINYIN, "", said = true)
            e.voiceSaid("我找徐静", "我找徐静", hinted = false)
            repeat(2) { assertFalse("the app's to delete", e.onEvent(PINYIN, EngineEvent.BACKSPACE, 0).handled) }
            e.write(0, "徐")
            e.write(1, "婧")
            val commit = e.onEvent(PINYIN, EngineEvent.CHAR, ' '.code).commit
            val ask = e.voicePutIn(commit).ask?.let { e.voiceConfirm("我找$commit", "") }
            assertRows(
                "written as 徐婧" to { assertEquals("徐婧", commit) },
                "asked, read by the characters' readings as voice's (no syllables typed)" to {
                    assertEquals("徐婧", ask?.word)
                    assertEquals("xu jing", ask?.pinyin)
                },
            )
        }
    }

    @Test
    @Uat("VO-51")
    fun `the once-only hint is not looked for once said`() = assertRows(
        "one character typed again where a word would be asked: the hint" to {
            Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
                e.context(PINYIN, "", said = true)
                assertEquals(true, e.retyped(n = 1, keys = "jing", word = "婧")?.hint)
            }
        },
        "the app saying it was shown: nothing" to {
            Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
                e.context(PINYIN, "", said = true)
                assertNull(e.retyped(n = 1, keys = "jing", word = "婧", hinted = true))
            }
        },
    )

    @Test
    @Uat("VO-59")
    fun `corrected where the word model would not put it in, asked whether always to, judged at that place`() {
        val dir = MemoryFiles()
        Engines(::load, dir, warmUps = inPlace).use { e ->
            e.context(PINYIN, "", said = true)
            // 攻势 heard already in the first clause: no reason to ask 「记住」 about the second
            val said = "敌人的攻势很猛，推导这个公式"
            val ask = e.retyped(said = said, keys = "gongshi", word = "攻势")
            assertRows(
                "asked whether always to" to { assertEquals(true, ask?.always) },
                "of what voice heard there" to { assertEquals("公式", ask?.heard) },
                "not added before" to { assertEquals(false, ask?.added) },
            )
            assertTrue(e.voiceRemember(ask!!))
            assertRows(
                "taken: added and always put in" to {
                    assertEquals(listOf("攻势" to true), e.userWords(UserWord.Kind.ADDED).map { it.text to it.voiceAlways })
                },
                "voice now puts it in where the model alone would not" to {
                    val h = e.voiceHomophones(e.userWords())
                    assertEquals("推导这个攻势", e.voicePick(h, "推导这个公式", "", emptyList()))
                },
            )
            // 撤销: off the added words and no longer always
            e.voiceForget(ask)
            assertEquals(emptyList<UserWord>(), e.userWords(UserWord.Kind.ADDED))
        }
    }

    @Test
    @Uat("VO-59")
    fun `an added word corrected again where the model left it is asked about always, and taking that back keeps it added`() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            assertTrue(e.addWord("攻势", "gong shi"))
            e.context(PINYIN, "", said = true)
            val ask = e.retyped(said = "推导这个公式", keys = "gongshi", word = "攻势")
            assertRows(
                "asked whether always to" to { assertEquals(true, ask?.always) },
                "added before" to { assertEquals(true, ask?.added) },
            )
            val h = e.voiceHomophones(e.userWords())
            assertEquals("added alone: the model leaves 公式", "推导这个公式", e.voicePick(h, "推导这个公式", "", emptyList()))
            assertTrue(e.voiceRemember(ask!!))
            assertEquals(listOf(true), e.userWords(UserWord.Kind.ADDED).map { it.voiceAlways })
            e.voiceForget(ask)
            assertEquals(listOf("攻势" to false), e.userWords(UserWord.Kind.ADDED).map { it.text to it.voiceAlways })
        }
    }

    @Test
    @Uat("VO-59")
    fun `总是换 goes with a deleted word and comes back with its undo, on the word list and from a candidate`() {
        fun marked(delete: Engines.() -> LearningResult) = Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            assertTrue(e.addWord("攻势", "gong shi"))
            assertTrue(e.addWord("徐婧", "xu jing"))
            e.learning(null, LearningOp.VoiceAlways("攻势", true))
            e.learning(null, LearningOp.VoiceAlways("徐婧", true))
            val done = e.delete() as LearningResult.Done
            assertEquals(listOf("徐婧" to true), e.userWords(UserWord.Kind.ADDED).map { it.text to it.voiceAlways })
            // one unmarked meanwhile stays so: only the deleted word's comes back
            e.learning(null, LearningOp.VoiceAlways("徐婧", false))
            e.learning(PINYIN, LearningOp.Undo(done.undo))
            e.userWords(UserWord.Kind.ADDED).map { it.text to it.voiceAlways }.toSet()
        }
        val back = setOf("攻势" to true, "徐婧" to false)
        assertRows(
            "deleted on the word list" to {
                assertEquals(back, marked { learning(null, LearningOp.RemoveWords(listOf(UserWord("攻势", "gong shi", UserWord.Kind.ADDED)))) })
            },
            "deleted from a candidate" to {
                assertEquals(back, marked {
                    context(PINYIN, "", cleared = true)
                    type(PINYIN, "gongshi")
                    val at = candidates(PINYIN, 0, 20).indexOfFirst { it.text == "攻势" }
                    learning(PINYIN, LearningOp.Delete(at, "攻势"))
                })
            },
        )
    }

    @Test
    @Uat("VO-52")
    fun `undone, what typing the word learned stays (review S3)`() {
        // the word retyped, then typed [more] times: what the user's words learned say of it
        fun learned(undone: Boolean, more: Int) = Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.context(PINYIN, "", said = true)
            val ask = e.retyped()!!
            if (undone) {
                assertTrue(e.voiceRemember(ask))
                e.voiceForget(ask)
            }
            repeat(more) {
                e.onEvent(PINYIN, EngineEvent.RESET, 0)
                e.type(PINYIN, "xujing")
                assertEquals("徐婧", e.onEvent(PINYIN, EngineEvent.PICK, 0).commit)
            }
            e.userWords(UserWord.Kind.LEARNED).map { "${it.text}:${it.count}" }
        }
        assertRows(*(0..3).map { n -> "typed $n more: learned as never 记住'd" to { assertEquals(learned(false, n), learned(true, n)) } }.toTypedArray())
    }

    @Test
    @Uat("VO-46", "VO-43")
    fun `nothing is followed where nothing would be learned`() = assertRows(
        "the habits not used (U-93)" to {
            Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
                e.useHabits = false
                assertNull(e.retyped())
            }
        },
        "the habits turned off while followed (review S4)" to {
            Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
                e.voiceSaid("我找徐静", "我找徐静", hinted = false)
                repeat(2) { e.onEvent(PINYIN, EngineEvent.BACKSPACE, 0) }
                e.useHabits = false
                assertNull(e.typedAgain("我找", "xujing", "徐婧"))
            }
        },
        "a commit not learned from" to {
            Engines(::load, MemoryFiles(), warmUps = inPlace).use { e -> assertNull(e.retyped(learning = false)) }
        },
        "a context of a field cleared" to {
            Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
                e.voiceSaid("我找徐静", "我找徐静", hinted = false)
                e.context(PINYIN, "我找徐静", cleared = true)
                assertFalse(e.voicePutIn("徐婧").open)
            }
        },
        "input finished" to {
            Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
                e.voiceSaid("我找徐静", "我找徐静", hinted = false)
                e.voiceEditsEnd()
                assertFalse(e.voicePutIn("徐婧").open)
            }
        },
        "a tone not the same, by the data's readings (Q2)" to {
            Engines(::load, MemoryFiles(), warmUps = inPlace).use { e -> assertNull(e.retyped("我找许静")) }
        },
    )

    // what was read for the question is let go when the window closes, not kept till the next
    private fun letGo(closing: Engines.() -> Unit) = Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
        e.addedUnrelated()
        e.context(PINYIN, "", said = true)
        assertNotNull(e.retyped())
        assertTrue("held while the window is open", e.voiceEditsHoldWords)
        e.closing()
        assertFalse(e.voiceEditsHoldWords)
    }

    @Test
    fun `the user's words read for an edit are let go when its window closes`() = assertRows(
        "input finished" to { letGo { voiceEditsEnd() } },
        "the habits turned off (U-93)" to { letGo { useHabits = false } },
        "a commit not learned from (answered)" to {
            letGo {
                type(PINYIN, "wo")
                assertEquals("我", onEvent(PINYIN, EngineEvent.PICK, 0, false).commit)
            }
        },
        "a context of a field cleared" to { letGo { context(PINYIN, "", cleared = true) } },
        "a context not learned from" to { letGo { context(PINYIN, "我找徐婧", learning = false) } },
        // the app's report of a paste closes the window without a word of the glue's: the next context lets go
        "a paste, then the next context" to {
            letGo {
                assertFalse(voicePutIn("，").open)
                context(PINYIN, "我找徐婧，")
            }
        },
    )
}
