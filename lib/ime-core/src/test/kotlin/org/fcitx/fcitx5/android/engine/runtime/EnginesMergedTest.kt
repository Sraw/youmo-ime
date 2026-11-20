/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.random.Random

/** The merged dictionaries' file (DictionaryFile) typed through the engines: picked, and the time a key takes. */
class EnginesMergedTest : EnginesFixture() {

    @Test
    fun aMergedWordPickedIsCommittedWhole() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("妮好 ni'hao 0.5") })
        Engines(::load, MemoryFiles(), { throw it }, additions = { additions }).use { engines ->
            val shown = engines.type(InputMethodCatalog.PINYIN, "nihao").candidates
            assertTrue(shown.toString(), "妮好" in shown)
            assertEquals("妮好", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, shown.indexOf("妮好")).commit)
        }
    }

    // the dictionaries turned on are a setting, not a habit
    @Test
    fun habitsOffATableOffersTheMergedWordsAsPinyinDoes() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("好你 hao'ni 0.5", "你你 ni'ni 0.5") })
        Engines(::load, MemoryFiles(), { throw it }, additions = { additions }, warmUps = { it.run() }).use { engines ->
            engines.settings = wubiShown
            // the user's own: one added by hand, one imported, and a merged word blocked
            assertTrue(engines.addWord("你好", "ni hao"))
            assertEquals(1, engines.importWords(sequenceOf("好好 hao'hao 3"), "a.txt").imported)
            val at = engines.type(WUBI, "wqwq").candidates.indexOf("你你")
            assertTrue(engines.learning(WUBI, LearningOp.Block(at, "你你")) is LearningResult.Done)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            assertTrue("你好" in engines.type(WUBI, "wqvb").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            assertTrue("好好" in engines.type(WUBI, "vbvb").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            // habits on: the index built, the word under vbwq shown from vbw on
            engines.type(WUBI, "vbw")
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            assertTrue("好你" in engines.type(WUBI, "vbw").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            engines.useHabits = false
            engines.type(WUBI, "vbw")
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            assertTrue("好你" in engines.type(WUBI, "vbw").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            assertTrue("好你" in engines.type(WUBI, "vbwq").candidates)
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            for ((code, word) in listOf("wqvb" to "你好", "vbvb" to "好好", "wqwq" to "你你")) {
                assertFalse(word, word in engines.type(WUBI, code).candidates)
                engines.onEvent(WUBI, EngineEvent.RESET, 0)
            }
            assertTrue("好你" in engines.type(InputMethodCatalog.PINYIN, "haoni").candidates)
        }
    }

    // timed as thread CPU, batches alternated and medians compared: wall-clock bounds flaked
    @Test
    fun aKeyTakesAsLongWithAHundredTimesTheWordsElsewhereInTheFile() {
        val random = Random(7)
        fun lines(count: Int, syllables: List<String>) = List(count) {
            val n = 1 + random.nextInt(4)
            val text = String(CharArray(n) { (0x4E00 + random.nextInt(20_000)).toChar() })
            "$text ${List(n) { syllables[random.nextInt(syllables.size)] }.joinToString("'")} ${random.nextInt(1, 1000)}"
        }
        val all = (0 until Syllables.count).map(Syllables::spelling).filter { it.isNotEmpty() }
        val near = lines(NEAR, all)
        // initials none of KEYS' letters starts, nor a key next to one reads (fuzzy, slips): checked below, the candidates the same
        val elsewhere = lines(ELSEWHERE, all.filter { it[0] in "bpfdtkqxcw" })
        fun additions(lines: List<String>) = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { lines.asSequence() })
        // asked before either is made, and each made inside its use: closed whatever fails
        val cpu = ManagementFactory.getThreadMXBean()
        assumeTrue("the thread's CPU time", cpu.isCurrentThreadCpuTimeSupported)
        fun engines(lines: List<String>) = Engines(::load, MemoryFiles(), { throw it }, additions = { additions(lines) }, warmUps = { it.run() })
        fun Engines.typed(): List<List<String>> = KEYS.map { onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, it.code).candidates }
            .also { onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0) }
        fun Engines.perKey(): Double {
            val start = cpu.currentThreadCpuTime
            repeat(ROUNDS) { typed() }
            return (cpu.currentThreadCpuTime - start) / 1e6 / (ROUNDS * KEYS.length)
        }
        engines(near).use { small ->
            engines(near + elsewhere).use { big ->
                assertEquals(small.typed(), big.typed())
                val times = List(BATCHES) { b -> if (b % 2 == 0) small.perKey() to big.perKey() else big.perKey().let { small.perKey() to it } }
                val without = times.map { it.first }.sorted()[BATCHES / 2]
                val with = times.map { it.second }.sorted()[BATCHES / 2]
                println("per key: %.3f ms with %d words, %.3f ms with %d more elsewhere".format(without, NEAR, with, ELSEWHERE))
                assertTrue("$with ms a key against $without", with < without * 3)
            }
        }
    }

    private companion object {
        const val KEYS = "nihaoshijiezhongguoren"
        const val NEAR = 3_000
        const val ELSEWHERE = 297_000
        const val ROUNDS = 5
        const val BATCHES = 9
    }
}
