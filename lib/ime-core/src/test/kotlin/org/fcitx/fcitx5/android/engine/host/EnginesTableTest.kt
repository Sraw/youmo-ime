/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** What may follow (联想) in a table and in handwriting, as [Engines] wires it to the one lexicon and the tables. */
class EnginesTableTest {

    @get:Rule
    val folder = TemporaryFolder()

    // 你好 follows 好: offered after it
    private val pinyin = pinyinData(nin = false)

    // 你 read nin too, likelier, for 你好 to be put together at a reading of its own (of equal
    // weight, 你 picked from ninhao would read ni, the arc found first)
    private fun pinyinData(nin: Boolean): ByteArray = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("好", -2.5f, 0f)
        .unigram("你好", -3f, 0f)
        .bigram("好", "你好", -0.5f, 0f)
        .entry("你", syl("ni"), weight = if (nin) -1f else 0f)
        .apply { if (nin) entry("你", syl("nin")) }
        .entry("好", syl("hao"))
        .entry("你好", syl("ni", "hao"))
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

    private fun load(path: String): ByteBuffer = load(path, pinyin)

    private fun load(path: String, pinyin: ByteArray): ByteBuffer = ByteBuffer.wrap(
        when (path) {
            Engines.PINYIN_DATA -> pinyin
            "${Engines.TABLE_DIR}/wbx.data" -> wubi
            else -> throw FileNotFoundException(path)
        },
    )

    // a prediction blocked in 五笔 is blocked in the lexicon, for pinyin too
    @Test
    fun aTablesPredictionIsBlockedInTheLexicon() {
        Engines(::load, folder.newFolder()).use { engines ->
            val after = engines.type("engine-wubi", "vbg ")
            assertEquals("好", after.commit)
            val i = after.candidates.indexOf("你好")
            assertTrue(i >= 0)
            assertTrue(Offer.BLOCK in engines.offers("engine-wubi", i))
            engines.onEvent("engine-wubi", EngineEvent.BLOCK, i)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertEquals(listOf(Engines.UserWord.Kind.BLOCKED), engines.userWords().filter { it.text == "你好" }.map { it.kind })
        }
    }

    // a table's own prediction forgotten is forgotten by the tables too: 五笔's phrase 你好 goes
    @Test
    fun aTablesPredictionForgottenGoesFromTheTables() {
        Engines(::load, folder.newFolder()).use { engines ->
            repeat(3) {
                engines.type("engine-wubi", "wqiy")
                engines.type("engine-wubi", "vbg ")
                engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            }
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            // picked: learned, to be forgotten
            val picked = engines.type("engine-wubi", "vbg ").candidates.indexOf("你好")
            assertEquals("你好", engines.onEvent("engine-wubi", EngineEvent.PICK, picked).commit)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            val i = engines.type("engine-wubi", "vbg ").candidates.indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers("engine-wubi", i))
            engines.onEvent("engine-wubi", EngineEvent.FORGET, i)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            assertFalse("你好" in engines.type("engine-wubi", "wqv").candidates)
        }
    }

    // a prediction forgotten in handwriting is forgotten by the tables too: 五笔's phrase 你好 goes
    @Test
    fun handwritingForgetsAPredictionInTheTables() {
        Engines(::load, folder.newFolder()).use { engines ->
            // typed character by character three times, 你好 is 五笔's phrase
            repeat(3) {
                engines.type("engine-wubi", "wqiy")
                engines.type("engine-wubi", "vbg ")
                engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            }
            assertTrue("你好" in engines.type("engine-wubi", "wqv").candidates)
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            fun afterHao(): List<String> {
                engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), engines.inkEnded + 1)
                engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
                return engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates
            }
            // picked: learned, to be forgotten
            assertEquals("你好", engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, afterHao().indexOf("你好")).commit)
            val i = afterHao().indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers(Engines.HANDWRITING, i))
            engines.onEvent(Engines.HANDWRITING, EngineEvent.FORGET, i)
            engines.onEvent(Engines.HANDWRITING, EngineEvent.RESET, 0)
            assertFalse("你好" in engines.type("engine-wubi", "wqv").candidates)
        }
    }

    // in pinyin only the reading predicted goes: the 你好 the user put together at nin hao stays.
    // Apart from the test above, as a word of the user's the tables code too (五笔 would offer it)
    @Test
    fun handwritingForgetsAPredictionAtItsReadingOnly() {
        val ninPinyin = pinyinData(nin = true)
        Engines({ load(it, ninPinyin) }, folder.newFolder()).use { engines ->
            engines.type(Engines.PINYIN, "ninhao")
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "你" })
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0)
            fun ninHao() = engines.userWords(Engines.UserWord.Kind.LEARNED).any { it.text == "你好" && it.pinyin == "nin hao" }
            assertTrue(ninHao())
            fun afterHao(): List<String> {
                engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), engines.inkEnded + 1)
                engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
                return engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates
            }
            assertEquals("你好", engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, afterHao().indexOf("你好")).commit)
            val i = afterHao().indexOf("你好")
            assertTrue(Offer.FORGET in engines.offers(Engines.HANDWRITING, i))
            engines.onEvent(Engines.HANDWRITING, EngineEvent.FORGET, i)
            engines.onEvent(Engines.HANDWRITING, EngineEvent.RESET, 0)
            assertTrue(ninHao())
        }
    }
}
