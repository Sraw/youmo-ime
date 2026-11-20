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

/** A grouped keyboard is named in several tables side by side; one left out swallows its codes silently. */
class KeyedKeyboardsTest {

    // a layout's own keyboard's codes and taps too (pinyin's own-keys session)
    private val codes = (NineKeyLetters.CODES + Keyboard.TAPS + Keyboard.TAPS_18 + Keyboard.KEYED_CODES + NineKeyLetters.CUSTOM_BLOCK + Keyboard.PLACES).toSortedSet()

    @Test
    fun aCodeAnInputMethodReadsGoesToOneOfItsKeyedSessionsAndNoOtherCode() {
        for (c in codes) {
            val hex = Integer.toHexString(c)
            assertEquals(hex, InputMethodCatalog.readsCode(InputMethodCatalog.PINYIN, c), KeyboardPair.pinyinKeyed(c) != null)
            assertEquals(hex, InputMethodCatalog.readsCode(InputMethodCatalog.SHUANGPIN, c), KeyboardPair.shuangpinKeyed(c) != null)
            assertTrue(hex, !(KeyboardPair.pinyinKeyed(c) != null && KeyboardPair.shuangpinKeyed(c) != null))
        }
    }

    @Test
    fun everyKeyedSessionIsReachedAndFiltersByStrokesAsItsInputMethodDoes() {
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
            if (c in Keyboard.SHUANGPIN_14) {
                assertEquals(Integer.toHexString(c), NineKeyLetters.G14, KeyboardPair.lettersOf(name))
            } else {
                assertEquals(Integer.toHexString(c), NineKeyLetters.ofCode(c), KeyboardPair.lettersOf(name))
            }
        }
    }
}
