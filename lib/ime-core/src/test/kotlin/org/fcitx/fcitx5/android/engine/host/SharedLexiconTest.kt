/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
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
            // put together in pinyin, 你好 is the user's word; 五笔 types it by its rules' code
            engines.type(Engines.PINYIN, "nihao")
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 9).indexOfFirst { it.text == "你" })
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 9).indexOfFirst { it.text == "好" })
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            // its code's only candidate, it commits itself
            assertEquals("你好", engines.type("engine-wubi", "wqvb").commit)
            assertEquals(listOf("你好"), engines.type("engine-wubi", "wqv").candidates)
            // blocked from 五笔, pinyin does not offer it either, nor the table
            assertTrue(Offer.BLOCK in engines.offers("engine-wubi", 0))
            val afterBlock = engines.onEvent("engine-wubi", EngineEvent.BLOCK, 0)
            assertEquals(emptyList<String>(), afterBlock.candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // pinyin's word: blocked, as a long press there would (its pieces still make it a sentence)
            assertEquals(listOf(Engines.UserWord.Kind.BLOCKED), engines.userWords().filter { it.text == "你好" }.map { it.kind })
            // and a character blocked is not found by its strokes
            assertTrue(engines.blockWord("好", "hao"))
            assertEquals(emptyList<String>(), engines.type(Engines.PINYIN, "uzphzsh").candidates)
        }
    }

    @Test
    fun aPhraseATableSavesIsPinyinsWordToo() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            // typed character by character three times, 好你 is 五笔's phrase, and pinyin's word
            repeat(3) {
                engines.type("engine-wubi", "vbg ")
                engines.type("engine-wubi", "wqiy")
                engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            }
            assertTrue("好你" in engines.type(Engines.PINYIN, "haoni").candidates)
            assertTrue(engines.userWords().any { it.text == "好你" && it.kind == Engines.UserWord.Kind.LEARNED })
        }
        // saved before pinyin was told, at the next start it is told
        assertTrue(dir.resolve(Engines.USER_PINYIN).delete())
        assertTrue(dir.resolve(Engines.userTable("engine-wubi") + Engines.TOLD).delete())
        Engines(::load, dir).use { engines ->
            engines.type("engine-wubi", "vbgw")
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue("好你" in engines.type(Engines.PINYIN, "haoni").candidates)
            assertTrue(engines.userWords().any { it.text == "好你" && it.kind == Engines.UserWord.Kind.LEARNED })
        }
        // told once: gone from pinyin since, it is not put back
        assertTrue(dir.resolve(Engines.USER_PINYIN).delete())
        Engines(::load, dir).use { engines ->
            engines.type("engine-wubi", "vbgw")
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.userWords().none { it.text == "好你" })
        }
    }

    private fun Engines.saveInWubi() = repeat(3) {
        type("engine-wubi", "vbg ")
        type("engine-wubi", "wqiy")
        onEvent("engine-wubi", EngineEvent.RESET, 0)
    }

    @Test
    fun forgottenInATableAPhraseIsForgottenByPinyinTooAndTakenOutOfPinyinItIsNotBack() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            engines.saveInWubi()
            assertEquals(listOf("好你"), engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.FORGET, 0)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.userWords().none { it.text == "好你" })
            assertFalse("好你" in engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // saved again, then taken out of pinyin's words in the settings: out of the table too
            engines.saveInWubi()
            engines.removeWords(engines.userWords().filter { it.text == "好你" })
        }
        Engines(::load, dir).use { engines ->
            assertFalse("好你" in engines.type("engine-wubi", "vbw").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.userWords().none { it.text == "好你" })
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
    // dictionary's words at a code): its code wqvb has the table's own word only, which would
    // commit itself. A word the user adds or imports is typable there all the same (UAT TBL-12;
    // the owner, 2026-10-19: 码表也需要导入词库)
    private fun knownToPinyin(): (String) -> ByteBuffer {
        val known = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f).unigram("你", -2f, 0f).unigram("好", -2.5f, 0f).unigram("你好", -3f, 0f)
            .entry("你", syl("ni")).entry("好", syl("hao")).entry("你好", syl("ni", "hao"))
            .build().toByteArray()
        val table = CodeTable.Builder()
            .header("键码", "abcdefghijklmnopqrstuvwxy").header("码长", "4").rule("e2", "p11+p12+p21+p22")
            .entry("wqiy", "你").entry("vbg", "好").entry("wqvb", "仰泳")
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

    @Test
    fun aDictionaryWordAddedByHandIsTypedInTheTables() {
        val load = knownToPinyin()
        Engines(load, folder.newFolder("engine")).use { engines ->
            engines.type("engine-wubi", "vbg")
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue(engines.addWord("你好", "ni hao"))
            val typed = engines.type("engine-wubi", "wqvb")
            assertEquals("", typed.commit)
            assertTrue("你好" in typed.candidates)
        }
    }

    @Test
    fun aWordOfAnImportedDictionaryIsTypedInTheTablesThoughPinyinsDictionaryHasIt() {
        val additions = Engines.Additions("", "d") { sequenceOf("你好 ni'hao 0") }
        Engines(knownToPinyin(), folder.newFolder("engine"), warmUps = { it.run() }, additions = { additions }).use { engines ->
            val typed = engines.type("engine-wubi", "wqvb")
            assertEquals("", typed.commit)
            // after the table's own
            assertEquals(listOf("仰泳", "你好"), typed.candidates)
        }
    }

    // F2442: what the real SharedWords.learned lets a table offer to forget, as the owner decided
    @Test
    fun aSharedWordIsForgettableInATableOnlyOnceTypedOrMade() {
        val additions = Engines.Additions("", "d") { sequenceOf("你好 ni'hao 0") }
        Engines(knownToPinyin(), folder.newFolder("engine"), warmUps = { it.run() }, additions = { additions }).use { engines ->
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
