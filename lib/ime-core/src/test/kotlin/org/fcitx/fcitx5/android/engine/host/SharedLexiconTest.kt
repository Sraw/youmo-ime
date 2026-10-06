/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
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

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

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

    private fun Engines.type(im: String, keys: String) = keys.map { onEvent(im, EngineEvent.CHAR, it.code) }.last()

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
        dir.resolve(Engines.USER_PINYIN).delete()
        Engines(::load, dir).use { engines ->
            engines.type("engine-wubi", "vbgw")
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertTrue("好你" in engines.type(Engines.PINYIN, "haoni").candidates)
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

}
