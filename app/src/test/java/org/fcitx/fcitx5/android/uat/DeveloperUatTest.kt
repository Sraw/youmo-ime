/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 开发者 (uat/14 ST-29): its page, and the editor info inspector it turns on in the keyboard's ⋯. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class DeveloperUatTest {

    @get:Rule
    val prefs = PrefsRule()

    // read each time, not kept from the class's making (OneDefaultPreferencesShadow now makes the page's
    // preferences AppPrefs' own; before it the switch saved to another, 2026-10-31's full run)
    private val inspector get() = AppPrefs.getInstance().internal.editorInfoInspector

    @Test
    @Uat("ST-29")
    fun theLiveLogOpensAndTheInspectorSwitchesOn() {
        prefs.set(inspector, false)
        SettingsHarness().use { st ->
            st.activity
            st.open(SettingsRoute.Developer)
            // the page shows the new drawing's switch on, as it is by default, and writes it so (it wrote off once)
            assertTrue((st.pref(R.string.canvas_keyboard) as TwoStatePreference).isChecked)
            assertTrue(AppPrefs.getInstance().internal.canvasKeyboard.getValue())
            st.pref(R.string.real_time_logs).performClick()
            st.idle()
            assertEquals(LogActivity::class.java.name, shadowOf(st.activity).nextStartedActivity.component!!.className)
            val switch = st.pref(R.string.editor_info_inspector) as TwoStatePreference
            switch.performClick()
            st.idle()
            // saved under the inspector's key in the default preferences, which AppPrefs reads
            val saved = PreferenceManager.getDefaultSharedPreferences(st.activity).getBoolean(inspector.key, false)
            assertTrue("switch ${switch.isChecked}", switch.isChecked && saved)
        }
    }

    @Test
    @Uat("ST-29")
    fun theInspectorInTheStatusAreaListsTheFieldsInputType() {
        prefs.set(inspector, true)
        KeyboardHarness().use { h ->
            h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
            h.after(500)
            h.tap(h.described(h.service.getString(R.string.status_area)))
            h.tap(h.described(h.service.getString(R.string.editor_info_inspector)))
            val texts = h.texts(h.inputView)
            assertTrue(texts.toString(), texts.any { "TYPE_TEXT_VARIATION_URI" in it })
        }
    }
}
