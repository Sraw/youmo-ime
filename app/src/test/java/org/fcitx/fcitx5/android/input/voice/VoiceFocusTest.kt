/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.media.AudioAttributes
import android.media.AudioManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Over Robolectric's AudioManager, which records the last request and abandon; another app's taking is its listener called. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class VoiceFocusTest {

    private val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
    private val shadow = shadowOf(audio)
    private var lost = 0
    private val focus = VoiceFocus(audio) { lost++ }

    private fun change(change: Int) = shadow.lastAudioFocusRequest.listener.onAudioFocusChange(change)

    @Test
    @Uat("VO-57")
    fun takenTransientForSpeechAndTheSameGivenBack() {
        focus.take()
        val taken = shadow.lastAudioFocusRequest.audioFocusRequest
        assertEquals("paused, not ducked: music made quieter is still heard", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, taken.focusGain)
        assertEquals(AudioAttributes.USAGE_ASSISTANT, taken.audioAttributes.usage)
        assertNull("held till the recording stops", shadow.lastAbandonedAudioFocusRequest)
        focus.giveBack()
        assertSame(taken, shadow.lastAbandonedAudioFocusRequest)
    }

    @Test
    @Uat("VO-57")
    fun whenNotToAskOrGiveBack() = assertRows(
        "a capture starting after its talk closed: not taken, else held for good" to {
            VoiceFocus(audio) {}.apply { giveBack() }.take()
            assertNull(shadow.lastAudioFocusRequest)
        },
        "refused (a call going on): nothing to give back, the recording goes on all the same" to {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            VoiceFocus(audio) {}.apply { take() }.giveBack()
            assertNull(shadow.lastAbandonedAudioFocusRequest)
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        },
    )

    private class Row(val why: String, val change: Int, val told: Boolean)

    @Test
    @Uat("VO-57")
    fun anotherAppTakingItIsTold() = assertRows(
        listOf(
            Row("a call ringing, an alarm: what comes next is not speech", AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true),
            Row("another app playing for good", AudioManager.AUDIOFOCUS_LOSS, true),
            Row("a notification or a navigation prompt ducking: we play nothing, the talk goes on", AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, false),
            Row("given back to us", AudioManager.AUDIOFOCUS_GAIN, false),
        ),
        { it.why },
    ) { row ->
        lost = 0
        VoiceFocus(audio) { lost++ }.take()
        change(row.change)
        assertEquals(if (row.told) 1 else 0, lost)
    }

    // a call ringing, then answered: the talk stops once
    @Test
    @Uat("VO-57")
    fun aTransientLossThenALossIsToldOnce() {
        focus.take()
        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        change(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, lost)
    }

    @Test
    @Uat("VO-57")
    fun aLossAfterGivingBackIsNotTold() {
        focus.take()
        focus.giveBack()
        change(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(0, lost)
    }
}
