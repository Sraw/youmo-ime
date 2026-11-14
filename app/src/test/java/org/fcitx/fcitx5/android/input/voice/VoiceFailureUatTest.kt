/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.FakeRecognizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.fcitx.fcitx5.android.Uat
import org.robolectric.annotation.GraphicsMode

/**
 * The listener failing while holding to talk (voice-3): each failure says its own message on the
 * card, the hold's hint gone from under it (voice-10), and what was heard before it is typed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceFailureUatTest {

    @After
    fun close() {
        VoiceSession.listening = null
    }

    private class Row(val why: String, val failure: VoiceListener.Failure, val message: Int, val heardFirst: String?)

    @Test
    @Uat("VO-39")
    fun eachFailureSaysItsOwnMessageAndWhatWasHeardIsTyped() = assertRows(
        listOf(
            Row("the models not loading (no voice pack file read, memory out): nothing was heard, nothing typed", VoiceListener.Failure.NoModel, R.string.voice_no_model, null),
            Row(
                "the microphone failing midway (taken by a call, a read's error): what was heard before it is typed, not lost",
                VoiceListener.Failure.NoMicrophone, R.string.voice_no_microphone, "今天天气",
            ),
            Row(
                "recognizing failing with the models in (voice-5): neither the models' message nor the microphone's, and what was heard is typed",
                VoiceListener.Failure.Recognition, R.string.voice_recognition_failed, "今天天气",
            ),
        ),
        { it.why },
    ) { row ->
        // a keyboard and a recognizer of the row's own: a talk failed leaves its card and its session behind
        val voice = FakeRecognizer()
        VoiceSession.listening = { events -> voice.also { v -> v.events = events } }
        KeyboardHarness().use { h ->
            fun shown(): List<String> = KeyboardHarness.all(h.service.window.window!!.decorView)
                .filterIsInstance<TextView>().filter { h.shown(it) }.map { it.text.toString() }.toList()
            h.start()
            val space = h.keyNamed("space")
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val f = h.finger(space, h.at(space)).also { h.advance(700) }
            row.heardFirst?.let { voice.heard(it) }
            h.advance(100)
            voice.fail(row.failure)
            h.advance(100)
            assertTrue(shown().toString(), h.service.getString(row.message) in shown())
            // the hold's hint gone from under the message (voice-10)
            assertFalse(shown().toString(), h.service.getString(R.string.voice_hold_hint) in shown())
            assertEquals(row.heardFirst.orEmpty(), h.field.text.toString())
            f.up(h.at(h.keyNamed("space")))
            h.advance(300)
            assertEquals(row.heardFirst.orEmpty(), h.field.text.toString())
        }
    }
}
