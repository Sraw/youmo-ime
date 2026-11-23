/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.voice.VoiceCommit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTrackTest {

    private val track = FieldTrack()

    private fun field(id: Int, pkg: String = "app", type: Int = 1, hint: String? = null) = VoiceCommit.Field(pkg, id, type, hintText = hint)

    @Test
    fun theFirstFieldIsANewOne() {
        assertFalse(track.started(field(7), identical = false, restarting = false))
    }

    @Test
    fun theSameViewStartedAgainIsTheSameField() {
        track.started(field(7), identical = false, restarting = false)
        // restartInput's EditorInfo, or the field come back to from another app
        assertTrue(track.started(field(7), identical = false, restarting = false))
    }

    @Test
    fun anotherViewAppOrTraitIsAnotherField() {
        track.started(field(7), identical = false, restarting = false)
        assertFalse(track.started(field(8), identical = false, restarting = false))
        assertFalse(track.started(field(8, pkg = "other"), identical = false, restarting = false))
        assertFalse(track.started(field(8, pkg = "other", type = 0x21), identical = false, restarting = false))
        assertFalse(track.started(field(8, pkg = "other", type = 0x21, hint = "mail"), identical = false, restarting = false))
    }

    @Test
    @Uat("PAN-36")
    fun anUnsetIdTellsByTheEditorInfoItselfOrARestartAlike() {
        track.started(field(0), identical = false, restarting = false)
        assertFalse(track.started(field(0), identical = false, restarting = false))
        assertTrue(track.started(field(0), identical = true, restarting = false))
        track.started(field(-1), identical = false, restarting = false)
        assertFalse(track.started(field(-1), identical = false, restarting = false))
        // a WebView's restartInput
        assertTrue(track.started(field(-1), identical = false, restarting = true))
        assertFalse(track.started(field(-1, hint = "mail"), identical = false, restarting = true))
        assertFalse(track.started(field(-1, type = 2), identical = false, restarting = true))
    }

    @Test
    fun backToAFieldAfterAnotherIsANewStart() {
        track.started(field(7), identical = false, restarting = false)
        track.started(field(8), identical = false, restarting = false)
        assertFalse(track.started(field(7), identical = false, restarting = false))
    }
}
