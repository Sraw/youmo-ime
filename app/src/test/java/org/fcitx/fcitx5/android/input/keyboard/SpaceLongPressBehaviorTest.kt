/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holding the space talks only where the user chose it, and never
 * in a password field: what is said there would be heard by whoever is near.
 */
class SpaceLongPressBehaviorTest {

    @Test
    fun holdingTheSpaceTalksOutsideAPasswordField() {
        assertTrue(SpaceLongPressBehavior.Voice.talks(inPasswordField = false))
    }

    @Test
    fun inAPasswordFieldItDoesNot() {
        assertFalse(SpaceLongPressBehavior.Voice.talks(inPasswordField = true))
    }

    @Test
    fun noOtherBehaviourTalks() {
        SpaceLongPressBehavior.entries.filter { it != SpaceLongPressBehavior.Voice }.forEach {
            assertFalse(it.name, it.talks(inPasswordField = false))
        }
    }
}
