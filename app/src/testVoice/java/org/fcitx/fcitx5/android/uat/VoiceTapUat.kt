/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.Manifest
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.voice.VoiceSession
import org.fcitx.fcitx5.android.input.voice.VoiceStyle
import org.fcitx.fcitx5.android.input.voice.VoiceTapOverlay
import org.junit.After
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * What tapping the bar's microphone to talk's UAT tests share (uat/12): the harness with the
 * [FakeRecognizer] behind the real VoiceSession, and how they find the microphone, the mask and
 * the field's text. [VoiceTapUatTest]: talking and stopping; [VoiceTapFieldUatTest]: the field
 * left, restarted or the keyboard hidden, and the ends with nothing back.
 */
open class VoiceTapUat {

    protected val voice = FakeRecognizer()
    protected val restore = mutableListOf<() -> Unit>()

    protected val h = KeyboardHarness().also {
        VoiceSession.listening = { events -> voice.also { v -> v.events = events } }
        val was = VoiceStyle.haptics
        VoiceStyle.haptics = { _, _ -> }
        restore += { VoiceStyle.haptics = was }
    }

    @After
    fun close() {
        h.close()
        VoiceSession.listening = null
        restore.asReversed().forEach { it() }
    }

    protected fun allowMicrophone() = shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)

    protected fun string(id: Int) = h.service.getString(id)

    protected fun described(description: CharSequence, within: View = h.inputView): View? =
        KeyboardHarness.all(within).firstOrNull { h.shown(it) && it.contentDescription == description }

    // the bar's own microphone
    protected fun mic(): View = described(string(R.string.voice_input)) ?: error("no microphone on the bar")

    protected fun tapMic() {
        h.tap(mic())
        h.advance(100)
    }

    // the mask: what its hint sits on (hidden once it is stopped), taken by a click
    protected fun mask(): View? {
        val hint = KeyboardHarness.all(h.inputView)
            .firstOrNull { it is TextView && it.text.toString() == string(R.string.voice_tap_hint) } ?: return null
        var at = hint.parent as? View
        while (at != null && !at.hasOnClickListeners()) at = at.parent as? View
        return at?.takeIf { h.shown(it) }
    }

    // the keyboard's own Enter, named as the field's action
    protected fun enter(): View =
        described(ReturnKeyDrawableComponent.appearanceFromEditorInfo(h.info).label(h.service), h.keyboard) ?: error("no Enter on the keyboard")

    protected fun shown(): List<String> = KeyboardHarness.all(h.inputView)
        .filterIsInstance<TextView>().filter { h.shown(it) }.map { it.text.toString() }.toList()

    protected fun text() = h.field.text.toString()

    protected fun composing() = h.field.text.let { BaseInputConnection.getComposingSpanStart(it) to BaseInputConnection.getComposingSpanEnd(it) }

    protected fun enters() = h.api.calls.count { it.startsWith("sendKey(sym=$RETURN,") }

    protected val talking get() = h.inputView.hold is VoiceTapOverlay

    companion object {
        const val MULTI_LINE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        const val RETURN = 0xff0d
    }
}
