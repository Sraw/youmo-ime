/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FakeHostConnection
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A talk's stretches go to the engine only where it follows voice's text: nowhere else is spoken text held. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceStretchesTest {

    @get:Rule
    val prefs = PrefsRule()

    // the stretches handed to the engine, waited for: none told is none handed over, not one not yet in
    private fun told(keeps: Boolean): Int {
        val fcitx = FakeHostConnection(TestScope())
        runBlocking {
            VoiceEngine.language(fcitx) { keeps }.stretches(listOf("我找徐静"))
            // far past their own LANGUAGE_MS: a wait this long is a wait on something else
            withTimeout(10_000) { VoiceEngine.stretchesHandedOver() }
        }
        return fcitx.api.calls.count { it == "voiceStretches([我找徐静])" }
    }

    @Test
    fun theStretchesGoOnlyWhereTheyAreKept() {
        assertRows(
            // the control: the same path, waited for the same way, does hand them over
            "kept: given them" to { assertEquals(1, told(keeps = true)) },
            "not kept: never given them" to { assertEquals(0, told(keeps = false)) },
            "a read of the user's words still out (the engine not ready): given them, not waited on it" to {
                val asked = CompletableDeferred<Unit>()
                val ready = CompletableDeferred<Unit>()
                val fake = FakeHostConnection(TestScope())
                val starting = object : HostConnection by fake {
                    override suspend fun <T> runOnReady(block: suspend HostApi.() -> T): T {
                        asked.complete(Unit)
                        ready.await()
                        return fake.runOnReady(block)
                    }
                }
                try {
                    runBlocking { VoiceEngine.userHotwords(starting) }
                    assertTrue("the read is out, waiting on the engine", asked.isCompleted)
                    assertEquals(1, told(keeps = true))
                } finally {
                    ready.complete(Unit)
                }
            },
        )
    }

    @Test
    fun theyAreKeptOnlyInAFieldAskedAboutWhileLearning() {
        val internal = AppPrefs.getInstance().internal
        fun keeps(remember: Boolean, habits: Boolean, inputType: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0): Boolean {
            prefs.set(internal.voiceAskRemember, remember)
            prefs.set(internal.useHabits, habits)
            return KeyboardHarness().use { it.start(inputType, imeOptions).service.voiceKeepsStretches }
        }
        assertRows(
            "an ordinary field, asking and learning" to { assertEquals(true, keeps(remember = true, habits = true)) },
            "a password's" to {
                assertEquals(false, keeps(remember = true, habits = true, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
            },
            "a field that does not learn (an incognito one's)" to {
                assertEquals(false, keeps(remember = true, habits = true, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
            },
            "习惯学习 off" to { assertEquals(false, keeps(remember = true, habits = false)) },
            "语音先问再记 off" to { assertEquals(false, keeps(remember = false, habits = true)) },
        )
    }
}
