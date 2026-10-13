/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard must stay on the input method fcitx last switched to: the system's echo of an
 * earlier switch is no pick of the user's. On the emulator, the startup switch to Pinyin and a
 * switch to English ran back to back, and the echo of the first put the keyboard back on Pinyin.
 */
class SubtypeEchoesTest {

    private var now = 0L
    private val echoes = SubtypeEchoes { now }

    @Test
    fun switchesToldBeforeTheirEchoesAreBackAreEachTakenForAnEcho() {
        echoes.shouldTell(PINYIN, had = ENGLISH)
        echoes.shouldTell(ENGLISH, had = PINYIN)

        assertTrue(echoes.isEcho(PINYIN))
        assertTrue(echoes.isEcho(ENGLISH))
        // the echoes are spent: the next is the user's
        assertFalse(echoes.isEcho(PINYIN))
    }

    /** As after the user's pick, or a sub-mode's change: the system calls back for neither. */
    @Test
    fun aSwitchToTheSubtypeTheSystemHasIsNotToldAndAwaitsNoEcho() {
        assertFalse(echoes.shouldTell(ENGLISH, had = ENGLISH))

        assertFalse(echoes.isEcho(ENGLISH))
    }

    @Test
    fun aSwitchWhoseEchoNeverCameIsForgottenWithTheNextEcho() {
        echoes.shouldTell(PINYIN, had = null)
        echoes.shouldTell(ENGLISH, had = PINYIN)

        assertTrue(echoes.isEcho(ENGLISH))
        assertFalse(echoes.isEcho(PINYIN))
    }

    @Test
    fun aMatchedEchoIsUsedUp() {
        echoes.shouldTell(ENGLISH, had = PINYIN)

        assertTrue(echoes.isEcho(ENGLISH))
        assertFalse(echoes.isEcho(ENGLISH))
    }

    /** Matched newest first, the first Pinyin echo would spend all three. */
    @Test
    fun echoesOfAnInputMethodToldTwiceAreMatchedOldestFirst() {
        echoes.shouldTell(PINYIN, had = ENGLISH)
        echoes.shouldTell(ENGLISH, had = PINYIN)
        echoes.shouldTell(PINYIN, had = ENGLISH)

        assertTrue(echoes.isEcho(PINYIN))
        assertTrue(echoes.isEcho(ENGLISH))
        assertTrue(echoes.isEcho(PINYIN))
        assertFalse(echoes.isEcho(PINYIN))
    }

    @Test
    fun theUsersPickOfAnotherInputMethodLeavesThePendingEchoForItsOwn() {
        echoes.shouldTell(PINYIN, had = ENGLISH)

        assertFalse(echoes.isEcho(SHUANGPIN))
        assertTrue(echoes.isEcho(PINYIN))
    }

    /** The pick changed the system's subtype already: told again, it would await an echo that never comes. */
    @Test
    fun theSwitchTheUsersPickMadeIsNotTold() {
        echoes.shouldTell(PINYIN, had = ENGLISH)
        // the user picks Shuangpin before Pinyin's echo is back
        assertFalse(echoes.isEcho(SHUANGPIN))

        assertFalse(echoes.shouldTell(SHUANGPIN, had = SHUANGPIN))
        // nothing awaited for it: picked again, it is the user's pick again
        assertFalse(echoes.isEcho(SHUANGPIN))
    }

    /** Pinyin is pending, but the system has the user's English: compared with the pending switch, it would stay on English. */
    @Test
    fun aSwitchAwayFromTheUsersPickIsToldThoughTheSameIsPending() {
        echoes.shouldTell(PINYIN, had = ENGLISH)
        // the user picks English before Pinyin's echo is back
        assertFalse(echoes.isEcho(ENGLISH))

        assertTrue(echoes.shouldTell(PINYIN, had = ENGLISH))
    }

    /** As for a switch fcitx made after the keyboard was disabled: the system throws instead of switching. */
    @Test
    fun aSwitchTheSystemRefusedAwaitsNoEchoWhileTheOnesToldBeforeItStillDo() {
        echoes.shouldTell(PINYIN, had = ENGLISH)
        echoes.shouldTell(ENGLISH, had = PINYIN)
        echoes.shouldTell(PINYIN, had = ENGLISH)
        echoes.refused(PINYIN)

        assertTrue(echoes.isEcho(PINYIN))
        assertTrue(echoes.isEcho(ENGLISH))
        // the user's pick: the refused switch's echo is not awaited
        assertFalse(echoes.isEcho(PINYIN))
    }

    /** Refused with nothing told, as no caller does: nothing is awaited, and nothing throws. */
    @Test
    fun aRefusalWithNoSwitchToldForgetsNothing() {
        echoes.refused(PINYIN)
        assertFalse(echoes.isEcho(PINYIN))
    }

    @Test
    fun anEchoNotBackWithinTenSecondsIsForgotten() {
        echoes.shouldTell(PINYIN, had = ENGLISH)
        now = 1
        echoes.shouldTell(ENGLISH, had = PINYIN)
        now = 10_001

        // the user's pick: Pinyin's echo, 10 001 ms on, is overdue
        assertFalse(echoes.isEcho(PINYIN))
        // exactly ten seconds behind a stalled main thread, still an echo
        assertTrue(echoes.isEcho(ENGLISH))
    }

    @Test
    fun theOldestOfMoreThanSixteenPendingIsDropped() {
        val told = List(17) { "table-$it" }
        told.forEach { echoes.shouldTell(it, had = ENGLISH) }

        assertFalse(echoes.isEcho(told.first()))
        told.drop(1).forEach { assertTrue(it, echoes.isEcho(it)) }
    }

    private companion object {
        const val PINYIN = "engine-pinyin"
        const val SHUANGPIN = "engine-shuangpin"
        const val ENGLISH = "keyboard-us"
    }
}
