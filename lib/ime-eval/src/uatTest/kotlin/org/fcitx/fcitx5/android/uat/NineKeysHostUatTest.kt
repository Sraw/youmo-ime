/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * uat/03's nine keys, the host's half: what the keys T9UatTest sees sent (the keys' codes, the
 * keypad's digits, a mark, a reset) make of the field, on the Kotlin host with the app's data:
 * pinyin on its nine keys (I1), the keypad's digits typed or picking as on any of pinyin's keyboards.
 */
class NineKeysHostUatTest {

    private fun HostReplay.keypad(digits: String) {
        digits.forEach { key(KeySyms.KP_0 + (it - '0')) }
        pause()
    }

    @Test
    @Uat("T9-18", "T9-22")
    fun theNumberPadsDigitsAreTypedOrPick() = with(HostReplay(im = InputMethodCatalog.PINYIN)) {
        keypad("23")
        expectText("23")
        expectPreedit("")
        backspace(2)
        expectText("")
        pinyinNine("64")
        val first = candidates.first()
        // 1 picks the first, as the full keyboard's: no digit
        keypad("1")
        expectText(first)
        expectPreedit("")
    }

    @Test
    @Uat("T9-22")
    fun aSwipedDigitPicksItsCandidate() = with(HostReplay(im = InputMethodCatalog.PINYIN)) {
        pinyinNine("64")
        val second = candidates[1]
        keypad("2")
        expectText(second)
        expectPreedit("")
    }

    @Test
    @Uat("T9-23")
    fun resetPutsTheFirstCandidateIn() = with(HostReplay(im = InputMethodCatalog.PINYIN)) {
        pinyinNine("64")
        val first = candidates.first()
        // a long-pressed digit's commitOverEngine starts so: what is typed resolved, then fcitx's reset
        assertEquals(PendingInputPolicy.Resolution.SelectFirstCandidate, commitAndReset())
        pause()
        expectText(first)
        expectPreedit("")
    }

    @Test
    @Uat("T9-27")
    fun aMarkWhileTypingCommitsTheCandidateThenTheChineseMark() = with(HostReplay(im = InputMethodCatalog.PINYIN)) {
        pinyinNine("64")
        val first = candidates.first()
        type("!")
        expectText("$first！")
        expectPreedit("")
    }
}
