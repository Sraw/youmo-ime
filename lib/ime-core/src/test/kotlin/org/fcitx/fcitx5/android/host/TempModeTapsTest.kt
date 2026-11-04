/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 14 keys' codes in a temporary mode (design k14-tap-position §1.4, review S1): the letter
 * they stand for, a tap's half's; no character of a private use area typed into the buffer.
 */
class TempModeTapsTest {

    private val rig = TempModeRig(QuickPhraseTable.load(emptyList()), { UnicodeInputTest::class.java.getResourceAsStream("/pkgdata/unicode/charselectdata")!!.readBytes() })

    private fun send(codePoint: Int) = rig.press(KeySyms.fromUnicode(codePoint))

    @Test
    fun quickInputTypesTheLetterOfTheHalfTappedAndTheCodesFirstLetter() {
        rig.press(TempKeys.GRAVE, KeyStates.SUPER)
        // BN tapped on its right half: n; BN with no place: b, its first (as the preedit shows it)
        send(Keyboard.tapKey(g14Code('b'), 0.75f))
        send(g14Code('b').code)
        // at the centre, the first letter
        send(Keyboard.tapKey(g14Code('q'), 0.5f))
        assertEquals("nbq", rig.preedit)
    }

    @Test
    fun quickInputTypesNoPrivateUseCharacter() {
        rig.press(TempKeys.GRAVE, KeyStates.SUPER)
        rig.type("a")
        send(Keyboard.letterKey('b', again = false))
        send(Keyboard.nineKey('2', upper = false))
        send(Keyboard.SYLLABLES.first.code)
        send(Keyboard.INK)
        // a stroke drawn while quick input shows its code (笔画筛选): no mode's, swallowed
        send(Keyboard.strokeKey('h'))
        // a letter typed as itself and the nine keys' digit are keys' (QuickPhraseKeys): typed as they read
        assertEquals("ab2", rig.preedit)
        assertEquals(emptyList<String>(), rig.commits())
    }

    @Test
    fun aRejectedCodeLeavesQuickInputUntouched() {
        // code review N5: a key quick input does not take (a syllable tapped in the strip) typed nothing, yet Return no longer committed alt
        rig.quickPhrase.trigger(rig.ic, str = "S", alt = "ALT", key = Key('k'.code))
        send(Keyboard.SYLLABLES.first.code)
        rig.press(KeySyms.RETURN)
        assertEquals(listOf("ALT"), rig.commits())
    }

    @Test
    fun unicodeTypesTheLettersAndTheirHexDigitsNow() {
        rig.press('u'.code, KeyStates.CTRL or KeyStates.SHIFT)
        // AS's a and DF's d are hex digits once letters: they typed nothing as codes
        send(Keyboard.tapKey(g14Code('a'), 0.25f))
        send(Keyboard.tapKey(g14Code('d'), 0.1f))
        send(g14Code('e').code)
        assertEquals("U+ade", rig.preedit)
    }

    @Test
    fun unicodeSearchTypesNoPrivateUseCharacter() {
        rig.press('u'.code, KeyStates.CTRL_ALT_SHIFT)
        rig.type("x")
        send(Keyboard.letterKey('b', again = false))
        send(Keyboard.tapKey(g14Code('g'), 0.9f))
        assertEquals("xh", rig.preedit)
    }
}
