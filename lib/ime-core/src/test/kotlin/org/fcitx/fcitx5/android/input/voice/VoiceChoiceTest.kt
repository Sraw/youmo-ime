/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class VoiceChoiceTest {

    private val blocked = listOf("开饭")

    // 开饭 standing as a word of its own in each
    private val words = mapOf("开饭了" to intArrayOf(0, 2, 3), "开饭啦" to intArrayOf(0, 2, 3))

    @Test
    fun theModelPicksAmongWhatWasHeardAlike() {
        val language = FakeLanguage(likely = { texts -> FloatArray(texts.size) { if (texts[it] == "很佛系") 0f else -10f } })
        val heard = VoiceChoice.Heard(listOf("恨佛系。", "很佛系。"), floatArrayOf(-0.1f, -0.2f), "恨佛系。")
        assertEquals("很佛系。", choose(heard, emptyList(), language))
    }

    @Test
    fun withNothingToRankTheModelIsNotAsked() {
        val language = FakeLanguage()
        // English set apart: no candidate to rank
        assertEquals("好的", choose(VoiceChoice.Heard(listOf("好的", "hello"), FloatArray(0), "好的"), emptyList(), language))
        // no hypotheses, only its best
        assertEquals("好的", choose(VoiceChoice.Heard(emptyList(), FloatArray(0), "好的"), emptyList(), language))
        assertTrue(language.scored.isEmpty())
    }

    @Test
    fun withNoBlockedWordInAnyTheBestIsTyped() {
        val language = FakeLanguage(words)
        assertEquals("开放了", choose(heard("开放了", "开饭了"), emptyList(), language))
        assertEquals("开放了", choose(heard("开放了"), blocked, language))
        assertTrue(language.split.isEmpty())
    }

    @Test
    fun whatIsTypedInsteadOfABlockedWord() = assertRows(
        "the first without the blocked word standing is typed" to {
            assertEquals("开放了", choose(heard("开饭了", "开放了"), blocked, FakeLanguage(words)))
        },
        "before the search again one below with the word at all is passed over" to {
            // 开饭啦 below the first, though not split: passed over for 开放了
            assertEquals("开放了", choose(heard("开饭了", "开饭啦", "开放了"), blocked, FakeLanguage(mapOf("开饭了" to intArrayOf(0, 2, 3)))))
        },
        "the search again with it in none is typed" to {
            assertEquals("开放了", choose(heard("开饭了"), blocked, FakeLanguage(words)) { heard("开放了") })
        },
        "the search again still with it in each types its best" to {
            val again = VoiceChoice.Heard(listOf("开饭啦", "开饭了"), FloatArray(0), "开饭啦")
            assertEquals("开饭啦", choose(heard("开饭了"), blocked, FakeLanguage(words)) { again })
        },
        "the search again's best is typed as the recognizer writes it whichever others have the word" to {
            // a lower hypothesis with it standing: its best still typed as written, not as its first hypothesis
            val again = VoiceChoice.Heard(listOf("开放了", "开饭了"), FloatArray(0), "开放了。")
            assertEquals("开放了。", choose(heard("开饭了"), blocked, FakeLanguage(words)) { again })
        },
        "the search again's lower without it standing beats its best with it" to {
            // the search has nothing after it: 打开饭盒 (开饭 across two words) over 开饭啦 typed with it
            val split = words + ("打开饭盒" to intArrayOf(0, 2, 4))
            assertEquals("打开饭盒", choose(heard("开饭了"), blocked, FakeLanguage(split)) { heard("开饭啦", "打开饭盒") })
        },
    )

    @Test
    fun eachWithItTheSearchAgainsFirstWithoutItIsTyped() {
        var searched = 0
        val typed = choose(heard("开饭了"), blocked, FakeLanguage(words)) {
            searched++
            heard("开饭啦", "开放啦")
        }
        assertEquals("开放啦", typed)
        assertEquals(1, searched)
    }

    @Test
    fun theSearchAgainWithItInNoneIsTypedAsTheRecognizerWritesIt() {
        // its text, where it is not its first hypothesis as written
        val again = VoiceChoice.Heard(listOf("开放了"), FloatArray(0), "开放了。")
        assertEquals("开放了。", choose(heard("开饭了"), blocked, FakeLanguage(words)) { again })
        // and with no hypotheses, only its text
        assertEquals("开放啦", choose(heard("开饭了"), blocked, FakeLanguage(words)) { VoiceChoice.Heard(emptyList(), FloatArray(0), "开放啦") })
    }

    @Test
    fun theModelsPickWithItStandingGivesWayToTheRecognizersNext() {
        // the model likes 开饭了 best, then 开发了; without 开饭了 the recognizer's order decides
        val likely = mapOf("开饭了" to 0f, "开发了" to -1f)
        val language = FakeLanguage(words, likely = { texts -> FloatArray(texts.size) { likely[texts[it]] ?: -5f } })
        val heard = VoiceChoice.Heard(listOf("开放了", "开饭了", "开发了"), floatArrayOf(0f, 0f, 0f), "开放了")
        assertEquals("开饭了", choose(heard, emptyList(), language))
        assertEquals("开放了", choose(heard, blocked, language))
    }

    // 生僻字屏蔽: a hypothesis with a character held back passed over
    @Test
    fun aHypothesisWithACharacterHeldBackIsPassedOver() {
        val held = { t: String -> "𤝰" in t }
        assertRows(
            "nothing blocked: the first without it" to {
                assertEquals("大大", now { VoiceChoice.choose(heard("大𤝰", "大大"), emptyList(), FakeLanguage(), held) { error("searched again") } })
            },
            "each with it: the first, something typed all the same" to {
                assertEquals("大𤝰", now { VoiceChoice.choose(heard("大𤝰", "𤝰"), emptyList(), FakeLanguage(), held) { error("searched again") } })
            },
            "a word blocked too: the first with neither" to {
                val got = now { VoiceChoice.choose(heard("开饭𤝰", "开饭了", "开放了"), blocked, FakeLanguage(words), held) { error("searched again") } }
                assertEquals("开放了", got)
            },
            "searched again: its best with it passed over for its next" to {
                val again = VoiceChoice.Heard(listOf("开放𤝰", "开放啦"), FloatArray(0), "开放𤝰")
                assertEquals("开放啦", now { VoiceChoice.choose(heard("开饭了"), blocked, FakeLanguage(words), held) { again } })
            },
        )
    }

    // 徐婧 added, read xu2 jing4 as 徐静 is
    private val xuJing: VoiceHomophones = run {
        val chars = mapOf('徐' to intArrayOf(VoiceHomophones.reading(1, 2)), '静' to intArrayOf(VoiceHomophones.reading(2, 4)), '婧' to intArrayOf(VoiceHomophones.reading(2, 4)))
        VoiceHomophones.of(listOf(UserWord("徐婧", "", UserWord.Kind.ADDED)), { w -> w.text.map { chars.getValue(it) } }) { chars[it] }
    }

    // the engine's pick: what [answer] says, each ask kept
    private class Picking(private val answer: (String) -> String?) : VoiceChoice.Language {
        val asked = mutableListOf<String>()
        override suspend fun boundaries(texts: List<String>) = emptyMap<String, IntArray>()
        override suspend fun logProbs(texts: List<String>): FloatArray? = null
        override suspend fun pick(homophones: VoiceHomophones, text: String, before: String, blocked: List<String>): String? {
            asked += "$before|$text|${blocked.joinToString()}"
            return answer(text)
        }
    }

    @Test
    @Uat("VO-38")
    fun `the user's words go in as the engine picks, never unasked`() {
        assertRows(
            "the engine's pick, with what came before and the blocked words" to {
                val l = Picking { it.replace("徐静", "徐婧") }
                assertEquals("找徐婧", now { VoiceChoice.pick("找徐静", "你好", xuJing, listOf("坏"), l) })
                assertEquals(listOf("你好|找徐静|坏"), l.asked)
            },
            "the engine not answering in time: as chosen, not put in by hand" to {
                assertEquals("找徐静", now { VoiceChoice.pick("找徐静", "", xuJing, emptyList(), Picking { null }) })
            },
            "nothing read alike: the engine not asked" to {
                val l = Picking { error("asked") }
                assertEquals("你好", now { VoiceChoice.pick("你好", "", xuJing, emptyList(), l) })
            },
        )
    }

    // asks launched on the side, run when a row says
    private inner class Side {
        val queued = ArrayList<suspend () -> Unit>()
        val shown = ArrayList<String>()
        fun live(l: VoiceChoice.Language) = VoiceChoice.Live(xuJing, emptyList(), l, { queued += it }, show = { shown += it })
        fun talk(l: VoiceChoice.Language) = VoiceChoice.Talk(xuJing, emptyList(), l, { queued += it }, show = { shown += it })
        fun run() {
            while (queued.isNotEmpty()) queued.removeAt(0).let { now { it() } }
        }
    }

    @Test
    @Uat("VO-38")
    fun `what shows while said is never waited for, asked once a place, shown if still shown`() {
        assertRows(
            "a place not answered: shown as heard, asked on the side, its answer shown" to {
                val l = Picking { it.replace("徐静", "徐婧") }
                val side = Side()
                val live = side.live(l)
                assertEquals("徐静", live.pick("徐静", ""))
                assertEquals(emptyList<String>(), l.asked)
                side.run()
                assertEquals(listOf("|徐静|"), l.asked)
                assertEquals(listOf("徐婧"), side.shown)
            },
            "answered, the same place again, more said past the few characters around it: not asked" to {
                val l = Picking { it.replace("徐静", "徐婧") }
                val side = Side()
                val live = side.live(l)
                live.pick("徐静来了没有", "我找")
                side.run()
                assertEquals("徐婧来了没有", live.pick("徐静来了没有", "我找"))
                assertEquals("徐婧来了没有啊", live.pick("徐静来了没有啊", "我找"))
                side.run()
                assertEquals(1, l.asked.size)
            },
            "more said about the place: asked again, its last answer shown meanwhile" to {
                val l = Picking { it.replace("徐静", "徐婧") }
                val side = Side()
                val live = side.live(l)
                live.pick("徐静", "")
                side.run()
                assertEquals("徐婧来", live.pick("徐静来", ""))
                side.run()
                assertEquals(2, l.asked.size)
            },
            "the text moved on before the answer: not shown" to {
                val side = Side()
                val live = side.live(Picking { it.replace("徐静", "徐婧") })
                live.pick("徐静", "")
                live.pick("你好", "")
                side.run()
                assertEquals(emptyList<String>(), side.shown)
            },
            "texts while one is asked: only the latest asked after it" to {
                val l = Picking { it.replace("徐静", "徐婧") }
                val side = Side()
                val live = side.live(l)
                live.pick("徐静", "")
                live.pick("徐静来", "")
                live.pick("徐静来了", "")
                assertEquals(1, side.queued.size)
                side.run()
                assertEquals(listOf("|徐静来了|"), l.asked)
                assertEquals(listOf("徐婧来了"), side.shown)
            },
            "the stretch closed before the ask ran: nothing asked nor shown" to {
                val l = Picking { it.replace("徐静", "徐婧") }
                val side = Side()
                val live = side.live(l)
                live.pick("徐静", "")
                live.closed()
                side.run()
                assertEquals(emptyList<String>(), l.asked)
                assertEquals(emptyList<String>(), side.shown)
            },
            "after one failure, this talk's later texts unasked" to {
                val l = Picking { null }
                val side = Side()
                val live = side.live(l)
                live.pick("徐静", "")
                side.run()
                assertEquals("徐静来", live.pick("徐静来", ""))
                assertEquals(0, side.queued.size)
                assertEquals(1, l.asked.size)
            },
        )
    }

    @Test
    @Uat("VO-38")
    fun `a word put in while said stays once nothing is asked`() {
        assertRows(
            "stopped (the finger lifted): an answered place still put in, a new one as heard and unasked, an answer on its way not shown" to {
                val l = Picking { it.replace("徐静", "徐婧") }
                val side = Side()
                val live = side.live(l)
                live.pick("徐静来了没有", "我找")
                side.run()
                live.pick("徐静来了没有啊", "我找")
                live.pick("徐静", "")
                live.stop()
                side.run()
                assertEquals(listOf("徐婧来了没有"), side.shown)
                assertEquals("徐婧来了没有啊，徐静", live.pick("徐静来了没有啊，徐静", "我找"))
                assertEquals(0, side.queued.size)
                assertEquals(1, l.asked.size)
            },
            "after one failure, the places answered before still put in" to {
                var answers = 0
                val l = Picking { t -> if (answers++ == 0) t.replace("徐静", "徐婧") else null }
                val side = Side()
                val live = side.live(l)
                live.pick("徐静来了没有", "我找")
                side.run()
                live.pick("徐静来了没有啊，徐静", "我找")
                side.run()
                assertEquals("徐婧来了没有啊，徐静呢", live.pick("徐静来了没有啊，徐静呢", "我找"))
                assertEquals(0, side.queued.size)
                assertEquals(2, l.asked.size)
            },
        )
    }

    @Test
    @Uat("VO-38")
    fun `a talk reads each stretch after the one kept before it`() {
        val l = Picking { it.replace("徐静", "徐婧") }
        val talk = Side().talk(l)
        assertEquals("找徐婧", now { talk.chosen("找徐静") })
        talk.kept("找徐婧")
        talk.kept("")
        assertEquals("徐婧来了", now { talk.chosen("徐静来了") })
        talk.kept("徐婧来了")
        assertRows(
            "each after the last one kept, an empty one passed over" to { assertEquals(listOf("|找徐静|", "找徐婧|徐静来了|"), l.asked) },
            "those kept, for 记住's judging" to { assertEquals(listOf("找徐婧", "徐婧来了"), talk.stretches) },
            "the words put in, for the polish to keep" to {
                val putIn = LinkedHashSet<String>()
                val t = VoiceChoice.Talk(xuJing, emptyList(), l, {}, {}, putIn = putIn)
                now { t.chosen("说了你好") }
                assertEquals(emptySet<String>(), putIn)
                now { t.chosen("找徐静") }
                assertEquals(setOf("徐婧"), putIn)
            },
        )
    }

    private fun heard(vararg nbest: String) = VoiceChoice.Heard(nbest.toList(), FloatArray(0), nbest.first())

    private fun choose(
        heard: VoiceChoice.Heard,
        blocked: List<String>,
        language: VoiceChoice.Language,
        searchBlocked: () -> VoiceChoice.Heard = { error("searched again") },
    ): String = now { VoiceChoice.choose(heard, blocked, language) { searchBlocked() } }

    // the fakes never suspend: it runs to its end at once
    private fun <T> now(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome).getOrThrow()
    }

    // splits as [words] has it, scores as [likely] does, and keeps what it was asked
    private class FakeLanguage(
        private val words: Map<String, IntArray> = emptyMap(),
        private val likely: (List<String>) -> FloatArray? = { null },
    ) : VoiceChoice.Language {
        val split = mutableListOf<List<String>>()
        val scored = mutableListOf<List<String>>()

        override suspend fun boundaries(texts: List<String>): Map<String, IntArray> {
            split.add(texts)
            return words
        }

        override suspend fun logProbs(texts: List<String>): FloatArray? {
            scored.add(texts)
            return likely(texts)
        }
    }
}
