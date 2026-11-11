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
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboardMode
import org.fcitx.fcitx5.android.input.keyboard.swipeSpots
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
        prefs.set(keyboard.swipeUpPosition, KeyMarkPosition.TopRight)
        prefs.set(keyboard.swipeDownPosition, KeyMarkPosition.TopLeft)
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
        val symbol = titles.indexOf(st.s(R.string.swipe_up_position))
        assertEquals("下滑 and 下滑图标位置 right after 上滑图标位置", listOf(symbol + 1, symbol + 2), listOf(titles.indexOf(st.s(R.string.swipe_down)), titles.indexOf(st.s(R.string.swipe_down_position))))
        assertEquals(places, (st.pref(R.string.swipe_up_position) as ListPreference).entries.map { it.toString() })
        assertEquals(places, (st.pref(R.string.swipe_down_position) as ListPreference).entries.map { it.toString() })
        // the defaults: 右上, 左上
        assertEquals("右上", st.pref(R.string.swipe_up_position).summary)
        assertEquals("左上", st.pref(R.string.swipe_down_position).summary)

        val d = dialog(R.string.swipe_up_position)
        val r = rows(d)
        assertEquals("左上（下滑图标在这里）" to false, r[2])
        assertTrue(r.filterIndexed { i, _ -> i != 2 }.all { it.second })
        // the greyed one not picked, another is
        d.listView.performItemClick(null, 2, 2)
        st.idle()
        assertEquals(KeyMarkPosition.TopRight, keyboard.swipeUpPosition.getValue())
        d.listView.performItemClick(null, 3, 3)
        st.idle()
        assertEquals(KeyMarkPosition.BottomLeft, keyboard.swipeUpPosition.getValue())
        assertFalse(d.isShowing)

        val s = dialog(R.string.swipe_down_position)
        assertEquals("左下（上滑图标在这里）" to false, rows(s)[3])
        // the default 左上 free: 默认 offered
        assertTrue(s.getButton(DialogInterface.BUTTON_NEUTRAL).isShown)
        s.listView.performItemClick(null, 0, 0)
        st.idle()
        assertEquals(KeyMarkPosition.TopRight, keyboard.swipeDownPosition.getValue())
    }

    @Test
    fun noneInBothAndNothingGreyedOut() {
        prefs.set(keyboard.swipeUpPosition, KeyMarkPosition.None)
        st.open(SettingsRoute.Keys)
        val d = dialog(R.string.swipe_down_position)
        assertTrue(rows(d).all { it.second })
        d.listView.performItemClick(null, 5, 5)
        st.idle()
        assertEquals(KeyMarkPosition.None, keyboard.swipeDownPosition.getValue())
        assertTrue(rows(dialog(R.string.swipe_up_position)).all { it.second })
    }

    @Test
    fun theDefaultTakenNoDefaultButton() {
        // 快捷键图标位置 右上: 标点位置's default is where it is
        prefs.set(keyboard.swipeUpPosition, KeyMarkPosition.BottomRight)
        prefs.set(keyboard.swipeDownPosition, KeyMarkPosition.TopRight)
        st.open(SettingsRoute.Keys)
        val d = dialog(R.string.swipe_up_position)
        assertEquals("右上（下滑图标在这里）" to false, rows(d)[0])
        assertNull(d.getButton(DialogInterface.BUTTON_NEUTRAL)?.takeIf { it.isShown })
    }

    @Test
    fun onePlaceStoredInBothReadsAsTheSymbolMoved() {
        prefs.set(keyboard.swipeUpPosition, KeyMarkPosition.TopLeft)
        prefs.set(keyboard.swipeDownPosition, KeyMarkPosition.TopLeft)
        st.open(SettingsRoute.Keys)
        assertEquals("左下", st.pref(R.string.swipe_up_position).summary)
        assertEquals("左上", st.pref(R.string.swipe_down_position).summary)
        assertEquals("左下（上滑图标在这里）" to false, rows(dialog(R.string.swipe_down_position))[3])
    }

    // the two in one place (a backup restored, not yet stored apart at a start), then the other changed:
    // the summary says where the keyboard draws it, as stored
    @Test
    @Uat("KB-66")
    fun onePlaceStoredInBothThenTheOtherChangedTheSummaryIsTheKeyboards() {
        prefs.set(keyboard.swipeUpPosition, KeyMarkPosition.TopLeft)
        prefs.set(keyboard.swipeDownPosition, KeyMarkPosition.TopLeft)
        st.open(SettingsRoute.Keys)
        assertEquals("左下", st.pref(R.string.swipe_up_position).summary)
        dialog(R.string.swipe_down_position).listView.performItemClick(null, 0, 0)
        st.idle()
        assertEquals(KeyMarkPosition.TopRight, keyboard.swipeDownPosition.getValue())
        assertEquals(KeyMarks.At.TopLeft, swipeSpots().symbol)
        assertEquals("左上", st.pref(R.string.swipe_up_position).summary)
        assertEquals(KeyMarkPosition.TopLeft, keyboard.swipeUpPosition.getValue())
    }

    // where the two settings put the marks, the keyboard floating, split or one-handed as upright (060-keys-unify
    // §6.2): z's 撤销 and its symbol; ⌫'s 清空 (上滑) and 恢复 (下滑), a mark left out where the key has no room for it
    // (floating's low keys: KeyMarksEverywhereUatTest's left out)
    private fun placedIn(mode: String) {
        prefs.set(keyboard.swipeUpPosition, KeyMarkPosition.BottomLeft)
        prefs.set(keyboard.swipeDownPosition, KeyMarkPosition.TopRight)
        KeyboardHarness().use { h ->
            h.start()
            val z = (h.key("z") as SceneShownKey).markPlaces()
            assertEquals(mode, listOf("TR") to "BL", z.shortcuts to z.symbol)
            val backspace = (h.keyNamed("backspace") as SceneShownKey).node
            val face = backspace.shape.bounds
            fun corner(icon: String): String? = backspace.ops(false).filterIsInstance<Op.Icon>().firstOrNull { it.name == icon }?.box?.let {
                (if (it.centerY < face.centerY) "T" else "B") + (if (it.centerX < face.centerX) "L" else "R")
            }
            assertEquals(mode, "BL", corner("delete-sweep"))
            assertTrue(mode, corner("restore").let { it == null || it == "TR" })
        }
    }

    @Test
    @Uat("KB-66")
    fun placedFloating() {
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        placedIn("悬浮")
    }

    @Test
    @Uat("KB-66")
    fun placedSplit() {
        prefs.set(ThemeManager.prefs.splitKeyboard, SplitKeyboardMode.Always)
        placedIn("分开")
    }

    @Test
    @Uat("KB-66")
    fun placedOneHanded() {
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        placedIn("单手")
    }
}
