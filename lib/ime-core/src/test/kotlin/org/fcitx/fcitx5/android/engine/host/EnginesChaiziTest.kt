/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.host.Engines.Companion.PINYIN
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.WUBI
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The characters by their parts as [Engines] wires them: full pinyin's, one setting, what the phone draws. */
class EnginesChaiziTest : EnginesFixture() {

    init {
        chaizi = Chaizi.format(mapOf("nvzi" to listOf("好", "妤"))).toByteArray()
    }

    @Test
    fun fullPinyinOffersThemAfterUTillTurnedOff() {
        val engines = Engines(::load, null, warmUps = inPlace)
        val s = engines.type(PINYIN, "unvzi")
        assertEquals(listOf("好", "妤"), s.candidates.take(2))
        engines.settings = EngineSettings(chaizi = false)
        assertFalse("妤" in engines.type(PINYIN, "unvzi").candidates)
        assertEquals(EngineSettings(chaizi = false), EngineSettings.parse("${EngineSettings.CHAIZI}=False"))
        assertEquals(EngineSettings(builtInPhrases = false), EngineSettings.parse("${EngineSettings.BUILT_IN_PHRASES}=False"))
    }

    /** The built-in date and time phrases as the setting has them, full pinyin's: sj the time, none turned off. */
    @Test
    fun theBuiltInPhrasesFollowTheirSetting() {
        val engines = Engines(::load, null, warmUps = inPlace)
        val time = Regex("\\d{1,2}:\\d{2}")
        assertTrue(engines.type(PINYIN, "sj").candidates.any(time::matches))
        engines.settings = EngineSettings(builtInPhrases = false)
        assertFalse(engines.type(PINYIN, "sj").candidates.any(time::matches))
    }

    /** A table's word of them brings them as the setting has it: 日期 its dates. */
    @Test
    fun aTablesWordOfTheBuiltInPhrasesFollowsTheSetting() {
        val engines = Engines(::load, null, warmUps = inPlace)
        val date = Regex("\\d{4}-\\d{2}-\\d{2}")
        engines.settings = wubiShown
        val on = engines.type(WUBI, "jjad").candidates
        assertEquals("日期", on.first())
        assertTrue(on.any(date::matches))
        engines.settings = wubiShown.copy(builtInPhrases = false)
        assertEquals(listOf("日期"), engines.type(WUBI, "jjad").candidates)
    }

    @Test
    fun noneThePhoneCannotDrawNorAnyWithoutTheData() {
        val drawn = Engines(::load, null, warmUps = inPlace, textShows = { it != "妤" })
        assertFalse("妤" in drawn.type(PINYIN, "unvzi").candidates)
        chaizi = null
        val errors = ArrayList<Exception>()
        val none = Engines(::load, null, onError = { errors += it }, warmUps = inPlace)
        assertFalse("妤" in none.type(PINYIN, "unvzi").candidates)
        assertEquals(1, errors.size)
    }
}
