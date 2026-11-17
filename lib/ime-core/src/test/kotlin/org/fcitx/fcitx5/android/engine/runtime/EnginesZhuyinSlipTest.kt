/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 注音九键's taps with their place, as the host's key carries them: read through the engine, as the setting says (061-neighbour-18key). */
class EnginesZhuyinSlipTest : EnginesFixture() {

    // since the user's 11-16, over -2: [ㄅㄉㄚ][ㄖㄥㄩ][ㄈㄌㄝ] spells nothing; with ㄅㄉㄚ tapped at its very right edge,
    // toward ㄍㄐㄞ, it reads ㄐㄩㄝ, 角 (a slip where the keys spell something as typed costs -2 more, past the
    // floor); in its middle, or with 纠错 off, not
    @Test
    @Uat("PY-151")
    fun zhuyinsNineKeysReadASlipUnlessSlipsAreOff() {
        val engines = Engines(::load, null)
        val keys = listOf(0, 10, 9).map(ZhuyinKeys::groupCode)
        fun tapped(dx: Float): List<String> {
            keys.forEachIndexed { i, c ->
                val touch = TouchBoxes.tap(TouchBoxes.ZHUYIN_NINE, c, dx = if (i == 0) dx else 0f)
                engines.onEvent(InputMethodCatalog.ZHUYIN_NINE, EngineEvent.CHAR, c.code, payload = Action.Key(c, touch = touch))
            }
            return engines.candidates(InputMethodCatalog.ZHUYIN_NINE, 0, 20).map { it.text }.also { engines.onEvent(InputMethodCatalog.ZHUYIN_NINE, EngineEvent.RESET, 0) }
        }
        assertEquals("角", tapped(0.49f).first())
        assertFalse("角" in tapped(0f))
        engines.settings = EngineSettings(typos = false)
        assertFalse("角" in tapped(0.49f))
    }
}
