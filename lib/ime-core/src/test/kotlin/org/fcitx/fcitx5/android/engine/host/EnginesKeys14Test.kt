/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinyin on the 14 keys through [Engines]: apart from EnginesTest, which is as large as a class may be. */
class EnginesKeys14Test : EnginesFixture() {

    // design §1.2, §1.3: the 14 keys' codes go to a session of their own, which the context reaches, and back
    @Test
    fun theFourteenKeysTypeInPinyinAfterTheTextBeforeAndHandBackToFullPinyin() {
        assertTrue(Engines.readsAsKey(Engines.PINYIN, EngineSettings().scheme, g14Code('b').code))
        assertFalse(Engines.readsAsKey(Engines.PINYIN, EngineSettings().scheme, 'b'.code))
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        engines.context(Engines.PINYIN, "好")
        val ni = NineKeyLetters.G14.typings("ni").single()
        assertEquals(listOf("拟", "你"), engines.type(Engines.PINYIN, ni).candidates)
        assertEquals(listOf("拟", "你"), engines.candidates(Engines.PINYIN, 0, 10).map { it.text })
        // a hardware keyboard's letter: what the 14 keys typed kept as typed, full pinyin after the same text
        val n = engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, 'n'.code)
        assertEquals("ni", n.commit)
        // review S2: full pinyin, reset as the 14 keys wrote, ranks by no old text: 好 is not before it now
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "i").candidates)
    }

    // HK-03, review S8 (S): 联想 after a 14 keys' commit: the first Escape closes them, the second is the app's
    @Test
    fun anEscapeClosesThePredictionsOfTheFourteenKeysFirst() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        engines.type(Engines.PINYIN, NineKeyLetters.G14.typings("hao").single())
        val after = engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code)
        assertTrue(after.toString(), after.predicting && after.candidates.isNotEmpty())
        val first = engines.onEvent(Engines.PINYIN, EngineEvent.ESCAPE, 0)
        assertTrue(first.handled)
        assertTrue(first.candidates.isEmpty())
        assertFalse(engines.onEvent(Engines.PINYIN, EngineEvent.ESCAPE, 0).handled)
    }
}
