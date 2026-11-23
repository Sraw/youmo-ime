/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.syl
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** One user lexicon for pinyin, the tables and the stroke lookup. */
class SharedLexiconTest {

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("好", -2.5f, 0f)
        .entry("你", syl("ni"))
        .entry("好", syl("hao"))
        .build()
        .toByteArray()

    private val wubi = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .entry("wqiy", "你")
        .entry("vbg", "好")
        .entry("vbgf", "妤")
        .build()
        .toByteArray()

    private val strokes = Strokes.read("...\n你\tpsnpzsn\n好\tzphzsh\n".reader().buffered(), "stroke").build().toByteArray()

    private fun load(path: String): ByteBuffer = ByteBuffer.wrap(
        when (path) {
            InputMethodCatalog.PINYIN_DATA -> pinyin
            "${InputMethodCatalog.TABLE_DIR}/wbx.data" -> wubi
            InputMethodCatalog.STROKE_DATA -> strokes
            else -> throw FileNotFoundException(path)
        },
    )

    @Test
    fun oneUserLexiconServesPinyinAndTheTables() {
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            // put together in pinyin twice, 你好 is the user's word; 五笔 types it by its rules' code
            repeat(2) {
                engines.type(InputMethodCatalog.PINYIN, "nihao")
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 9).indexOfFirst { it.text == "你" })
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 9).indexOfFirst { it.text == "好" })
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            }
            assertEquals("你好", engines.type("engine-wubi", "wqvb").commit)
            assertEquals(listOf("你好"), engines.type("engine-wubi", "wqv").candidates)
            // blocked from 五笔 under wqv: there alone, still pinyin's word and the whole code's
            assertTrue(Offer.BLOCK in engines.offers("engine-wubi", 0))
            val afterBlock = engines.onEvent("engine-wubi", EngineEvent.BLOCK, 0)
            assertEquals(emptyList<String>(), afterBlock.candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertEquals(listOf(UserWord.Kind.LEARNED), engines.userWords().filter { it.text == "你好" }.map { it.kind })
            assertEquals("你好", engines.type("engine-wubi", "wqvb").commit)
            assertTrue(engines.blockWord("好", "hao"))
            assertEquals(emptyList<String>(), engines.type(InputMethodCatalog.PINYIN, "uzphzsh").candidates)
        }
    }

    @Test
    fun deletedInATableAWordStaysPinyinsAndTakenOutOfPinyinItStaysTheTables() {
        val dir = MemoryFiles()
        olderPhrases(dir, "vbwq" to "好你", "wqvb" to "你好", own = mapOf("好你" to "hao ni", "你好" to "ni hao"))
        fun Engines.pinyins(text: String) = userWords().filter { it.text == text && it.table.isEmpty() }
        Engines(::load, dir).use { engines ->
            engines.settings = wubiShown
            val at = engines.type("engine-wubi", "vbwq").candidates.indexOf("好你")
            assertTrue(Offer.FORGET in engines.offers("engine-wubi", at))
            engines.onEvent("engine-wubi", EngineEvent.FORGET, at)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // the table's row gone, pinyin's word kept, and blocked under this code so it shows here no more
            assertEquals(1, engines.pinyins("好你").size)
            assertTrue(engines.userWords().none { it.text == "好你" && it.table.isNotEmpty() })
            assertFalse("好你" in engines.type("engine-wubi", "vbwq").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // taken out of pinyin's words in the settings: the table's stays
            engines.removeWords(engines.pinyins("你好"))
        }
        Engines(::load, dir).use { engines ->
            engines.settings = wubiShown
            assertTrue("你好" in engines.type("engine-wubi", "wqvb").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.pinyins("你好").isEmpty())
        }
    }

    @Test
    fun aWordAddedOrTakenOutIsSoInTheTablesAtOnce() {
        Engines(::load, MemoryFiles()).use { engines ->
            engines.type("engine-wubi", "vbg")
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.addWord("好你", "hao ni"))
            assertEquals(listOf("好你"), engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.blockWord("好你", "hao ni"))
            assertFalse("好你" in engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            engines.removeWords(engines.userWords().filter { it.text == "好你" && it.kind == UserWord.Kind.BLOCKED })
            assertEquals(listOf("好你"), engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // the user model made anew: no copy of the old one's words stays
            engines.removeWords(engines.userWords().filter { it.text == "好你" })
            assertFalse("好你" in engines.type("engine-wubi", "vbw").candidates)
        }
    }

    // 你好 is pinyin's, not the table's: wqvb has the table's own word only, which commits itself, or that and [another]
    private fun knownToPinyin(another: Boolean = false): (String) -> ByteBuffer {
        val known = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f).unigram("你", -2f, 0f).unigram("好", -2.5f, 0f).unigram("你好", -3f, 0f)
            .entry("你", syl("ni")).entry("好", syl("hao")).entry("你好", syl("ni", "hao"))
            .build().toByteArray()
        val table = CodeTable.Builder()
            .header("键码", "abcdefghijklmnopqrstuvwxy").header("码长", "4").rule("e2", "p11+p12+p21+p22")
            .entry("wqiy", "你").entry("vbg", "好").entry("wqvb", "仰泳")
            .apply { if (another) entry("wqvb", "伛") }
            .build().toByteArray()
        return { path: String ->
            ByteBuffer.wrap(
                when (path) {
                    InputMethodCatalog.PINYIN_DATA -> known
                    "${InputMethodCatalog.TABLE_DIR}/wbx.data" -> table
                    else -> throw FileNotFoundException(path)
                },
            )
        }
    }

    // an undo in a session that cannot read the record anew (a table's) has it made anew: no stale list
    @Test
    fun anUndoInATableShowsItsListAnew() {
        Engines(knownToPinyin(another = true), MemoryFiles()).use { engines ->
            engines.type("engine-wubi", "vbg")
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.addWord("你好", "ni hao"))
            val at = engines.type("engine-wubi", "wqvb").candidates.indexOf("你好")
            val done = engines.learning("engine-wubi", LearningOp.Block(at, "你好")) as LearningResult.Done
            assertFalse("你好" in engines.candidates("engine-wubi", 0, 10).map { it.text })
            assertEquals(LearningResult.Done(), engines.learning("engine-wubi", LearningOp.Undo(done.undo)))
            // a table reads the record anew (stream C), the code typed kept: the app's AGAIN shows it back
            assertTrue("你好" in engines.onEvent("engine-wubi", EngineEvent.CHAR, Keyboard.AGAIN).candidates)
            engines.learning("engine-wubi", LearningOp.Block(at, "你好"))
            assertTrue("你好" in engines.type(InputMethodCatalog.PINYIN, "nihao").candidates)
        }
    }

    @Test
    fun aDictionaryWordAddedByHandIsTypedInTheTablesButWhereTheTablesWordIsAlone() {
        for (another in listOf(true, false)) {
            Engines(knownToPinyin(another), MemoryFiles()).use { engines ->
                engines.type("engine-wubi", "vbg")
                engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
                assertTrue(engines.addWord("你好", "ni hao"))
                val typed = engines.type("engine-wubi", "wqvb")
                if (another) {
                    assertEquals("", typed.commit)
                    assertEquals(setOf("仰泳", "伛"), typed.candidates.take(2).toSet())
                    assertEquals("你好", typed.candidates[2])
                } else {
                    assertEquals("仰泳", typed.commit)
                }
            }
        }
    }

    @Test
    fun aWordOfAnImportedDictionaryIsTypedInTheTablesThoughPinyinsDictionaryHasIt() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("你好 ni'hao 0") })
        Engines(knownToPinyin(another = true), MemoryFiles(), warmUps = { it.run() }, additions = { additions }).use { engines ->
            val typed = engines.type("engine-wubi", "wqvb")
            assertEquals("", typed.commit)
            assertEquals(setOf("仰泳", "伛"), typed.candidates.take(2).toSet())
            assertEquals("你好", typed.candidates[2])
        }
        // the table's word alone at the code: committed, as typed blind
        Engines(knownToPinyin(), MemoryFiles(), warmUps = { it.run() }, additions = { additions }).use { engines ->
            assertEquals("仰泳", engines.type("engine-wubi", "wqvb").commit)
        }
    }

    // a code with one table entry commits it, as typed blind
    @Test
    fun anImportedWordIsTypedInTheTablesAfterTheirOwnButWhereTheTablesWordIsAlone() {
        for (another in listOf(true, false)) {
            val table = CodeTable.Builder()
                .header("键码", "abcdefghijklmnopqrstuvwxy").header("码长", "4").rule("e2", "p11+p12+p21+p22")
                .entry("wqiy", "你").entry("vbg", "好").entry("vbwq", "妁")
                .apply { if (another) entry("vbwq", "妃") }
                .build().toByteArray()
            val load = { path: String ->
                ByteBuffer.wrap(if (path == "${InputMethodCatalog.TABLE_DIR}/wbx.data") table else knownToPinyin()(path).array())
            }
            Engines(load, MemoryFiles(), warmUps = { it.run() }).use { engines ->
                assertEquals(1, engines.importWords(sequenceOf("好你 hao'ni"), "a.txt").imported)
                val typed = engines.type("engine-wubi", "vbwq")
                if (another) {
                    assertEquals("", typed.commit)
                    assertEquals(setOf("妁", "妃"), typed.candidates.take(2).toSet())
                    assertEquals(listOf("好你"), typed.candidates.drop(2))
                } else {
                    assertEquals("妁", typed.commit)
                }
            }
        }
    }

    // pinyin's word shown in a table is deleted in pinyin: the table offers to block it, never to delete it
    @Test
    fun aSharedWordIsNeverForgottenInATable() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("你好 ni'hao 0") })
        Engines(knownToPinyin(another = true), MemoryFiles(), warmUps = { it.run() }, additions = { additions }).use { engines ->
            fun offers(): Set<Offer> {
                val i = engines.type("engine-wubi", "wqvb").candidates.indexOf("你好")
                assertTrue(i >= 0)
                return engines.offers("engine-wubi", i).also { engines.onEvent("engine-wubi", EngineEvent.RESET, 0) }
            }
            assertFalse(Offer.FORGET in offers())
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 9).indexOfFirst { it.text == "你好" })
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            // typed in pinyin, learned there: still nothing to delete in 五笔, blocking under its code is
            val now = offers()
            assertFalse(Offer.FORGET in now)
            assertTrue(Offer.BLOCK in now)
        }
    }
}
