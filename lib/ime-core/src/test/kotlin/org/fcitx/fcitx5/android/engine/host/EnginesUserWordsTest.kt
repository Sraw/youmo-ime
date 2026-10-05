/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

/** The words the user adds, makes and blocks, as the settings list them ([Engines.userWords]). */
class EnginesUserWordsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("拟", -3f, 0f)
        .unigram("好", -2.5f, 0f)
        .bigram("好", "拟", -0.5f, 0f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("好", syl("hao"))
        // a character of two readings
        .unigram("行", -2f, 0f)
        .unigram("航", -3f, 0f)
        .unigram("星", -3f, 0f)
        .entry("行", syl("xing"))
        .entry("行", syl("hang"))
        .entry("航", syl("hang"))
        .entry("星", syl("xing"))
        .build()
        .toByteArray()

    // no sentence models: the decoder's readings alone
    private fun load(path: String): ByteBuffer =
        if (path == Engines.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw java.io.FileNotFoundException(path)

    private fun Engines.type(im: String, keys: String) = keys.map { onEvent(im, EngineEvent.CHAR, it.code) }.last()

    @Test
    fun aWordBlockedFromTheKeyboardIsNeverOfferedAgainTillUnblocked() {
        val dir = folder.newFolder()
        val engines = Engines(::load, dir)
        val shown = engines.type(Engines.PINYIN, "ni").candidates
        assertEquals(listOf("你", "拟"), shown)
        assertTrue(Offer.BLOCK in engines.offers(Engines.PINYIN, 1))
        assertEquals(listOf("你"), engines.onEvent(Engines.PINYIN, EngineEvent.BLOCK, 1).candidates)
        // the session goes on: its candidates are still there to fetch
        assertEquals(listOf("你"), engines.candidates(Engines.PINYIN, 0, 10).map { it.text })
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        // nor predicted after 好, the model's word for it
        engines.type(Engines.PINYIN, "hao")
        assertEquals(emptyList<String>(), engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0).candidates)
        val blocked = Engines.UserWord("拟", "ni", Engines.UserWord.Kind.BLOCKED)
        assertEquals(listOf(blocked), engines.userWords())
        engines.close()
        // kept in the user directory
        val again = Engines(::load, dir)
        assertEquals(listOf("你"), again.type(Engines.PINYIN, "ni").candidates)
        again.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        again.removeWord(blocked)
        assertEquals(listOf("你", "拟"), again.type(Engines.PINYIN, "ni").candidates)
        assertEquals(emptyList<Engines.UserWord>(), again.userWords())
    }

    @Test
    fun aWordAddedInTheSettingsIsTypedTillRemoved() {
        val dir = folder.newFolder()
        val engines = Engines(::load, dir)
        assertFalse(engines.addWord("妮浩", "ni"))
        assertFalse(engines.addWord("妮浩", "nizz"))
        assertTrue(engines.addWord("妮浩", "nihao"))
        assertTrue("妮浩" in engines.type(Engines.PINYIN, "nihao").candidates)
        val added = Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED)
        assertEquals(listOf(added), engines.userWords())
        engines.close()
        val again = Engines(::load, dir)
        assertTrue("妮浩" in again.type(Engines.PINYIN, "nihao").candidates)
        again.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        again.removeWord(added)
        assertFalse("妮浩" in again.type(Engines.PINYIN, "nihao").candidates)
        assertEquals(emptyList<Engines.UserWord>(), again.userWords())
    }

    @Test
    fun aWordTheUserPutTogetherIsListedTillRemoved() {
        val engines = Engines(::load, folder.newFolder())
        // 拟 then 好, over the decoder's 你好: learned as the user's word 拟好
        engines.type(Engines.PINYIN, "nihao")
        val pieces = engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
        assertEquals("拟", pieces.preedit.substringBefore("hao").trim())
        engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0)
        val learned = engines.userWords().single()
        assertEquals("拟好" to Engines.UserWord.Kind.LEARNED, learned.text to learned.kind)
        assertEquals("ni hao", learned.pinyin)
        engines.removeWord(learned)
        assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
    }

    @Test
    fun aWordIsReadAsTheDictionaryReadsItsCharacters() {
        val engines = Engines(::load, null)
        assertEquals("ni hao", engines.pinyinOf("你好"))
        assertEquals("ni ni", engines.pinyinOf("拟你"))
        assertEquals(null, engines.pinyinOf("你x"))
        assertEquals(null, engines.pinyinOf(""))
    }

    @Test
    fun aWordIsBlockedInTheReadingItWasBlockedInOnly() {
        val engines = Engines(::load, folder.newFolder())
        assertEquals(listOf("行", "航"), engines.type(Engines.PINYIN, "hang").candidates)
        engines.onEvent(Engines.PINYIN, EngineEvent.BLOCK, 0)
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        assertEquals(listOf("航"), engines.type(Engines.PINYIN, "hang").candidates)
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        assertEquals(listOf("行", "星"), engines.type(Engines.PINYIN, "xing").candidates)
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        // added as hang: offered so again
        assertTrue(engines.addWord("行", "hang"))
        assertTrue("行" in engines.type(Engines.PINYIN, "hang").candidates)
    }
}
