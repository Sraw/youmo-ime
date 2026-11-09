/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What each keyboard's key is in a quick phrase's key (design 051-quickphrase §1.4). */
class QuickPhraseKeysTest {

    private fun of(codePoint: Int, nine: NineKeyLetters = NineKeyLetters.STANDARD) = QuickPhraseKeys.of(codePoint, nine)!!

    private fun chars(k: QuickPhraseKey) = k.can.map { it.toChar() }.sorted().joinToString("")

    @Test
    fun theNineKeysDigitIsItsLettersAndItself() {
        val four = of(Keyboard.pinyinNineKey('4'))
        assertEquals('4'.code, four.shown)
        assertEquals("4GHIghi", chars(four))
        // the user's letters, English's nine keys the same
        val mine = NineKeyLetters.parse("abc,def,gh,ijkl,mno,pqrs,tuv,wxyz")
        assertEquals("4GHgh", chars(of(Keyboard.pinyinNineKey('4'), mine)))
        assertEquals("4GHgh", chars(of(Keyboard.nineKey('4', upper = true), mine)))
        // 0 and 1 have no letters: the digit
        assertEquals("0", chars(of(Keyboard.pinyinNineKey('0'))))
        // 九键双拼's: fixed letters, 5 JKL alone; 微软's ; is 1's
        val sp = of(NineKeyLetters.NINE_SP.codes[3].code)
        assertEquals('5'.code, sp.shown)
        assertEquals("5JKLjkl", chars(sp))
        assertEquals('\''.code, of(NineKeyLetters.NINE_SP_SEMICOLON.code).shown)
        assertEquals(setOf('\''.code, ';'.code), of(NineKeyLetters.NINE_SP_SEMICOLON.code).can)
    }

    @Test
    fun aGroupedKeyIsItsWholeKeyShownAsTheHalfTapped() {
        val tapped = of(Keyboard.tapKey(g14Code('g'), 0.9f))
        assertEquals('h'.code, tapped.shown)
        assertEquals("GHgh", chars(tapped))
        assertEquals("GHgh", chars(of(g14Code('g').code)))
        // 18 keys: FG
        assertEquals("FGfg", chars(of(NineKeyLetters.G18.codes[9].code)))
        assertEquals("FGfg", chars(of(Keyboard.pinyin18Key(NineKeyLetters.G18.codes[9]))))
    }

    @Test
    fun lettersAsThemselvesAndPlainCharacters() {
        assertEquals("b", chars(of(Keyboard.letterKey('b', again = true))))
        assertEquals("B", chars(of(Keyboard.nineLetter('B'))))
        assertEquals(":", chars(of(':'.code)))
        assertNull(QuickPhraseKeys.of(Keyboard.SYLLABLES.first.code, NineKeyLetters.STANDARD))
        assertNull(QuickPhraseKeys.of(Keyboard.INK, NineKeyLetters.STANDARD))
    }
}
