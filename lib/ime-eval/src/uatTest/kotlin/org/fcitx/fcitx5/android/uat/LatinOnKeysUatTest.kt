/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.junit.Assert.assertEquals
import org.junit.Test

/** uat/03-t9.md, uat/06-pinyin.md: 中英混输 on 全拼's nine, 14 and 18 keys, a word's letters typed as their keys. */
class LatinOnKeysUatTest {

    private val keyboards = listOf<Pair<String, (String) -> String>>(
        "nine keys" to { w -> EngineReplay.nineKeys(w.map(NineKeyLetters.STANDARD::key).joinToString("")) },
        "14 keys" to { w -> w.map(NineKeyLetters.G14::key).joinToString("") },
        "18 keys" to { w -> w.map { String(Character.toChars(Keyboard.pinyin18Key(NineKeyLetters.G18.key(it)))) }.joinToString("") },
    )

    @Test
    @Uat("T9-70", "PY-158", "PY-159")
    fun chineseAndEnglishMixedOnEveryKeyboard() = assertRows(keyboards, { it.first }) { (_, keys) ->
        with(EngineReplay()) {
            type(keys("wodeiphone"))
            assertEquals("我的iPhone", candidates.first())
            pick("我的iPhone")
            expectText("我的iPhone")
            focus("")
            type(keys("iphone"))
            expectPreedit("iphone")
            enter()
            expectText("iphone")
        }
        // a sentence with no Latin word: the first as with 中英混输 off, which offers no Latin word
        val sentence = "womenjintianquchifan"
        val off = { settings(EngineSettings(latinWords = false)) }
        with(EngineReplay(off())) {
            type(keys("iphone"))
            expectNotAmong("iPhone")
        }
        val first = with(EngineReplay(off())) { type(keys(sentence)); candidates.first() }
        with(EngineReplay()) {
            type(keys(sentence))
            assertEquals("我们今天去吃饭", candidates.first())
            assertEquals(first, candidates.first())
        }
    }
}
