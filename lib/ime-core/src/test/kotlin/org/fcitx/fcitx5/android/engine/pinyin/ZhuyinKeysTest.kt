/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.reading.Zhuyin
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.KeyboardPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhuyinKeysTest {

    @Test
    fun everySymbolASyllableUsesIsOnBothKeyboards() {
        assertEquals(Zhuyin.symbols.toSet(), ZhuyinKeys.SYMBOLS.toSet())
        assertEquals(ZhuyinKeys.SYMBOLS.toSet(), ZhuyinKeys.GROUP_SYMBOLS.joinToString("").toSet())
        assertEquals(ZhuyinKeys.SYMBOLS.length, ZhuyinKeys.GROUP_SYMBOLS.sumOf { it.length })
        for (c in ZhuyinKeys.SYMBOLS) {
            assertEquals(1, ZhuyinKeys.DACHEN.keysOf(c).length)
            assertEquals(1, ZhuyinKeys.NINE.keysOf(c).length)
        }
    }

    // the gestures' presets name 大千's keys by the computer keyboard's (design 051 §1.3)
    @Test
    fun dachenByQwertyHasEverySymbolAndTheFourTones() {
        val keys = ZhuyinKeys.DACHEN_QWERTY
        assertEquals(41, keys.size)
        assertEquals(ZhuyinKeys.SYMBOLS.toSet() + "ˇˋˊ˙".toSet(), keys.values.toSet())
        assertEquals('ㄅ', keys['1'])
        assertEquals('ㄆ', keys['q'])
        assertEquals('ㄇ', keys['a'])
        assertEquals('ㄈ', keys['z'])
        assertEquals('ㄦ', keys['-'])
        assertEquals('ㄤ', keys[';'])
        assertEquals('ㄥ', keys['/'])
    }

    @Test
    fun theKeyboardsCodesReachTheirSessionsAsTheBlocksCharacters() {
        for (c in ZhuyinKeys.SYMBOLS) {
            assertEquals(ZhuyinKeys.symbolCode(c), Keyboard.keyedChar(Keyboard.zhuyinKey(c)))
            assertEquals(ZhuyinKeys.symbolCode(c), Keyboard.keyedChar(Keyboard.zhuyinNineSymbol(c)))
            assertEquals(c, ZhuyinKeys.symbolOf(ZhuyinKeys.symbolCode(c)))
        }
        for (g in 0 until ZhuyinKeys.GROUPS) assertEquals(ZhuyinKeys.groupCode(g), Keyboard.keyedChar(Keyboard.zhuyinNineKey(g)))
        assertEquals(SyllableGraph.SEPARATOR, Keyboard.keyedChar(Keyboard.zhuyinNineTone(0)))
        for (t in 1..ZhuyinKeys.TONES) {
            assertEquals(t, ZhuyinKeys.toneOf(Keyboard.keyedChar(Keyboard.zhuyinNineTone(t))!!))
            assertEquals(t, ZhuyinKeys.toneOf(Keyboard.keyedChar(Keyboard.zhuyinTone(t))!!))
        }
        assertEquals(0, ZhuyinKeys.toneOf('\''))
        assertNull(ZhuyinKeys.symbolOf('ㄅ'))
    }

    @Test
    fun theBlockIsApartFromEveryOtherCode() {
        for (c in ZhuyinKeys.BLOCK) {
            assertFalse(c in Keyboard.SYLLABLES)
            assertFalse(c.code in NineKeyLetters.CODES)
            assertNull(NineKeyLetters.ofCode(c.code))
        }
        val codes = Keyboard.ZHUYIN_NINE.toList() + Keyboard.ZHUYIN_FULL.toList()
        assertEquals(codes.size, codes.toSet().size)
        for (c in codes) {
            assertTrue(c in Keyboard.KEYED_CODES)
            assertTrue(InputMethodCatalog.isCode(c))
            assertTrue(InputMethodCatalog.readsCode(InputMethodCatalog.PINYIN, c))
            assertFalse(InputMethodCatalog.readsCode(InputMethodCatalog.SHUANGPIN, c))
        }
        assertEquals(InputMethodCatalog.ZHUYIN_NINE, KeyboardPair.pinyinKeyed(Keyboard.zhuyinNineKey(0)))
        assertEquals(InputMethodCatalog.ZHUYIN, KeyboardPair.pinyinKeyed(Keyboard.zhuyinKey('ㄅ')))
        // a ㄅ as text (the symbols page) is no key of theirs
        assertNull(KeyboardPair.pinyinKeyed('ㄅ'.code))
        assertFalse(InputMethodCatalog.isCode('ㄅ'.code))
    }
}
