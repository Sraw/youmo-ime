/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHoldTest {

    private val hold = VoiceHold(cancelDistance = 100f)

    @Test
    fun liftedWhereItWentDownTypes() {
        hold.heard("今天天气不错，")
        hold.heard("我们出去走走。")
        assertTrue(hold.release())
        assertEquals("今天天气不错，我们出去走走。", hold.text)
    }

    @Test
    fun slidUpFarEnoughDrops() {
        assertFalse(hold.moved(0f, -50f))
        assertEquals(VoiceHold.Zone.Talk, hold.zone)
        assertTrue(hold.moved(0f, -100f))
        assertEquals(VoiceHold.Zone.Cancel, hold.zone)
        assertFalse(hold.release())
    }

    @Test
    fun slidBackDownTypesAgain() {
        hold.moved(0f, -150f)
        assertTrue(hold.moved(0f, -20f))
        assertTrue(hold.release())
    }

    @Test
    fun aMoveOnceLiftedChangesNothing() {
        hold.release()
        assertFalse(hold.moved(0f, -500f))
        assertEquals(VoiceHold.Zone.Talk, hold.zone)
    }

    @Test
    fun stretchesCleanedAndJoined() {
        hold.heard("嗯")
        hold.heard(" 订了 C E P 的票， ")
        hold.heard("hello")
        hold.heard("world")
        assertEquals("订了CEP的票，hello world", hold.text)
    }

    @Test
    fun nothingHeardIsNothingTyped() {
        hold.heard("")
        assertEquals("", hold.text)
    }

    @Test
    fun `on a round button, out of it whichever way cancels`() {
        val round = VoiceHold(cancelDistance = 50f, around = true)
        assertFalse(round.moved(30f, -30f))
        assertTrue(round.moved(60f, 0f))
        assertEquals(VoiceHold.Zone.Cancel, round.zone)
        assertTrue(round.moved(0f, 40f))
        assertTrue(round.moved(0f, -51f))
        assertFalse(round.release())
    }
}
