/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.voice.VoiceEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 语音先问再记, the app's side (061-voice-learn-from-edits.md): a talk's text, all in, told to the engine with what
 * is before the cursor then; what goes in after reported; the engine's ask shown as a notice with 记住, its 撤销
 * after; the switch and the counts. What the engine follows and decides: VoiceEditsTest, EnginesVoiceEditsTest
 * (`lib/ime-core`); here [FakeHostApi.voiceAsk] says what it asks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceAskUatTest : VoiceTapUat() {

    @get:Rule
    val prefs = PrefsRule()

    private val internal get() = AppPrefs.getInstance().internal

    private val xujing = VoiceEdits.Ask("徐婧", "xu jing", "徐静", replaces = null, hint = false)

    // a talk of [said], tapped on and off
    private fun talk(said: String) {
        tapMic()
        voice.said += said
        tapMic()
        h.idle()
    }

    // the engine's commit of [text] (徐婧 typed into the gap)
    private fun typed(text: String) {
        h.field.text.let { it.delete(it.length - text.length, it.length) }
        h.emit(HostEvent.Commit(text, -1))
        h.idle()
    }

    private fun button(text: String) {
        val w = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow!!
        check(KeyboardHarness.all(w.contentView).filterIsInstance<TextView>().first { it.text.toString() == text }.performClick())
        h.idle()
    }

    private fun counts() = internal.voiceAskShown.getValue() to internal.voiceAskTaken.getValue()

    private fun start(inputType: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0) {
        prefs.set(internal.voiceAskRemember, true)
        prefs.set(internal.voiceAskShown, 0)
        prefs.set(internal.voiceAskTaken, 0)
        prefs.set(internal.voiceAskHintShown, false)
        allowMicrophone()
        h.start(inputType, imeOptions)
    }

    @Test
    @Uat("VO-41", "VO-47", "WD-59")
    fun aWordTypedAgainIsAskedAboutAndRememberedOnATap() {
        start()
        talk("我找徐静")
        assertRows(
            "the talk's text told once it is in, with what is before the cursor read then (review R6)" to {
                assertEquals("我找徐静", text())
                assertTrue(h.api.calls.toString(), "voiceSaid(我找徐静, 我找徐静, false)" in h.api.calls)
            },
            "what goes in after reported; the field read before the notice (review M6)" to {
                h.api.voiceAsk = xujing
                typed("徐婧")
                assertTrue(h.api.calls.toString(), "voicePutIn(徐婧)" in h.api.calls)
                assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("voiceConfirm(我找徐婧, ") })
            },
            "asked, 记住 its button" to {
                assertEquals(listOf(string(R.string.voice_ask_remember, "徐婧"), string(R.string.voice_ask_remember_action)), h.noticeTexts())
                assertEquals(1 to 0, counts())
            },
            "记住: added by hand, said so with 撤销" to {
                button(string(R.string.voice_ask_remember_action))
                assertTrue(h.api.calls.toString(), "addUserWord(徐婧, xu jing)" in h.api.calls)
                // the word added drops the engine's sessions: what the bar showed of the old one cleared (review 16)
                assertTrue(h.api.calls.toString(), h.api.calls.lastIndexOf("reset()") > h.api.calls.indexOf("addUserWord(徐婧, xu jing)"))
                assertEquals(listOf(string(R.string.voice_ask_remembered, "徐婧"), string(R.string.learning_undo)), h.noticeTexts())
                assertEquals(1 to 1, counts())
            },
            "撤销: taken off, the count with it" to {
                button(string(R.string.learning_undo))
                assertTrue(h.api.calls.toString(), "voiceForget(徐婧)" in h.api.calls)
                assertEquals(1 to 0, counts())
            },
        )
    }

    private fun string(id: Int, vararg args: Any) = h.service.getString(id, *args)

    @Test
    @Uat("VO-42")
    fun notTappedNothingIsAdded() {
        start()
        talk("我找徐静")
        h.api.voiceAsk = xujing
        typed("徐婧")
        assertRows(
            "asked" to { assertEquals(string(R.string.voice_ask_remember, "徐婧"), h.noticeTexts().firstOrNull()) },
            "a key typed: gone, nothing added" to {
                h.emit(HostEvent.Commit("了", -1))
                h.advance(1000)
                assertEquals(emptyList<String>(), h.noticeTexts())
                assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("addUserWord") })
            },
            "asked again: gone by itself in 6 s" to {
                h.api.voiceAsk = xujing
                typed("徐婧")
                assertEquals(string(R.string.voice_ask_remember, "徐婧"), h.noticeTexts().firstOrNull())
                h.advance(7000)
                assertEquals(emptyList<String>(), h.noticeTexts())
                assertFalse(h.api.calls.any { it.startsWith("addUserWord") })
            },
        )
    }

    @Test
    @Uat("VO-46")
    fun aFieldThatLearnsNothingIsNotFollowed() {
        start(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        talk("我找徐静")
        assertRows(
            "voice's text not given to the engine, what it followed ended (review S6)" to {
                assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("voiceSaid") })
                assertTrue(h.api.calls.toString(), "voiceEditsEnd()" in h.api.calls)
            },
            "what goes in after not reported" to {
                h.api.voiceAsk = xujing
                typed("徐婧")
                assertFalse(h.api.calls.any { it.startsWith("voicePutIn") })
                assertEquals(emptyList<String>(), h.noticeTexts())
            },
        )
    }

    @Test
    @Uat("VO-47")
    fun whatGoesInWhileTalkingIsNotReported() {
        start()
        talk("我找徐静")
        tapMic()
        h.idle()
        h.api.calls.clear()
        h.api.voiceAsk = xujing
        typed("徐婧")
        assertRows(
            "another talk on: what goes in not reported, the window of the last ended" to {
                assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("voicePutIn") })
                assertTrue(h.api.calls.toString(), "voiceEditsEnd()" in h.api.calls)
                assertEquals(emptyList<String>(), h.noticeTexts())
            },
            "the talk over: its text told" to {
                voice.said += "了"
                tapMic()
                h.idle()
                assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("voiceSaid(了, ") })
            },
        )
    }

    @Test
    @Uat("VO-47")
    fun aTalkThatPutsNothingInLeavesTheLastFollowed() {
        start()
        talk("我找徐静")
        // tapped on and off, nothing said
        tapMic()
        tapMic()
        h.idle()
        h.api.voiceAsk = xujing
        typed("徐婧")
        assertRows(
            "what goes in after still reported" to { assertTrue(h.api.calls.toString(), "voicePutIn(徐婧)" in h.api.calls) },
            "asked" to { assertEquals(string(R.string.voice_ask_remember, "徐婧"), h.noticeTexts().firstOrNull()) },
            "the window not ended" to { assertFalse(h.api.calls.toString(), "voiceEditsEnd()" in h.api.calls) },
        )
    }

    @Test
    @Uat("VO-51")
    fun howToHaveAWordRememberedIsSaidOnce() {
        start()
        talk("我找徐静")
        val hint = VoiceEdits.Ask("", "", "", replaces = null, hint = true)
        h.api.voiceAsk = hint
        typed("婧")
        assertRows(
            "said, with nothing to tap" to { assertEquals(listOf(string(R.string.voice_ask_hint)), h.noticeTexts()) },
            "not counted as asked" to { assertEquals(0 to 0, counts()) },
            "not again" to {
                h.advance(3000)
                h.api.voiceAsk = hint
                typed("婧")
                assertEquals(emptyList<String>(), h.noticeTexts())
            },
            "the engine told it was said, not to look for it (review S7)" to {
                talk("我找徐静")
                assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("voiceSaid(我找徐静, ") && it.endsWith(", true)") })
            },
        )
    }

    @Test
    @Uat("WD-58")
    fun theSwitchOffAsksNothing() {
        start()
        prefs.set(internal.voiceAskRemember, false)
        talk("我找徐静")
        assertRows(
            "the engine not given voice's text to follow (review S6)" to {
                assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("voiceSaid") })
                assertTrue(h.api.calls.toString(), "voiceEditsEnd()" in h.api.calls)
            },
            "no notice, nothing counted" to {
                h.api.voiceAsk = xujing
                typed("徐婧")
                assertEquals(emptyList<String>(), h.noticeTexts())
                assertEquals(0 to 0, counts())
            },
        )
    }
}
