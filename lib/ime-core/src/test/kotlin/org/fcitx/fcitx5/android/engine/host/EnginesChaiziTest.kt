/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.host.Engines.Companion.PINYIN
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
