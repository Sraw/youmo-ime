/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.scene.Op
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.fcitx.fcitx5.android.comparesScreenshots
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * 按键字符和快捷键 (051-c §1.8): each tab's keys with their marks drawn as the keyboard draws them,
 * and the dialogs a key opens; against the goldens under src/test/screenshots, for the user to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class KeyShortcutsPageScreenshotTest {

    @get:Rule
    val prefs = PrefsRule()

    private val st = SettingsHarness()

    @After
    fun close() = st.close()

    private val decor get() = st.activity.window.decorView

    // q's text shortcut, b's long press turned off (faded, its @ gone); [more] set besides
    private fun open(vararg more: Pair<String, KeyShortcut>) {
        val p = AppPrefs.getInstance().internal
        val set = more.fold(KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好"))) { s, (name, shortcut) -> s.with(name, shortcut) }
        prefs.set(p.keyShortcuts, set.serialize())
        prefs.set(p.popupOverrides, PopupOverrides.Empty.with("b", emptyList()).serialize())
        st.activity
        st.open(SettingsRoute.PopupOverrides)
    }

    private fun tab(title: Int) {
        KeyboardHarness.all(decor).filterIsInstance<TextView>().first { it.text.toString() == st.s(title) }.performClick()
        st.idle()
    }

    // the key a screen reader calls [label] first
    private fun key(label: String): View = KeyboardHarness.all(decor)
        .first { it.contentDescription?.toString()?.substringBefore(',') == label && it.hasOnClickListeners() }


    private fun capture(name: String) = st.capture("KeyShortcutsPageScreenshotTest/$name")

    // a dialog over the page: the screen's every window
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureScreen(name: String) {
        st.idle()
        if (!comparesScreenshots) {
            // drawn all the same (see SCREENSHOT_SDK): the page, and the dialog over it
            st.capture("KeyShortcutsPageScreenshotTest/$name")
            val dialog = ShadowDialog.getLatestDialog().window!!.decorView
            dialog.draw(Canvas(Bitmap.createBitmap(dialog.width.coerceAtLeast(1), dialog.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
            return
        }
        captureScreenRoboImage(
            "src/test/screenshots/KeyShortcutsPageScreenshotTest/$name.png",
            RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)),
        )
    }

    @Test
    fun everyTabsKeysWithTheirMarks() {
        open()
        // a's select all top left and its @ top right (标点位置's default), as on the keyboard; s's * alone top right
        assertEquals(1, sceneIcons(key("a")))
        assertEquals(0, sceneIcons(key("s")))
        capture("full")
        for ((title, name) in listOf(
            R.string.shortcut_page_nine to "nine",
            R.string.shortcut_page_g14 to "keys14",
            R.string.shortcut_page_g18 to "keys18",
            R.string.shortcut_page_zyfull to "zhuyin_full",
            R.string.shortcut_page_number to "number",
            R.string.shortcut_page_function to "function",
        )) {
            tab(title)
            capture(name)
        }
        // the backspace's clear up and restore down, each its icon: no names cut short
        assertEquals(2, sceneIcons(key(st.s(R.string.fn_key_backspace))))
    }

    // the caps drawn by the engine where the canvas keyboard is on (060-render-p2 S6): the same marks as the
    // views', read from each cap's scene: a's select all an icon, s none; ⌫'s clear up and restore down, two
    private fun sceneIcons(v: View) = KeyboardHarness.all(v).filterIsInstance<SceneKeyCell>()
        .sumOf { c -> c.keyNode?.ops(false).orEmpty().count { it is Op.Icon } }

    @Test
    fun theEnginesCapsWithTheirMarks() {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        open()
        st.idle()
        assertEquals(1, sceneIcons(key("a")))
        assertEquals(0, sceneIcons(key("s")))
        // s's * in its corner, a text the engine draws
        assertTrue(KeyboardHarness.all(key("s")).filterIsInstance<SceneKeyCell>().single().keyNode!!.ops(false).any { it is Op.Text && it.text == "*" })
        capture("canvas_full")
        tab(R.string.shortcut_page_function)
        assertEquals(2, sceneIcons(key(st.s(R.string.fn_key_backspace))))
        capture("canvas_function")
    }

    // the settings at night, the keyboard's theme as it is: the caps' faces, words and the edited one's outline the
    // keyboard theme's, as light or dark as on the keyboard, whatever the page's own (S6 review); b's words faded alone
    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-port-night-440dpi")
    fun theEnginesCapsInTheKeyboardsThemeAtNight() {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        open()
        st.idle()
        val b = KeyboardHarness.all(key("b")).filterIsInstance<SceneKeyCell>().single()
        assertEquals(0.4, b.labelAlpha, 0.0)
        capture("canvas_full_night")
    }

    // the number keys draw no symbol (a digit alone): a shortcut there goes where one alone goes on
    // the keyboard, under the digit with 标点位置 下方, never into the corner a symbol would have left
    @Test
    fun aNumberKeysShortcutIsPlacedAsTheKeyboardPlacesIt() {
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.Bottom)
        open("num/5" to KeyShortcut.Command(KeyCommand.Copy))
        tab(R.string.shortcut_page_number)
        assertEquals(1, sceneIcons(key("5")))
        capture("number_bottom")
        // the 14 keys' corners drawn too, under their letters there
        tab(R.string.shortcut_page_g14)
        val qw = KeyboardHarness.all(key("QW")).filterIsInstance<SceneKeyCell>().single()
        assertTrue("${qw.width}x${qw.height} ${qw.keyNode?.ops(false)}", qw.keyNode?.ops(false).orEmpty().any { it is Op.Text && it.text == "1" })
        capture("keys14_bottom")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-440dpi")
    fun landscape() {
        open()
        capture("full_landscape")
        tab(R.string.shortcut_page_function)
        capture("function_landscape")
    }

    // 快捷键图标位置 and 标点位置 as the keyboard has them: each shortcut place's full and function keys,
    // the symbols top right or, the shortcuts there, bottom right; the symbols left (uat/02 KB-66)
    @Test
    fun theMarksWhereTheirSettingsSay() {
        for (at in KeyMarkPosition.entries.filter { it != KeyMarkPosition.TopLeft }) {
            if (at == KeyMarkPosition.TopRight) prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.BottomRight)
            prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, at)
            open()
            assertEquals(if (at == KeyMarkPosition.None) 0 else 1, sceneIcons(key("a")))
            capture("full_${at.name}")
            tab(R.string.shortcut_page_function)
            capture("function_${at.name}")
            prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.TopRight)
        }
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, KeyMarkPosition.BottomRight)
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.TopLeft)
        open()
        capture("full_symbols_TopLeft")
    }

    // 标点位置 无: no symbols on the page, as on the keyboard; the shortcuts still there
    @Test
    fun noSymbolsWhereThePunctuationPositionIsNone() {
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.None)
        open()
        assertEquals(1, sceneIcons(key("a")))
        capture("full_symbols_None")
    }

    @Test
    fun theDialogsAKeyOpens() {
        open()
        key("a").performClick()
        st.idle()
        captureScreen("dialog_letter")
        // 换: the commands to pick from
        val letter = ShadowDialog.getLatestDialog() as AlertDialog
        KeyboardHarness.all(letter.window!!.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == st.s(R.string.shortcut_change) }.performClick()
        st.idle()
        // voice input among them; quick input after the clipboard
        val commands = KeyboardHarness.all((ShadowDialog.getLatestDialog() as AlertDialog).window!!.decorView)
            .filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(commands.toString(), st.s(R.string.shortcut_paste) in commands)
        assertTrue(commands.toString(), st.s(R.string.shortcut_voice) in commands)
        assertEquals(commands.toString(), commands.indexOf(st.s(R.string.shortcut_clipboard)) + 1, commands.indexOf(st.s(R.string.quick_phrases)))
        captureScreen("dialog_commands")
        (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
        letter.dismiss()
        st.idle()

        tab(R.string.shortcut_page_nine)
        key("2").performClick()
        st.idle()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        captureScreen("dialog_nine")
        ShadowDialog.getLatestDialog().dismiss()
        st.idle()

        tab(R.string.shortcut_page_function)
        key(st.s(R.string.fn_key_space)).performClick()
        st.idle()
        captureScreen("dialog_space")
        ShadowDialog.getLatestDialog().dismiss()
    }
}
