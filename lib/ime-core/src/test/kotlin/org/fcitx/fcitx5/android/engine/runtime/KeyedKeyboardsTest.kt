/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A grouped keyboard is named in several tables side by side (which input method reads its codes,
 * which session they go to, which filter by strokes, which letters it has): one left out of one
 * of them and its codes are swallowed without a word. These hold them to each other.
 */
class KeyedKeyboardsTest {

    private val codes = (NineKeyLetters.CODES + Keyboard.TAPS + Keyboard.KEYED_CODES).toSortedSet()

    @Test
    fun aCodeAnInputMethodReadsGoesToOneOfItsKeyedSessionsAndNoOtherCode() {
        for (c in codes) {
            val hex = Integer.toHexString(c)
            assertEquals(hex, InputMethodCatalog.readsCode(InputMethodCatalog.PINYIN, c), KeyboardPair.pinyinKeyed(c) != null)
            assertEquals(hex, InputMethodCatalog.readsCode(InputMethodCatalog.SHUANGPIN, c), KeyboardPair.shuangpinKeyed(c) != null)
            // a code is one keyboard's alone
            assertTrue(hex, !(KeyboardPair.pinyinKeyed(c) != null && KeyboardPair.shuangpinKeyed(c) != null))
        }
    }

    @Test
    fun everyKeyedSessionIsReachedAndFiltersByStrokesAsItsInputMethodDoes() {
        // 连按's letters go to the nine keys' session too
        val reached = (codes + Keyboard.LETTERS).flatMap { listOfNotNull(KeyboardPair.pinyinKeyed(it), KeyboardPair.shuangpinKeyed(it)) }.toSet()
        assertEquals(KeyboardPair.KEYED, reached)
        // strokes filter Chinese by its readings alone: each keyed session as its input method, English and a table's codes not
        for (name in KeyboardPair.KEYED + InputMethodCatalog.PINYIN + InputMethodCatalog.SHUANGPIN) {
            assertTrue(name, InputMethodCatalog.filtersByStrokes(name))
        }
        for (name in listOf(InputMethodCatalog.ENGLISH, InputMethodCatalog.WUBI, InputMethodCatalog.WUBI_PINYIN)) {
            assertFalse(name, InputMethodCatalog.filtersByStrokes(name))
        }
    }

    @Test
    fun doubleSpellingsGroupedCodesAreOfTheLettersItsSessionIsMadeWith() {
        for (c in codes) {
            val name = KeyboardPair.shuangpinKeyed(c) ?: continue
            // the 14 keys' codes are Keyboard's own, their letters G14's
            if (c in Keyboard.SHUANGPIN_14) {
                assertEquals(Integer.toHexString(c), NineKeyLetters.G14, KeyboardPair.lettersOf(name))
            } else {
                assertEquals(Integer.toHexString(c), NineKeyLetters.ofCode(c), KeyboardPair.lettersOf(name))
            }
        }
    }
}
