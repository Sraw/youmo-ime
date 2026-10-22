/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** One user lexicon for pinyin, the tables and the stroke lookup. */
class SharedLexiconTest {

    @get:Rule
    val folder = TemporaryFolder()

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
            Engines.PINYIN_DATA -> pinyin
            "${Engines.TABLE_DIR}/wbx.data" -> wubi
            Engines.STROKE_DATA -> strokes
            else -> throw FileNotFoundException(path)
        },
    )

    @Test
    fun oneUserLexiconServesPinyinAndTheTables() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            // put together in pinyin twice, 你好 is the user's word (U-06); 五笔 types it by its rules' code
            repeat(2) {
                engines.type(Engines.PINYIN, "nihao")
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 9).indexOfFirst { it.text == "你" })
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 9).indexOfFirst { it.text == "好" })
                engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            }
            // its code's only candidate, it commits itself
            assertEquals("你好", engines.type("engine-wubi", "wqvb").commit)
            assertEquals(listOf("你好"), engines.type("engine-wubi", "wqv").candidates)
            // blocked from 五笔 under wqv: there alone (U-28), still pinyin's word and the whole code's
            assertTrue(Offer.BLOCK in engines.offers("engine-wubi", 0))
            val afterBlock = engines.onEvent("engine-wubi", EngineEvent.BLOCK, 0)
            assertEquals(emptyList<String>(), afterBlock.candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertEquals(listOf(Engines.UserWord.Kind.LEARNED), engines.userWords().filter { it.text == "你好" }.map { it.kind })
            assertEquals("你好", engines.type("engine-wubi", "wqvb").commit)
            // and a character blocked is not found by its strokes
            assertTrue(engines.blockWord("好", "hao"))
            assertEquals(emptyList<String>(), engines.type(Engines.PINYIN, "uzphzsh").candidates)
        }
    }

    @Test
    fun aPhraseATableSavedIsPinyinsWordToo() {
        val dir = folder.newFolder("engine")
        olderPhrases(dir, "vbwq" to "好你")
        // told as the table comes up
        Engines(::load, dir).use { engines ->
            assertEquals(listOf("好你"), engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue("好你" in engines.type(Engines.PINYIN, "haoni").candidates)
            assertTrue(engines.userWords().any { it.text == "好你" && it.kind == Engines.UserWord.Kind.LEARNED })
        }
        // told once: gone from pinyin since, it is not put back
        assertTrue(dir.resolve(LearnLog.FILE).delete())
        Engines(::load, dir).use { engines ->
            engines.type("engine-wubi", "vbgw")
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.userWords().none { it.text == "好你" })
        }
    }

    @Test
    fun forgottenInATableAPhraseIsForgottenByPinyinTooAndTakenOutOfPinyinItIsNotBack() {
        val dir = folder.newFolder("engine")
        olderPhrases(dir, "vbwq" to "好你", "wqvb" to "你好")
        Engines(::load, dir).use { engines ->
            assertEquals(listOf("好你"), engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.FORGET, 0)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.userWords().none { it.text == "好你" })
            assertFalse("好你" in engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // taken out of pinyin's words in the settings: out of the table too
            assertEquals(listOf("你好"), engines.type("engine-wubi", "wqv").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            engines.removeWords(engines.userWords().filter { it.text == "你好" })
        }
        Engines(::load, dir).use { engines ->
            for ((code, text) in listOf("vbw" to "好你", "wqv" to "你好")) {
                assertFalse(text in engines.type("engine-wubi", code).candidates)
                engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
                assertTrue(engines.userWords().none { it.text == text })
            }
        }
    }

    @Test
    fun aWordAddedOrTakenOutIsSoInTheTablesAtOnce() {
        Engines(::load, folder.newFolder("engine")).use { engines ->
            // the table typed with before
            engines.type("engine-wubi", "vbg")
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.addWord("好你", "hao ni"))
            assertEquals(listOf("好你"), engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // blocked and offered again
            assertTrue(engines.blockWord("好你", "hao ni"))
            assertFalse("好你" in engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            engines.removeWords(engines.userWords().filter { it.text == "好你" && it.kind == Engines.UserWord.Kind.BLOCKED })
            assertEquals(listOf("好你"), engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // the user model made anew: no copy of the old one's words stays
            engines.removeWords(engines.userWords().filter { it.text == "好你" })
            assertFalse("好你" in engines.type("engine-wubi", "vbw").candidates)
        }
    }

    // 你好 a word of pinyin's dictionary, not the table's (a table keeps only the commoner of the
    // dictionary's words at a code): its code wqvb has the table's own word only, which commits
    // itself, or that and [another] too. A word the user adds or imports is typable there all the
    // same (UAT TBL-12; the owner, 2026-10-19: 码表也需要导入词库), but where the table's word is
    // alone: one who types it blind keeps it (U-28 D8)
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
                    Engines.PINYIN_DATA -> known
                    "${Engines.TABLE_DIR}/wbx.data" -> table
                    else -> throw FileNotFoundException(path)
                },
            )
        }
    }

    // an undo in a session that cannot read the record anew (a table's) has it made anew: no stale list (review-E-engine S5)
    @Test
    fun anUndoInATableShowsItsListAnew() {
        Engines(knownToPinyin(another = true), folder.newFolder("engine")).use { engines ->
            engines.type("engine-wubi", "vbg")
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.addWord("你好", "ni hao"))
            val at = engines.type("engine-wubi", "wqvb").candidates.indexOf("你好")
            val done = engines.learning("engine-wubi", LearningOp.Block(at, "你好")) as LearningResult.Done
            assertFalse("你好" in engines.candidates("engine-wubi", 0, 10).map { it.text })
            assertEquals(LearningResult.Done(), engines.learning("engine-wubi", LearningOp.Undo(done.undo)))
            // a table reads the record anew (stream C), the code typed kept: the app's AGAIN shows it back
            assertTrue("你好" in engines.onEvent("engine-wubi", EngineEvent.CHAR, Keyboard.AGAIN).candidates)
            // blocked under that code alone (U-28): pinyin had it all along
            engines.learning("engine-wubi", LearningOp.Block(at, "你好"))
            assertTrue("你好" in engines.type(Engines.PINYIN, "nihao").candidates)
        }
    }

    @Test
    fun aDictionaryWordAddedByHandIsTypedInTheTablesButWhereTheTablesWordIsAlone() {
        for (another in listOf(true, false)) {
            Engines(knownToPinyin(another), folder.newFolder()).use { engines ->
                engines.type("engine-wubi", "vbg")
                engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
                assertTrue(engines.addWord("你好", "ni hao"))
                val typed = engines.type("engine-wubi", "wqvb")
                if (another) {
                    assertEquals("", typed.commit)
                    // after the table's own, which the word model places
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
        val additions = Engines.Additions("", "d") { sequenceOf("你好 ni'hao 0") }
        Engines(knownToPinyin(another = true), folder.newFolder("engine"), warmUps = { it.run() }, additions = { additions }).use { engines ->
            val typed = engines.type("engine-wubi", "wqvb")
            assertEquals("", typed.commit)
            // after the table's own, which the word model places
            assertEquals(setOf("仰泳", "伛"), typed.candidates.take(2).toSet())
            assertEquals("你好", typed.candidates[2])
        }
        // the table's word alone at the code: committed, as typed blind
        Engines(knownToPinyin(), folder.newFolder(), warmUps = { it.run() }, additions = { additions }).use { engines ->
            assertEquals("仰泳", engines.type("engine-wubi", "wqvb").commit)
        }
    }

    // TBL-12a, D8: a word of another input method's list, imported (U-101), is typed in the tables
    // as a dictionary's is, after their own; a code the table has one entry for commits it, as typed blind
    @Test
    fun anImportedWordIsTypedInTheTablesAfterTheirOwnButWhereTheTablesWordIsAlone() {
        for (another in listOf(true, false)) {
            val table = CodeTable.Builder()
                .header("键码", "abcdefghijklmnopqrstuvwxy").header("码长", "4").rule("e2", "p11+p12+p21+p22")
                .entry("wqiy", "你").entry("vbg", "好").entry("vbwq", "妁")
                .apply { if (another) entry("vbwq", "妃") }
                .build().toByteArray()
            val load = { path: String ->
                ByteBuffer.wrap(if (path == "${Engines.TABLE_DIR}/wbx.data") table else knownToPinyin()(path).array())
            }
            Engines(load, folder.newFolder(), warmUps = { it.run() }).use { engines ->
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

    // F2442: what the real SharedWords.learned lets a table offer to forget, as the owner decided
    @Test
    fun aSharedWordIsForgettableInATableOnlyOnceTypedOrMade() {
        val additions = Engines.Additions("", "d") { sequenceOf("你好 ni'hao 0") }
        Engines(knownToPinyin(another = true), folder.newFolder("engine"), warmUps = { it.run() }, additions = { additions }).use { engines ->
            fun forgettable(code: String = "wqvb", text: String = "你好"): Boolean {
                val i = engines.type("engine-wubi", code).candidates.indexOf(text)
                assertTrue(i >= 0)
                val offered = Offer.FORGET in engines.offers("engine-wubi", i)
                engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
                return offered
            }
            // imported, never typed: nothing learned to forget
            assertFalse(forgettable())
            // typed once in pinyin: learned
            engines.type(Engines.PINYIN, "nihao")
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 9).indexOfFirst { it.text == "你好" })
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertTrue(forgettable())
            // forgotten there: no longer
            val i = engines.type("engine-wubi", "wqvb").candidates.indexOf("你好")
            engines.onEvent("engine-wubi", EngineEvent.FORGET, i)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertFalse(forgettable())
            // added by hand, never typed: as pinyin offers it (PY-33, something learned to forget)
            assertTrue(engines.addWord("好你", "hao ni"))
            val inPinyin = engines.type(Engines.PINYIN, "haoni").candidates.indexOf("好你").also { assertTrue(it >= 0) }
                .let { Offer.FORGET in engines.offers(Engines.PINYIN, it) }
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertEquals(inPinyin, forgettable("vbw", "好你"))
        }
    }
}
