/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.pinyin.nineSpCode
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.RecordedSession
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A 14-key tap with where it fell, [Keyboard.tapKey] (design k14-tap-position §1.3): one character, a code and a place. */
class KeyboardTapsTest {

    private fun recorded() = RecordedSession(PinyinSession(sessionTestData(), T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14), spell = true, prediction = false))

    private val codes = NineKeyLetters.G14_BLOCK.map { it.toChar() }

    @Test
    fun everyCodeAndPlaceComesBackAsTheKeyAndItsPlaceWhateverTheSession() {
        for (nineKeys in listOf(true, false)) {
            for (code in codes) {
                for (step in listOf(0, 8, 16)) {
                    val session = recorded()
                    Keyboard(session, nineKeys).onEvent(EngineEvent.CHAR, Keyboard.tapKey(code, step / 16f))
                    assertEquals("$code $step", Action.Key(code, at = step / 16f), session.actions.single())
                }
            }
        }
        val session = recorded()
        Keyboard(session, nineKeys = false).onEvent(EngineEvent.CHAR, Keyboard.tapKey(g14Code('b'), 0.75f))
        assertEquals(Action.Key(g14Code('b'), at = 0.75f), session.actions.single())
    }

    @Test
    fun aPlaceJustPastTheKeyIsItsEdgeNotTheNextCodesPlace() {
        assertEquals(Keyboard.tapKey(g14Code('b'), 0f), Keyboard.tapKey(g14Code('b'), -0.01f))
        assertEquals(Keyboard.tapKey(g14Code('b'), 1f), Keyboard.tapKey(g14Code('b'), 1.01f))
        assertTrue(Keyboard.tapKey(g14Code('b'), 1.01f) < Keyboard.tapKey(g14Code('c'), 0f))
        assertEquals(Keyboard.TAPS.first, Keyboard.tapKey(g14Code('a'), 0f))
        assertEquals(Keyboard.TAPS.last, Keyboard.tapKey(g14Code('z'), 1f))
    }

    @Test
    fun noPlaceAndNoCodeAreNoTap() {
        assertThrows(IllegalArgumentException::class.java) { Keyboard.tapKey(g14Code('b'), Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Keyboard.tapKey('b', 0.5f) }
    }

    @Test
    fun theTapsShareNoCharacterWithTheOtherKeys() {
        val others = listOf(Keyboard.LETTERS, Keyboard.NINE_DIGITS, Keyboard.NINE_LETTERS, Keyboard.INK..Keyboard.AGAIN)
        for (range in others) assertTrue(range.toString(), range.last < Keyboard.TAPS.first || range.first > Keyboard.TAPS.last)
        // past the BMP: no syllable, no code
        assertTrue(Keyboard.TAPS.first > Char.MAX_VALUE.code)
        assertTrue(Character.getType(Keyboard.TAPS.last) == Character.PRIVATE_USE.toInt())
    }

    @Test
    fun aTemporaryModeGetsTheLetterOfTheHalfTapped() {
        val rows = listOf(
            Keyboard.tapKey(g14Code('b'), 0.75f) to 'n',
            Keyboard.tapKey(g14Code('b'), 0.25f) to 'b',
            // the centre: the first letter, as the code alone
            Keyboard.tapKey(g14Code('b'), 0.5f) to 'b',
            Keyboard.tapKey(g14Code('q'), 1f) to 'w',
            g14Code('b').code to 'b',
            g14Code('u').code to 'u',
            // a key of one letter
            Keyboard.tapKey(g14Code('l'), 0.9f) to 'l',
            // the 18 keys' and the nine keys' 双拼's codes: their key's first letter (no taps of theirs yet)
            0xF8C1 to 'b',
            0xF8C8 to 'i',
            0xF8CB to 'l',
            nineSpCode('2').code to 'a',
            nineSpCode('5').code to 'j',
        )
        for ((codePoint, letter) in rows) assertEquals(Integer.toHexString(codePoint), letter, Keyboard.typedLetter(codePoint))
        // no grouped key's code (CV's v; U+F8C2 is no 18-key key's, U+F8FC past the blocks, ⒈ and ⒜ text), nor anything else
        for (codePoint in listOf(g14Code('v').code, 0xF8C2, 0xF8FC, '⒈'.code, 0x249C, 'b'.code, Keyboard.letterKey('b', again = false), Keyboard.INK)) {
            assertNull(Integer.toHexString(codePoint), Keyboard.typedLetter(codePoint))
        }
    }
}
