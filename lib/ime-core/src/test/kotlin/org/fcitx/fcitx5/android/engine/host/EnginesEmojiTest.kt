/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.host.Engines.Companion.PINYIN
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.WUBI
import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.junit.Assert.assertEquals
import org.junit.Test

/** The emoji candidates as [Engines] wires them: pinyin's and the tables', one setting, what the phone draws. */
class EnginesEmojiTest : EnginesFixture() {

    init {
        emoji = EmojiData.format(emptyList(), mapOf("好" to listOf("👌", "👍"))).toByteArray()
    }

    @Test
    fun pinyinAndTheTablesOfferAWordsEmojiTillTurnedOff() {
        val engines = Engines(::load, null, warmUps = inPlace)
        assertEquals(listOf("好", "👌", "👍"), engines.type(PINYIN, "hao").candidates)
        engines.settings = wubiShown
        assertEquals(listOf("好", "妤", "👌", "👍"), engines.type(WUBI, "vbg").candidates)
        engines.settings = wubiShown.copy(emoji = false)
        assertEquals(listOf("好", "妤"), engines.type(WUBI, "vbg").candidates)
        assertEquals(listOf("好"), engines.type(PINYIN, "hao").candidates)
        assertEquals(EngineSettings(emoji = false), EngineSettings.parse("${EngineSettings.EMOJI}=False"))
    }

    @Test
    fun noneThePhoneCannotDrawNorAnyWithoutTheData() {
        val drawn = Engines(::load, null, warmUps = inPlace, emojiShows = { it != "👌" })
        assertEquals(listOf("好", "👍"), drawn.type(PINYIN, "hao").candidates)
        emoji = null
        val errors = ArrayList<Exception>()
        val none = Engines(::load, null, onError = { errors += it }, warmUps = inPlace)
        assertEquals(listOf("好"), none.type(PINYIN, "hao").candidates)
        assertEquals(1, errors.size)
    }
}
