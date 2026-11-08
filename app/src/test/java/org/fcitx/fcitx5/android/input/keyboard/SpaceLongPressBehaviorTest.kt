/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holding the space talks only where the user chose it and the voice pack is imported, and never
 * in a password field: what is said there would be heard by whoever is near.
 */
class SpaceLongPressBehaviorTest {

    @Test
    fun holdingTheSpaceTalksOutsideAPasswordField() {
        assertTrue(SpaceLongPressBehavior.Voice.talks(voiceReady = true, inPasswordField = false))
    }

    @Test
    fun inAPasswordFieldItDoesNot() {
        assertFalse(SpaceLongPressBehavior.Voice.talks(voiceReady = true, inPasswordField = true))
    }

    @Test
    fun withoutTheVoicePackItNeverTalks() {
        assertFalse(SpaceLongPressBehavior.Voice.talks(voiceReady = false, inPasswordField = false))
    }

    @Test
    fun noOtherBehaviourTalks() {
        SpaceLongPressBehavior.entries.filter { it != SpaceLongPressBehavior.Voice }.forEach {
            assertFalse(it.name, it.talks(voiceReady = true, inPasswordField = false))
        }
    }
}
