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
        assertTrue(hold.release())
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
    fun onARoundButtonOutOfItWhicheverWayCancels() {
        val round = VoiceHold(cancelDistance = 50f, around = true)
        assertFalse(round.moved(30f, -30f))
        assertTrue(round.moved(60f, 0f))
        assertEquals(VoiceHold.Zone.Cancel, round.zone)
        assertTrue(round.moved(0f, 40f))
        assertTrue(round.moved(0f, -51f))
        assertFalse(round.release())
    }
}
