/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.VoiceSession.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionTest {

    private val session = VoiceSession()

    @Test
    fun itListensOnceLoadedAndTypesEachStretchAsItEnds() {
        assertEquals(State.Loading, session.state)
        assertFalse(session.listening)
        assertTrue(session.wantsModels)
        session.loaded()
        assertTrue(session.listening)
        session.speechStarted()
        assertEquals(State.Hearing, session.state)
        session.speechEnded()
        assertEquals(State.Listening, session.state)
        assertEquals(1, session.pending)
        assertEquals("今天天气怎么样？", session.recognized(" 今天天气怎么样？ "))
        assertEquals(0, session.pending)
    }

    @Test
    fun aTapPausesAndResumes() {
        session.loaded()
        session.tap()
        assertEquals(State.Paused, session.state)
        assertFalse(session.wantsModels)
        // nothing heard while paused
        session.speechStarted()
        assertEquals(State.Paused, session.state)
        session.tap()
        assertEquals(State.Listening, session.state)
    }

    @Test
    fun pausedWhileLoadingItDoesNotListenOnceLoaded() {
        session.tap()
        assertEquals(State.Paused, session.state)
        session.loaded()
        assertEquals(State.Paused, session.state)
        assertFalse(session.listening)
        session.tap()
        assertTrue(session.listening)
    }

    @Test
    fun theKeyboardHiddenWhileLoadingTheMicrophoneStaysClosed() {
        session.pause()
        session.loaded()
        assertFalse(session.listening)
    }

    @Test
    fun theRecognizerLetGoATapReadsTheModelsInAgain() {
        session.loaded()
        session.speechStarted()
        session.speechEnded()
        session.pause()
        session.unloaded()
        assertEquals(State.Paused, session.state)
        assertEquals(0, session.pending)
        session.tap()
        assertEquals(State.Loading, session.state)
        assertTrue(session.wantsModels)
        session.loaded()
        assertTrue(session.listening)
        // let go while in use: paused, not listening without the models
        session.unloaded()
        assertEquals(State.Paused, session.state)
    }

    @Test
    fun aFailureStandsUntilReset() {
        session.failed(VoiceSession.Failure.NoPermission)
        session.loaded()
        session.pause()
        session.tap()
        assertEquals(State.Failed, session.state)
        assertEquals(VoiceSession.Failure.NoPermission, session.failure)
        assertFalse(session.wantsModels)
        session.unloaded()
        assertEquals(State.Failed, session.state)
        // granted after all
        session.reset()
        assertEquals(State.Loading, session.state)
        assertNull(session.failure)
        session.loaded()
        assertTrue(session.listening)
    }

    @Test
    fun aStretchRecognizedAfterAPauseIsStillTyped() {
        session.loaded()
        session.speechStarted()
        session.speechEnded()
        session.tap()
        assertEquals("好的。", session.recognized("好的。"))
        assertEquals(0, session.recognized("好的。")?.let { session.pending })
    }

    @Test
    fun noiseAndFillersTypeNothing() {
        assertNull(VoiceText.clean(""))
        assertNull(VoiceText.clean("。"))
        assertNull(VoiceText.clean("嗯。"))
        assertNull(VoiceText.clean("呃，啊"))
        assertEquals("嗯，好的。", VoiceText.clean("嗯，好的。"))
        assertEquals("OK.", VoiceText.clean("OK."))
    }

    @Test
    fun spacedAsTyped() {
        assertEquals("对啊，大家最近都是觉得AI有一天能够replace人类。", VoiceText.clean("对啊， 大家最近都是觉得 AI 有一天能够 replace 人类。"))
        assertEquals("委员会CEP中宣誓就职。", VoiceText.clean("委员会 C E P 中宣誓就职。"))
        assertEquals("Make friends.", VoiceText.clean("Make friends."))
        assertEquals("I am OK", VoiceText.clean("I am OK"))
        assertEquals("1990年，由于沙漠的威胁", VoiceText.clean("一九九零年， 由于沙漠的威胁"))
    }
}
