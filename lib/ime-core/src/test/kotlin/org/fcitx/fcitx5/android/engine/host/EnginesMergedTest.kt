/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/** The merged dictionaries' file (DictionaryFile) typed through the engines: picked, and the time a key takes. */
class EnginesMergedTest : EnginesFixture() {
    @get:Rule
    val folder = TemporaryFolder()

    // review S6: a merged word picked is spelt from the file's trie (Reading.Spelt) and committed
    @Test
    fun aMergedWordPickedIsCommittedWhole() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("妮好 ni'hao 0.5") })
        Engines(::load, folder.newFolder("engine"), { throw it }, additions = { additions }).use { engines ->
            val shown = engines.type(Engines.PINYIN, "nihao").candidates
            assertTrue(shown.toString(), "妮好" in shown)
            assertEquals("妮好", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, shown.indexOf("妮好")).commit)
        }
    }

    // review F3412 / U-93: habits off, a table offers the merged file's words as pinyin does (the
    // dictionaries turned on are a setting, not a habit), under longer codes too
    @Test
    fun habitsOffATableOffersTheMergedWordsAsPinyinDoes() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("好你 hao'ni 0.5", "你你 ni'ni 0.5") })
        Engines(::load, folder.newFolder("engine"), { throw it }, additions = { additions }, warmUps = { it.run() }).use { engines ->
            engines.settings = wubiShown
            // the user's own (review F3440): one added by hand, one imported, and a merged word blocked
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
            // none of the user's own words, the block held
            for ((code, word) in listOf("wqvb" to "你好", "vbvb" to "好好", "wqwq" to "你你")) {
                assertFalse(word, word in engines.type(WUBI, code).candidates)
                engines.onEvent(WUBI, EngineEvent.RESET, 0)
            }
            // pinyin's fresh model keeps them too
            assertTrue("好你" in engines.type(Engines.PINYIN, "haoni").candidates)
        }
    }

    // review S6: a key's time with a file of 300,000 words over every syllable, against none: the
    // walkers' merged trie adds arcs, never a pass over the file. Generous bounds: a slow CI machine
    @Test
    fun aKeyTakesAboutAsLongWithAFileOfManyWordsAsWithout() {
        val random = Random(7)
        val syllables = (0 until Syllables.count).map(Syllables::spelling).filter { it.isNotEmpty() }
        val lines = List(WORDS) {
            val n = 1 + random.nextInt(4)
            val text = String(CharArray(n) { (0x4E00 + random.nextInt(20_000)).toChar() })
            "$text ${List(n) { syllables[random.nextInt(syllables.size)] }.joinToString("'")} ${random.nextInt(1, 1000)}"
        }
        val big = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { lines.asSequence() })
        fun perKey(additions: Engines.Additions?): Double {
            Engines(::load, folder.newFolder(), { throw it }, additions = { additions }).use { engines ->
                // warmed: the first key opens everything
                engines.type(Engines.PINYIN, "ni")
                engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
                val keys = "nihaoshijiezhongguoren"
                val start = System.nanoTime()
                repeat(ROUNDS) {
                    engines.type(Engines.PINYIN, keys)
                    engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
                }
                return (System.nanoTime() - start) / 1e6 / (ROUNDS * keys.length)
            }
        }
        val without = perKey(null)
        val with = perKey(big)
        println("per key: ${"%.3f".format(without)} ms without, ${"%.3f".format(with)} ms with $WORDS merged words")
        assertTrue("$with ms a key against $without", with < without * 3 + 2)
    }

    private companion object {
        const val WUBI = "engine-wubi"
        const val WORDS = 300_000
        const val ROUNDS = 5
    }
}
