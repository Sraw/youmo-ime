/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipData
import android.text.InputType
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class DeveloperUatTest {

    @get:Rule
    val prefs = PrefsRule()

    // read each time, not kept from the class's making: the page's preferences must be AppPrefs' own
    private val inspector get() = AppPrefs.getInstance().internal.editorInfoInspector

    @Test
    @Uat("ST-29")
    fun theLiveLogOpensAndTheInspectorSwitchesOn() {
        prefs.set(inspector, false)
        SettingsHarness().use { st ->
            st.activity
            st.open(SettingsRoute.Developer)
            st.pref(R.string.real_time_logs).performClick()
            st.idle()
            assertEquals(LogActivity::class.java.name, shadowOf(st.activity).nextStartedActivity.component!!.className)
            val switch = st.pref(R.string.editor_info_inspector) as TwoStatePreference
            switch.performClick()
            st.idle()
            // the default preferences, which AppPrefs reads
            val saved = PreferenceManager.getDefaultSharedPreferences(st.activity).getBoolean(inspector.key, false)
            assertTrue("switch ${switch.isChecked}", switch.isChecked && saved)
        }
    }

    @Test
    @Uat("ST-119")
    fun voiceCorrectionsAskedAndRememberedAreCounted() {
        val internal = AppPrefs.getInstance().internal
        prefs.set(internal.voiceAskShown, 3)
        prefs.set(internal.voiceAskTaken, 1)
        SettingsHarness().use { st ->
            st.activity
            st.open(SettingsRoute.Developer)
            assertTrue(!st.pref(st.activity.getString(R.string.voice_ask_counts, 3, 1)).isSelectable)
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

    private fun restartSays(): String = SettingsHarness().use { st ->
        st.activity
        st.open(SettingsRoute.Developer)
        st.pref(R.string.restart_fcitx_instance).performClick()
        st.idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        until("said", { ShadowToast.getTextOfLatestToast() }) { st.idle(); ShadowToast.getTextOfLatestToast() != null }
        ShadowToast.getTextOfLatestToast()
    }

    @Test
    @Uat("LC-20")
    fun restartedTheInstanceSaysDone() {
        assertEquals(appContext.getString(R.string.done), restartSays())
    }

    @Test
    @Uat("LC-20")
    fun aRestartGivenUpOnAHostStillStartingSaysItMayNotHaveRestarted() {
        HostDaemon.testRestarted = false
        try {
            assertEquals(appContext.getString(R.string.restart_fcitx_still_starting), restartSays())
        } finally {
            HostDaemon.testRestarted = true
        }
    }

    @Test
    @Uat("ST-196")
    fun clearingTheClipboardDatabaseTakesTheFavouritesToo() {
        ClipboardManager.resetDuplicateCheck()
        appContext.clipboardManager.setPrimaryClip(ClipData.newPlainText(null, "公园"))
        runBlocking {
            ClipboardManager.coroutineContext.job.children.forEach { it.join() }
            ClipboardManager.favorite(ClipboardManager.lastEntry!!.id)
        }
        assertEquals(1 to 1, ClipboardManager.recentCount to ClipboardManager.favoriteCount)
        SettingsHarness().use { st ->
            st.activity
            st.open(SettingsRoute.Developer)
            st.pref(R.string.clear_clb_db).performClick()
            st.idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(st.s(R.string.clear_clp_db_confirm), dialog.findViewById<TextView>(android.R.id.message)!!.text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until("cleared", { ClipboardManager.recentCount to ClipboardManager.favoriteCount }) {
                st.idle()
                ClipboardManager.recentCount == 0 && ClipboardManager.favoriteCount == 0
            }
        }
    }
}
