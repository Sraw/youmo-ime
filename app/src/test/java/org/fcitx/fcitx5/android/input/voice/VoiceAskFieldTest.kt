/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.text.InputType
import kotlinx.coroutines.CompletableDeferred
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.uat.VoiceTapUat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 语音先问再记: the answer comes on the fcitx thread; a field left meanwhile is not read nor asked over, the code in it not read as text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceAskFieldTest : VoiceTapUat() {

    @get:Rule
    val prefs = PrefsRule()

    private val xujing = VoiceEdits.Ask("徐婧", "xu jing", "徐静", replaces = null, hint = false)

    private fun start() {
        val internal = AppPrefs.getInstance().internal
        prefs.set(internal.voiceAskRemember, true)
        prefs.set(internal.voiceAskHintShown, false)
        allowMicrophone()
        h.start()
    }

    private fun talk(said: String) {
        tapMic()
        voice.said += said
        tapMic()
        h.idle()
    }

    private fun typedHeld(text: String): CompletableDeferred<Unit> {
        val held = CompletableDeferred<Unit>().also { h.api.voicePutInHeld = it }
        h.api.voiceAsk = xujing
        h.field.text.let { it.delete(it.length - text.length, it.length) }
        // unreported, the commit after it is taken to land past the deleted text, and the next code is not where the session looks
        h.idle()
        h.emit(HostEvent.Commit(text, -1))
        assertTrue(h.api.calls.toString(), "voicePutIn($text)" in h.api.calls)
        return held
    }

    private fun asked() = h.noticeTexts().any { it == h.service.getString(R.string.voice_ask_remember, "徐婧") }

    @Test
    @Uat("VO-41")
    fun aFieldLeftBeforeTheEngineAnswersIsNotReadNorAskedOver() {
        start()
        talk("我找徐静")
        val held = typedHeld("徐婧")
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { it.setText("hunter2") }
        held.complete(Unit)
        h.idle()
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("voiceConfirm(") })
        assertFalse(asked())
    }

    @Test
    @Uat("VO-41")
    fun theCodeInTheFieldIsNotReadAsItsText() {
        start()
        talk("我找徐静")
        val held = typedHeld("徐婧")
        // its code written into the field (编码行: 输入框里)
        prefs.set(ThemeManager.prefs.codeRow, CodeRowStyle.InField)
        val panel = HostEvent.InputPanel(FormattedText(arrayOf("n"), intArrayOf(0), 1), FormattedText.Empty, FormattedText.Empty, code = true)
        h.api.inputPanelCached = panel
        h.emit(panel)
        assertEquals("the code in the field", "我找徐婧n", h.field.text.toString())
        held.complete(Unit)
        h.idle()
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("voiceConfirm(我找徐婧, ") })
    }
}
