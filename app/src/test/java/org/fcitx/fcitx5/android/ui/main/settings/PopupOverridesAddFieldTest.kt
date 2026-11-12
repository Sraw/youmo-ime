/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The key editor over the keyboard: 确定 in reach while it is up; the 添加字符 field's Done adds and puts it away. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PopupOverridesAddFieldTest {

    private val st = SettingsHarness()

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() = st.close()

    @Test
    fun doneInTheAddFieldAddsAndPutsTheKeyboardAway() {
        // the dialog's 确定 sits under the keyboard while it is up: Done adds what was typed and hides it
        st.activity
        st.open(SettingsRoute.PopupOverrides)
        KeyboardHarness.all(st.activity.window.decorView)
            .first { it.contentDescription?.toString()?.substringBefore(',') == "q" && it.hasOnClickListeners() }
            .performClick()
        st.idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val field = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().first()
        field.requestFocus()
        val imm = st.activity.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(field, 0)
        val shadow = shadowOf(imm)
        assertTrue(shadow.isSoftInputVisible)
        field.setText("☆")
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        st.idle()
        assertEquals("", field.text.toString())
        assertTrue(KeyboardHarness.all(dialog.window!!.decorView).any { it.contentDescription?.toString()?.startsWith("☆") == true })
        assertFalse(field.hasFocus())
        assertFalse(shadow.isSoftInputVisible)
        assertTrue(dialog.isShowing)
    }

    @Test
    fun aFullListKeepsTheFieldAndKeyboardWithItsError() {
        // what did not fit is said under the field: Done leaves the keyboard up and 确定 keeps the dialog to show it
        st.activity
        st.open(SettingsRoute.PopupOverrides)
        KeyboardHarness.all(st.activity.window.decorView)
            .first { it.contentDescription?.toString()?.substringBefore(',') == "q" && it.hasOnClickListeners() }
            .performClick()
        st.idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val field = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().first()
        field.requestFocus()
        val imm = st.activity.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(field, 0)
        val shadow = shadowOf(imm)
        field.setText((1..20).joinToString(" ") { "s$it" })
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        st.idle()
        assertTrue(field.error != null)
        assertTrue(field.hasFocus())
        assertTrue(shadow.isSoftInputVisible)
        field.error = null
        field.setText("x")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        assertTrue(dialog.isShowing)
        assertTrue(field.error != null)
    }

    // the dialog's buttons above the keyboard while it is up, its content scrolling in the room left: resized as asked
    // on 29; from 30 the window the screen's height and the content padded by the keyboard, back as it goes
    @Test
    fun theDialogKeepsAboveTheKeyboard() {
        st.activity
        st.open(SettingsRoute.PopupOverrides)
        KeyboardHarness.all(st.activity.window.decorView)
            .first { it.contentDescription?.toString()?.substringBefore(',') == "q" && it.hasOnClickListeners() }
            .performClick()
        st.idle()
        val window = (ShadowDialog.getLatestDialog() as AlertDialog).window!!
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val content = window.findViewById<View>(android.R.id.content)
        fun keyboard(height: Int) = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
            .setVisible(WindowInsetsCompat.Type.ime(), height > 0)
            .build()
        ViewCompat.dispatchApplyWindowInsets(window.decorView, keyboard(800))
        assertEquals(800, content.paddingBottom)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, window.attributes.height)
        ViewCompat.dispatchApplyWindowInsets(window.decorView, keyboard(0))
        assertEquals(0, content.paddingBottom)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, window.attributes.height)
    }
}
