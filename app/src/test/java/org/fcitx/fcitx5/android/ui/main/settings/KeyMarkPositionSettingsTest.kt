/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * 标点位置 and 快捷键图标位置 on 按键, one after the other, one list of places in one order, never one
 * place in both but 无 (uat/02 KB-66): the other's place greyed out, said whose it is, not picked; one
 * stored in both (an import, a backup) read with the symbol moved ([KeyMarks.settled]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class KeyMarkPositionSettingsTest {

    @get:Rule
    val prefs = PrefsRule()

    private val st = SettingsHarness()

    @After
    fun close() = st.close()

    // what the dialogs pick put back after
    @Before
    fun defaults() {
        prefs.set(keyboard.punctuationPosition, KeyMarkPosition.TopRight)
        prefs.set(keyboard.shortcutMarkPosition, KeyMarkPosition.TopLeft)
    }

    private val keyboard get() = AppPrefs.getInstance().keyboard

    private val places = listOf("右上", "右下", "左上", "左下", "底部", "无")

    // [title]'s dialog opened: its rows' texts, which can be picked
    private fun dialog(title: Int): AlertDialog {
        st.pref(title).performClick()
        st.idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun rows(d: AlertDialog) = (0 until d.listView.adapter.count).map { d.listView.adapter.getItem(it).toString() to d.listView.adapter.isEnabled(it) }

    @Test
    @Uat("KB-66")
    fun theTwoTogetherTheOthersPlaceGreyedOut() {
        st.open(SettingsRoute.Keys)
        val titles = st.titles()
        val symbol = titles.indexOf(st.s(R.string.punctuation_position))
        assertEquals("快捷键图标位置 right after 标点位置", symbol + 1, titles.indexOf(st.s(R.string.shortcut_mark_position)))
        assertEquals(places, (st.pref(R.string.punctuation_position) as ListPreference).entries.map { it.toString() })
        assertEquals(places, (st.pref(R.string.shortcut_mark_position) as ListPreference).entries.map { it.toString() })
        // the defaults: 右上, 左上
        assertEquals("右上", st.pref(R.string.punctuation_position).summary)
        assertEquals("左上", st.pref(R.string.shortcut_mark_position).summary)

        val d = dialog(R.string.punctuation_position)
        val r = rows(d)
        assertEquals("左上（快捷键图标在这里）" to false, r[2])
        assertTrue(r.filterIndexed { i, _ -> i != 2 }.all { it.second })
        // the greyed one not picked, another is
        d.listView.performItemClick(null, 2, 2)
        st.idle()
        assertEquals(KeyMarkPosition.TopRight, keyboard.punctuationPosition.getValue())
        d.listView.performItemClick(null, 3, 3)
        st.idle()
        assertEquals(KeyMarkPosition.BottomLeft, keyboard.punctuationPosition.getValue())
        assertFalse(d.isShowing)

        val s = dialog(R.string.shortcut_mark_position)
        assertEquals("左下（标点在这里）" to false, rows(s)[3])
        // the default 左上 free: 默认 offered
        assertTrue(s.getButton(DialogInterface.BUTTON_NEUTRAL).isShown)
        s.listView.performItemClick(null, 0, 0)
        st.idle()
        assertEquals(KeyMarkPosition.TopRight, keyboard.shortcutMarkPosition.getValue())
    }

    @Test
    fun noneInBothAndNothingGreyedOut() {
        prefs.set(keyboard.punctuationPosition, KeyMarkPosition.None)
        st.open(SettingsRoute.Keys)
        val d = dialog(R.string.shortcut_mark_position)
        assertTrue(rows(d).all { it.second })
        d.listView.performItemClick(null, 5, 5)
        st.idle()
        assertEquals(KeyMarkPosition.None, keyboard.shortcutMarkPosition.getValue())
        assertTrue(rows(dialog(R.string.punctuation_position)).all { it.second })
    }

    @Test
    fun theDefaultTakenNoDefaultButton() {
        // 快捷键图标位置 右上: 标点位置's default is where it is
        prefs.set(keyboard.punctuationPosition, KeyMarkPosition.BottomRight)
        prefs.set(keyboard.shortcutMarkPosition, KeyMarkPosition.TopRight)
        st.open(SettingsRoute.Keys)
        val d = dialog(R.string.punctuation_position)
        assertEquals("右上（快捷键图标在这里）" to false, rows(d)[0])
        assertNull(d.getButton(DialogInterface.BUTTON_NEUTRAL)?.takeIf { it.isShown })
    }

    @Test
    fun onePlaceStoredInBothReadsAsTheSymbolMoved() {
        prefs.set(keyboard.punctuationPosition, KeyMarkPosition.TopLeft)
        prefs.set(keyboard.shortcutMarkPosition, KeyMarkPosition.TopLeft)
        st.open(SettingsRoute.Keys)
        assertEquals("左下", st.pref(R.string.punctuation_position).summary)
        assertEquals("左上", st.pref(R.string.shortcut_mark_position).summary)
        assertEquals("左下（标点在这里）" to false, rows(dialog(R.string.shortcut_mark_position))[3])
    }
}
