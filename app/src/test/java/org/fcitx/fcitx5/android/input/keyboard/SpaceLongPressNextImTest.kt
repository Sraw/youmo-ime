/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.fcitx.fcitx5.android.Uat
import org.robolectric.shadows.ShadowDialog

/**
 * audit-061 app-input-8: the space's long press set to the next input method, with one input method enabled,
 * shows the prompt to add more as the language key does; it did nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class SpaceLongPressNextImTest {

    private val behavior = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior
    private val was = behavior.getValue()
    private var h: KeyboardHarness? = null

    @After
    fun close() {
        h?.close()
        behavior.setValue(was)
    }

    @Test
    @Uat("ST-17")
    fun holdingTheSpaceWithOneInputMethodAsksToAddMore() {
        behavior.setValue(SpaceLongPressBehavior.Enumerate)
        val h = KeyboardHarness(im = KeyboardHarness.ENGLISH).also { this.h = it }.start()
        h.api.enabledIme = arrayOf(KeyboardHarness.ENGLISH)
        val before = ShadowDialog.getLatestDialog()
        val space = h.keys().first { it.description?.let { d -> d == "Space" || d.startsWith("Space,") } == true }
        h.press(space)
        h.after(1000)
        h.release()
        h.idle()
        val shown = ShadowDialog.getLatestDialog()
        assertTrue("a dialog shown", shown != null && shown !== before && shown.isShowing)
        // the prompt to add input methods, the language key's, not any dialog
        assertEquals(h.service.getString(R.string.add_more_input_methods), shown.findViewById<TextView>(android.R.id.message)?.text?.toString())
        assertEquals(h.api.calls.toString(), 0, h.api.calls.count { it.startsWith("enumerateIme") })
    }
}
