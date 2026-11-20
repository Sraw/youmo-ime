/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** What [Engines] reads ahead of the first keys: pinyin's data as a field starts, English's as its session is made. */
class EnginesWarmUpTest : EnginesFixture() {

    // run when a test says, as a thread would run them some time later
    private val later = ArrayList<Runnable>()
    private val warmUps = Executor { later += it }

    // the steps' names of each warm-up handed so far (each counted for the idle signal: BackgroundWork)
    private fun handed() = later.map { handed ->
        val work = (handed as? BackgroundCount.Counted)?.work ?: handed
        (work as? WarmUp)?.names ?: throw AssertionError("not a warm-up's steps: $handed")
    }

    @Test
    fun pinyinsDataIsReadOffTheFcitxThreadOnceAsTheFirstFieldStarts() {
        val engines = Engines(::load, null, warmUps = warmUps)
        engines.context(InputMethodCatalog.T9, "")
        assertEquals(emptyList<String>(), loaded)
        assertEquals(1, later.size)
        // the nine keys' emoji and the parts' characters are read once a field starts, not at the first key
        engines.context(InputMethodCatalog.PINYIN, "你")
        assertEquals(2, later.size)
        engines.context(InputMethodCatalog.PINYIN, "")
        assertEquals(2, later.size)
        later.forEach { it.run() }
        assertEquals(listOf(InputMethodCatalog.PINYIN_DATA, InputMethodCatalog.EMOJI_DATA, InputMethodCatalog.CHAIZI_DATA), loaded)
        assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
    }

    /** The platform is handed a field's warm-ups as steps in order, on one thread: each reads what the one before it read. */
    @Test
    fun aFieldStartHandsThePlatformItsWarmUpAsStepsInOrder() {
        val engines = Engines(::load, null, warmUps = warmUps)
        engines.context(InputMethodCatalog.T9, "")
        engines.context(InputMethodCatalog.PINYIN, "")
        engines.context(InputMethodCatalog.WUBI, "")
        assertEquals(
            listOf(
                listOf(WarmUp.PINYIN_WORDS, WarmUp.LATIN_KEYS, WarmUp.CHAR_SYLLABLES, WarmUp.EMOJI),
                listOf(WarmUp.PINYIN_WORDS, WarmUp.LATIN_KEYS, WarmUp.CHAR_SYLLABLES, WarmUp.CHAIZI, WarmUp.CHAR_READINGS),
            ),
            handed(),
        )
        engines.type(InputMethodCatalog.ENGLISH, "y")
        assertEquals(listOf(WarmUp.ENGLISH), handed().last())
    }

    /** 中英混输's keys found before the first key: the nine keys' as a nine-key field starts, full pinyin's keyed keyboards' with it; once each. */
    @Test
    fun latinWordsKeysAreWarmedUpForTheKeyboardsAFieldMayType() {
        val engines = Engines(::load, null, warmUps = warmUps)
        engines.context(InputMethodCatalog.T9, "")
        engines.context(InputMethodCatalog.T9, "")
        engines.context(InputMethodCatalog.PINYIN, "")
        assertEquals(listOf(listOf(WarmUp.LATIN_KEYS)), handed().map { names -> names.filter { it == WarmUp.LATIN_KEYS } }.distinct())
        assertEquals(2, later.size)
        later.forEach { it.run() }
        val latin = engines.assets.latinWords
        for (keys in listOf(NineKeyLetters.STANDARD, NineKeyLetters.G14, NineKeyLetters.G18)) assertTrue(keys.tag(), latin.keyTrieBuilt(keys))
        // turned off: none
        later.clear()
        val off = Engines(::load, null, warmUps = warmUps).apply { settings = EngineSettings(latinWords = false) }
        off.context(InputMethodCatalog.T9, "")
        assertFalse(WarmUp.LATIN_KEYS in handed().single())
    }

    /** One who types only English neither waits for pinyin's data nor holds its word index; a table reads by them. */
    @Test
    fun aFieldStartedInEnglishReadsNoPinyinATableDoes() {
        val engines = Engines(::load, null, warmUps = warmUps)
        engines.context(InputMethodCatalog.ENGLISH, "")
        assertEquals(emptyList<Runnable>(), later)
        assertEquals(emptyList<String>(), loaded)
        // a table ranks and learns by pinyin's words too (TableLanguage)
        engines.context(InputMethodCatalog.WUBI, "")
        assertEquals(1, later.size)
        engines.context(InputMethodCatalog.SHUANGPIN, "")
        assertEquals(1, later.size)
    }

    /** What the settings turned off is not shown, so not read either. */
    @Test
    fun emojiAndCharactersByTheirPartsTurnedOffAreNotWarmedUp() {
        val engines = Engines(::load, null, warmUps = warmUps).apply { settings = EngineSettings(emoji = false, chaizi = false) }
        engines.context(InputMethodCatalog.PINYIN, "")
        later.single().run()
        assertEquals(listOf(InputMethodCatalog.PINYIN_DATA), loaded)
    }

    @Test
    fun englishIsWarmedUpOnceHoweverOftenItsSessionIsMade() {
        val engines = Engines(::load, null, warmUps = warmUps)
        engines.type(InputMethodCatalog.ENGLISH, "th")
        assertEquals(1, later.size)
        // a setting changed: the session made again, over English's data as read the first time
        engines.settings = EngineSettings(pageSize = 5)
        engines.type(InputMethodCatalog.ENGLISH, "th")
        assertEquals(1, later.size)
        later.single().run()
    }

    @Test
    fun aFieldStartedInFullPinyinWarmsEnglishUpOnlyForAWordLearned() {
        val none = Engines(::load, null, warmUps = warmUps)
        none.context(InputMethodCatalog.PINYIN, "", started = true)
        assertFalse(InputMethodCatalog.ENGLISH_DATA in loaded)
        val off = Engines(::load, null, warmUps = warmUps)
        assertTrue(off.addEnglishWord("youmo"))
        off.useHabits = false
        off.context(InputMethodCatalog.PINYIN, "", started = true)
        assertFalse(InputMethodCatalog.ENGLISH_DATA in loaded)
        later.clear()
        val learned = Engines(::load, null, warmUps = warmUps)
        assertTrue(learned.addEnglishWord("youmo"))
        learned.context(InputMethodCatalog.PINYIN, "", started = true)
        assertTrue(InputMethodCatalog.ENGLISH_DATA in loaded)
        // its index made off the fcitx thread, with pinyin's: however many tasks that takes
        assertTrue(later.isNotEmpty())
        later.forEach { it.run() }
        // English typed after reads nothing more: its index was made there
        val read = loaded.toList()
        assertTrue(learned.type(InputMethodCatalog.ENGLISH, "you").candidates.isNotEmpty())
        assertEquals(read, loaded)
    }

    /** Pinyin data that is not there, or that this build cannot read (a stale VERSION, say): a field starts all the same. */
    @Test
    fun pinyinDataThatCannotBeReadIsForPinyinsSessionToReport() {
        val missing = fieldStarted { if (it == InputMethodCatalog.PINYIN_DATA) throw FileNotFoundException(it) else load(it) }
        assertThrows(IOException::class.java) { missing.type(InputMethodCatalog.PINYIN, "n") }
        val stale = fieldStarted { if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(byteArrayOf(1, 2, 3)) else load(it) }
        assertThrows(DataFormatException::class.java) { stale.type(InputMethodCatalog.PINYIN, "n") }
    }

    /** Whatever else reading it throws, a bug say: on the fcitx thread, as pinyin's session is made, it goes to the addon to log. */
    @Test
    fun whateverReadingPinyinsDataThrowsIsForPinyinsSessionToThrow() {
        val broken = fieldStarted { if (it == InputMethodCatalog.PINYIN_DATA) throw IllegalStateException(it) else load(it) }
        assertThrows(IllegalStateException::class.java) { broken.type(InputMethodCatalog.PINYIN, "n") }
    }

    // what the warm-up cannot read must not escape its thread, which on Android ends the app
    private fun fieldStarted(assets: (String) -> ByteBuffer): Engines {
        val engines = Engines(assets, null, warmUps = inPlace)
        engines.context(InputMethodCatalog.PINYIN, "")
        engines.type(InputMethodCatalog.ENGLISH, "youmo")
        assertEquals("youmo ", engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code).commit)
        return engines
    }
}
