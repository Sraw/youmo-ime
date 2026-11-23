/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.media.AudioManager
import android.view.MotionEvent
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.voice.VoiceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 语音输入时暂停播放 as the session sees it: the switch decides whether the recognizer gets the AudioManager to pause
 * others through (VoiceListener takes and gives back the focus), and another app taking it stops the talk.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceFocusUatTest : VoiceTapUat() {

    private var given: AudioManager? = null

    init {
        VoiceSession.listening = { events, audio ->
            given = audio
            voice.also { it.events = events }
        }
    }

    private val space get() = h.keyNamed("space")

    private class Row(val why: String, val on: Boolean)

    @Test
    @Uat("VO-57")
    fun theSwitchDecidesWhetherPlaybackIsPaused() {
        allowMicrophone()
        h.start()
        assertTrue("on by default", AppPrefs.getInstance().keyboard.voicePausePlayback.getValue())
        // read as each talk begins
        talks(
            Row("on, as it is by default: the focus taken through the app's AudioManager", true),
            Row("off: others go on playing, no focus asked for", false),
        )
    }

    private fun talks(vararg rows: Row) = assertRows(rows.asList(), { it.why }) { row ->
        val pref = AppPrefs.getInstance().keyboard.voicePausePlayback
        val was = pref.getValue()
        try {
            pref.setValue(row.on)
            given = null
            tapMic()
            assertTrue(talking)
            if (row.on) assertSame(h.service.getSystemService(AudioManager::class.java), given) else assertNull(given)
            tapMic()
            assertFalse(talking)
        } finally {
            pref.setValue(was)
        }
    }

    // a call ringing mid-talk: stopped with no tail (the ring), what was said typed; the lift after it types nothing again
    @Test
    @Uat("VO-57")
    fun heldTheFocusTakenStopsItAsIfLifted() {
        allowMicrophone()
        h.start()
        val f = h.finger(space, h.at(space)).also { h.advance(700) }
        assertEquals(1, voice.opened)
        voice.held = true
        voice.said += "今天天气不错"
        voice.interrupted()
        h.advance(100)
        assertEquals(1, voice.finishes)
        assertEquals("the ring not recorded", 1, voice.cuts)
        assertEquals("今天天气不错", text())
        assertEquals(1, voice.closes)
        f.up(h.at(space))
        h.advance(300)
        assertEquals("今天天气不错", text())
        assertEquals(1, voice.finishes)
    }

    private val up get() = h.keyboard.height / h.service.resources.displayMetrics.density * 0.4f

    // still being recognized (the recognizer holding it) when the finger slides up and lifts, or the call's screen takes the touch
    @Test
    @Uat("VO-57")
    fun heldTheFingerAfterTheFocusTakenDropsNothing() {
        allowMicrophone()
        h.start()
        fingers(
            "slid up past the cancel distance and lifted there" to { f ->
                f.move(h.at(space, dy = -up))
                assertFalse(shown().toString(), string(R.string.voice_hold_cancel) in shown())
                f.up(h.at(space, dy = -up))
            },
            "the touch taken away" to { f -> f.send(MotionEvent.ACTION_CANCEL, h.at(space)) },
        )
    }

    private fun fingers(vararg rows: Pair<String, (KeyboardHarness.Finger) -> Unit>) = assertRows(rows.asList(), { it.first }) { (_, finger) ->
        val before = text()
        val closes = voice.closes
        voice.held = true
        voice.stuck = true
        val f = h.finger(space, h.at(space)).also { h.advance(700) }
        voice.said += "今天天气不错"
        voice.interrupted()
        finger(f)
        assertEquals("not dropped", closes, voice.closes)
        voice.stuck = false
        voice.release()
        h.advance(100)
        assertEquals(before + "今天天气不错", text())
        h.advance(1000)
    }

    // the card saying 松开手指，取消输入: the ring ends it as the lift would, as another field does
    @Test
    @Uat("VO-57")
    fun heldInTheCancelZoneWhenTheFocusIsTakenCancels() {
        allowMicrophone()
        h.start()
        voice.held = true
        val f = h.finger(space, h.at(space)).also { h.advance(700) }
        f.move(h.at(space, dy = -up))
        assertTrue(shown().toString(), string(R.string.voice_hold_cancel) in shown())
        voice.said += "今天天气不错"
        voice.interrupted()
        h.advance(100)
        assertEquals(1, voice.closes)
        assertEquals(0, voice.finishes)
        assertFalse(shown().toString(), string(R.string.voice_hold_cancel) in shown())
        f.up(h.at(space, dy = -up))
        h.advance(100)
        assertEquals("", text())
    }

    @Test
    @Uat("VO-57")
    fun tappedTheFocusTakenStopsItAndTheMicrophoneStartsTheNext() {
        allowMicrophone()
        h.start()
        tapMic()
        voice.held = true
        voice.said += "我们走吧"
        voice.interrupted()
        h.advance(100)
        assertEquals("the ring not recorded", 1, voice.cuts)
        assertEquals("我们走吧", text())
        assertFalse(talking)
        assertFalse(shown().toString(), string(R.string.voice_hold_nothing) in shown())
        h.advance(1000)
        tapMic()
        assertTrue(talking)
        assertEquals(2, voice.opened)
    }

    // still being recognized: a tap is the tail's press, not a stop the second tap would be
    @Test
    @Uat("VO-57")
    fun tappedAfterTheFocusTakenOneTapEndsIt() {
        allowMicrophone()
        h.start()
        voice.held = true
        voice.stuck = true
        tapMic()
        voice.said += "我们走吧"
        voice.interrupted()
        h.advance(100)
        assertEquals(1, voice.cuts)
        tapMic()
        assertEquals(2, voice.cuts)
        voice.stuck = false
        voice.release()
        h.advance(100)
        assertEquals("我们走吧", text())
        assertFalse(talking)
    }
}
