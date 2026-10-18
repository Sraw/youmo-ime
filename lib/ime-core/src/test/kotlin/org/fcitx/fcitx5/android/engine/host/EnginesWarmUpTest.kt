/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * What [Engines] reads ahead of the first keys, handed to its warm-ups: pinyin's data and word
 * index as a field starts ([Engines.context]), English's keys and word index as its session is
 * first made.
 */
class EnginesWarmUpTest : EnginesFixture() {

    // run when a test says, as a thread would run them some time later
    private val later = ArrayList<Runnable>()
    private val warmUps = Executor { later += it }

    @Test
    fun pinyinsDataIsReadOffTheFcitxThreadOnceAsTheFirstFieldStarts() {
        val engines = Engines(::load, null, warmUps = warmUps)
        engines.context(Engines.T9, "")
        // nothing read on the fcitx thread
        assertEquals(emptyList<String>(), loaded)
        assertEquals(1, later.size)
        // and what is placed beside its candidates, not at the first key: the nine keys' emoji, and
        // full pinyin's characters by their parts once a field starts there, the data not read again
        engines.context(Engines.PINYIN, "你")
        assertEquals(2, later.size)
        engines.context(Engines.PINYIN, "")
        assertEquals(2, later.size)
        later.forEach { it.run() }
        assertEquals(listOf(Engines.PINYIN_DATA, Engines.EMOJI_DATA, Engines.CHAIZI_DATA), loaded)
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
    }

    /** One who types only English neither waits for pinyin's data nor holds its word index; a table reads by them. */
    @Test
    fun aFieldStartedInEnglishReadsNoPinyinATableDoes() {
        val engines = Engines(::load, null, warmUps = warmUps)
        engines.context(Engines.ENGLISH, "")
        assertEquals(emptyList<Runnable>(), later)
        // nor read on the fcitx thread instead
        assertEquals(emptyList<String>(), loaded)
        // a table ranks and learns by pinyin's words too (TableLanguage)
        engines.context(Engines.WUBI, "")
        assertEquals(1, later.size)
        engines.context(Engines.SHUANGPIN, "")
        assertEquals(1, later.size)
    }

    /** Handwriting reads its sentences by pinyin's words: warmed up for as a pinyin is, off the fcitx thread; it shows no emoji, nor characters by their parts. */
    @Test
    fun aFieldStartedInHandwritingWarmsPinyinUp() {
        val engines = Engines(::load, null, warmUps = warmUps)
        engines.context(Engines.HANDWRITING, "")
        assertEquals(emptyList<String>(), loaded)
        assertEquals(1, later.size)
        later.single().run()
        assertEquals(listOf(Engines.PINYIN_DATA), loaded)
    }

    /** What the settings turned off is not shown, so not read either. */
    @Test
    fun emojiAndCharactersByTheirPartsTurnedOffAreNotWarmedUp() {
        val engines = Engines(::load, null, warmUps = warmUps).apply { settings = EngineSettings(emoji = false, chaizi = false) }
        engines.context(Engines.PINYIN, "")
        later.single().run()
        assertEquals(listOf(Engines.PINYIN_DATA), loaded)
    }

    @Test
    fun englishIsWarmedUpOnceHoweverOftenItsSessionIsMade() {
        val engines = Engines(::load, null, warmUps = warmUps)
        engines.type(Engines.ENGLISH, "th")
        assertEquals(1, later.size)
        // a setting changed: the session made again, over English's data as read the first time
        engines.settings = EngineSettings(pageSize = 5)
        engines.type(Engines.ENGLISH, "th")
        assertEquals(1, later.size)
        later.single().run()
    }

    /** Pinyin data that is not there, or that this build cannot read (a stale VERSION, say): a field starts all the same. */
    @Test
    fun pinyinDataThatCannotBeReadIsForPinyinsSessionToReport() {
        val missing = fieldStarted { if (it == Engines.PINYIN_DATA) throw FileNotFoundException(it) else load(it) }
        assertThrows(IOException::class.java) { missing.type(Engines.PINYIN, "n") }
        val stale = fieldStarted { if (it == Engines.PINYIN_DATA) ByteBuffer.wrap(byteArrayOf(1, 2, 3)) else load(it) }
        assertThrows(DataFormatException::class.java) { stale.type(Engines.PINYIN, "n") }
    }

    /** Whatever else reading it throws, a bug say: on the fcitx thread, as pinyin's session is made, it goes to the addon to log. */
    @Test
    fun whateverReadingPinyinsDataThrowsIsForPinyinsSessionToThrow() {
        val broken = fieldStarted { if (it == Engines.PINYIN_DATA) throw IllegalStateException(it) else load(it) }
        assertThrows(IllegalStateException::class.java) { broken.type(Engines.PINYIN, "n") }
    }

    // Engines over [assets] once a field started and English was typed in it, the warm-up run as it
    // is handed over: what it cannot read must not escape its thread, which on Android ends the app
    private fun fieldStarted(assets: (String) -> ByteBuffer): Engines {
        val engines = Engines(assets, null, warmUps = inPlace)
        engines.context(Engines.PINYIN, "")
        engines.type(Engines.ENGLISH, "youmo")
        assertEquals("youmo ", engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code).commit)
        return engines
    }
}
