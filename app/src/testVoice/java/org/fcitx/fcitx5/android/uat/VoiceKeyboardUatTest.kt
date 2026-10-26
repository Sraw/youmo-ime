/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.voice.VoicePermissionActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UAT cases of the voice build's keyboard (uat/12): where holding the space talks. No microphone
 * here (the permission not granted): a hold that would talk asks for it, which is how it shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceKeyboardUatTest {

    private val h = KeyboardHarness()

    @After
    fun close() = h.close()

    private val space: View
        get() = KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription?.startsWith("Space") == true }

    private fun hold(v: View) {
        val f = h.finger(v, h.at(v))
        h.advance(700)
        f.up(h.at(v))
    }

    private fun asked() = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        ?.component?.className == VoicePermissionActivity::class.java.name

    private fun microphone() = KeyboardHarness.all(h.inputView).firstOrNull { it.contentDescription == h.service.getString(R.string.voice_input) }

    @Test
    @Uat("VO-11")
    fun aPasswordFieldHasNoVoice() {
        // a plain field: the bar has the microphone, holding the space talks
        h.start()
        assertTrue(microphone()?.let { h.shown(it) } == true)
        hold(space)
        assertTrue(asked())
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(microphone()?.let { h.shown(it) } == true)
        hold(space)
        assertFalse(asked())
        // a slow drag moves the cursor: the space not held for voice
        h.api.calls.clear()
        val s = space
        h.touch(s, h.at(s), h.at(s, ms = 600), h.at(s, dx = -20f, ms = 400), h.at(s, dx = -60f, ms = 400))
        // the cursor moved (Left, 0xff51), and no space typed: a drag that typed one passed the old check
        val sent = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
        assertTrue(h.api.calls.toString(), sent.any { "sym=65361," in it })
        assertFalse(h.api.calls.toString(), sent.any { "sym=32," in it || "char= ," in it })
    }
}
