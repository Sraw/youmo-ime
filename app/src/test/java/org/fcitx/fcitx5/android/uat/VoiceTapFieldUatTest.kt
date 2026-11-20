/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.voice.VoiceTap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tapped talk that ends otherwise than by a stop; after each, the microphone closed once. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceTapFieldUatTest : VoiceTapUat() {

    @Test
    @Uat("VO-30")
    fun anotherFieldGetsNothingTheFirstGetsWhatWasSaid() {
        allowMicrophone()
        h.start()
        val first = h.field
        tapMic()
        voice.heard("你好，")
        h.advance(100)
        h.start()
        h.advance(500)
        assertEquals("你好", first.text.toString())
        assertEquals("", text())
        assertFalse(talking)
        assertNull(mask())
        voice.release()
        h.advance(500)
        assertEquals("", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-30")
    fun theKeyboardHiddenStopsItAndTypesTheRestOnceRecognized() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气，")
        voice.heard("很好，")
        h.advance(100)
        assertEquals("今天天气，很好，", text())
        voice.held = true
        voice.said += "我们走吧"
        h.hide()
        h.advance(100)
        // stopped as by a tap: what composed taken away, not left as text; the tail still recorded
        assertEquals("今天天气，", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.finishes)
        assertEquals(0, voice.closes)
        voice.release()
        h.advance(100)
        assertEquals("今天天气，很好，我们走吧", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-30")
    fun theKeyboardHiddenThenAnotherFieldTheFirstKeepsWhatWasHeard() {
        allowMicrophone()
        h.start(MULTI_LINE)
        val first = h.field
        tapMic()
        voice.heard("今天天气，")
        voice.heard("很好，")
        h.advance(100)
        voice.held = true
        voice.said += "我们走吧"
        h.hide()
        h.advance(100)
        assertEquals("今天天气，", first.text.toString())
        h.start()
        h.advance(100)
        assertEquals("今天天气，很好，", first.text.toString())
        assertEquals("", text())
        assertFalse(talking)
        voice.release()
        h.advance(500)
        assertEquals("今天天气，很好，", first.text.toString())
        assertEquals("", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-30")
    fun theKeyboardHiddenAfterTheStopTheRestStillComesIn() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气，")
        voice.heard("很好，")
        h.advance(100)
        voice.held = true
        voice.said += "我们走吧"
        tapMic()
        assertEquals(1, voice.finishes)
        h.hide()
        h.advance(100)
        assertEquals("今天天气，", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.finishes)
        assertTrue(talking)
        voice.release()
        h.advance(100)
        assertEquals("今天天气，很好，我们走吧", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-30")
    fun theMaskThrownAwayWithTheKeysWhatWasHeardIsTyped() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气，")
        voice.hearing("很好")
        h.advance(100)
        val mask = mask()!!
        assertTrue(mask.isAttachedToWindow)
        (mask.parent as ViewGroup).removeView(mask)
        h.advance(100)
        assertEquals("今天天气，很好", text())
        assertEquals(-1 to -1, composing())
        assertFalse(talking)
        voice.release()
        h.advance(100)
        assertEquals("今天天气，很好", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-30")
    fun inputRestartedInTheSameFieldTalksOn() {
        allowMicrophone()
        h.start(MULTI_LINE, fieldId = 7)
        tapMic()
        voice.heard("今天天气，")
        voice.heard("很好，")
        h.advance(100)
        assertEquals("今天天气，很好，", text())
        // a WebView's field, an app's restartInput: a new EditorInfo
        h.restart()
        h.advance(100)
        assertTrue(talking)
        assertEquals("今天天气，", text())
        voice.hearing("我们")
        h.advance(100)
        assertEquals("今天天气，很好，我们", text())
        voice.said += "我们走吧"
        tapMic()
        assertEquals("今天天气，很好，我们走吧", text())
        assertEquals(1, voice.opened)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-31")
    fun givenUpWhatWasHeardIsTypedAfterWhatWent() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气，")
        voice.heard("很好，")
        h.advance(100)
        voice.held = true
        voice.stuck = true
        tapMic()
        tapMic()
        h.advance(15_100)
        assertEquals("今天天气，很好，", text())
        assertEquals(-1 to -1, composing())
        assertTrue(shown().toString(), string(R.string.voice_tap_cut_short) in shown())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-31")
    fun givenUpWithNothingTypedSaysNothingWasHeard() {
        allowMicrophone()
        h.start()
        tapMic()
        voice.held = true
        voice.stuck = true
        voice.said += "我们走吧"
        tapMic()
        tapMic()
        h.advance(15_100)
        assertTrue(shown().toString(), string(R.string.voice_hold_nothing) in shown())
        assertEquals("", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-31")
    fun givenUpWithOnlyTheTailHeardItIsTypedAndSaidCutShort() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.hearing("我们走吧")
        h.advance(100)
        voice.held = true
        voice.stuck = true
        tapMic()
        tapMic()
        h.advance(15_100)
        assertEquals("我们走吧", text())
        assertEquals(-1 to -1, composing())
        assertTrue(shown().toString(), string(R.string.voice_tap_cut_short) in shown())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-31")
    fun aMinuteWithNothingSaidStopsIt() {
        allowMicrophone()
        h.start()
        tapMic()
        h.advance(VoiceTap.QUIET_MS / 2)
        voice.hearing("你好")
        h.advance(VoiceTap.QUIET_MS - 1000)
        assertTrue(talking)
        assertEquals(0, voice.finishes)
        h.advance(2000)
        assertEquals(1, voice.finishes)
        assertEquals("你好", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }
}
