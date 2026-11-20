/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.comparesScreenshots
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.Op
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

    private fun open(vararg more: Pair<String, KeyShortcut>) {
        val p = AppPrefs.getInstance().internal
        val set = more.fold(KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好"))) { s, (name, shortcut) -> s.with(name, shortcut) }
        prefs.setShortcuts(set)
        prefs.set(p.keyLists, KeyLists.of(mapOf(KeyId("full/b") to UserList.Off)).serialize())
        st.activity
        st.open(SettingsRoute.PopupOverrides)
    }

    private fun tab(title: Int) {
        var v: View = KeyboardHarness.all(decor).filterIsInstance<TextView>().first { it.text.toString() == st.s(title) }
        while (!v.hasOnClickListeners()) v = v.parent as View
        v.performClick()
        st.idle()
    }

    private fun key(label: String): View = KeyboardHarness.all(decor)
        .first { it.contentDescription?.toString()?.substringBefore(',') == label && it.hasOnClickListeners() }


    // the notes made off the main thread: waited for, no sleeping
    private fun fatesDone() {
        st.idle()
        (st.page as? PopupOverridesFragment)?.awaitFates()
        st.idle()
    }

    private fun capture(name: String): Unit = fatesDone().let { st.capture("KeyShortcutsPageScreenshotTest/$name") }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureScreen(name: String) {
        fatesDone()
        if (!comparesScreenshots) {
            // drawn all the same (see SCREENSHOT_SDK)
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
        assertEquals(1, sceneIcons(key("a")))
        assertEquals(0, sceneIcons(key("s")))
        assertTrue(KeyboardHarness.all(key("s")).filterIsInstance<SceneKeyCell>().single().keyNode!!.ops(false).any { it is Op.Text && it.text == "*" })
        capture("full")
        for ((title, name) in listOf(
            R.string.shortcut_page_nine to "nine",
            R.string.shortcut_page_g14 to "keys14",
            R.string.shortcut_page_g18 to "keys18",
            R.string.shortcut_page_zyfull to "zhuyin_full",
            R.string.shortcut_page_zy9 to "zhuyin_nine",
            R.string.shortcut_page_number to "number",
            R.string.shortcut_page_function to "function",
        )) {
            tab(title)
            capture(name)
        }
        // backspace's clear up and restore down, each its icon: no names cut short
        assertEquals(3, sceneIcons(key(st.s(R.string.fn_key_backspace))))
    }

    // 注音九键 mixes 14- and 24-wide keys: cells weighted by width, every face of a row one height
    @Test
    @Uat("ST-113")
    fun zhuyinNinesCapsAreOneHeightARow() {
        open()
        tab(R.string.shortcut_page_zy9)
        fatesDone()
        val rows = KeyboardHarness.all(decor).filterIsInstance<SceneKeyCell>().groupBy { it.bounds.top }.values
        assertTrue("${rows.size}", rows.size >= 4)
        rows.forEach { row ->
            val h = row.map { it.faceHeight }
            // within rounding (the chips were about 0.58 of the rest)
            assertTrue("faces $h", h.max() - h.min() <= 2.0)
        }
    }

    // every cap found on its board: none a stand-in
    @Test
    fun everyCapIsTheKeyItNames() {
        open()
        st.idle()
        var caps = 0
        for (title in listOf(
            R.string.shortcut_page_full, R.string.shortcut_page_nine, R.string.shortcut_page_g14, R.string.shortcut_page_g18,
            R.string.shortcut_page_zyfull, R.string.shortcut_page_function,
        )) {
            if (title != R.string.shortcut_page_full) tab(title)
            st.idle()
            for (c in KeyboardHarness.all(decor).filterIsInstance<SceneKeyCell>()) {
                val b = c.board ?: continue
                assertEquals("$title: ${b.shortcut} on ${b.layout}", b.shortcut, c.keyNode?.shortcut)
                caps++
            }
        }
        assertTrue(caps.toString(), caps > 40)
    }

    private fun sceneIcons(v: View) = KeyboardHarness.all(v).filterIsInstance<SceneKeyCell>()
        .sumOf { c -> c.keyNode?.ops(false).orEmpty().count { it is Op.Icon } }

    // at night the caps follow the keyboard theme, not the page's
    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-port-night-440dpi")
    fun theEnginesCapsInTheKeyboardsThemeAtNight() {
        open()
        st.idle()
        val b = KeyboardHarness.all(key("b")).filterIsInstance<SceneKeyCell>().single()
        assertEquals(0.4, b.labelAlpha, 0.0)
        capture("canvas_full_night")
        key("a").performClick()
        captureScreen("dialog_letter_night")
    }

    @Test
    @Uat("LS-53")
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi", fontScale = 1.5f)
    fun theLargestFont() {
        open()
        capture("full_font15")
        key("a").performClick()
        captureScreen("dialog_letter_font15")
    }

    @Test
    fun multiSelect() {
        open()
        var v: View = KeyboardHarness.all(decor).filterIsInstance<TextView>().first { it.text.toString() == st.s(R.string.swipe_multi_select) }
        while (!v.hasOnClickListeners()) v = v.parent as View
        v.performClick()
        key("q").performClick()
        capture("multi_select")
    }

    @Test
    fun theFourteenKeysCornersAreDrawnUnderTheirLetters() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.Bottom)
        open()
        tab(R.string.shortcut_page_g14)
        val qw = KeyboardHarness.all(key("QW")).filterIsInstance<SceneKeyCell>().single()
        assertTrue("${qw.width}x${qw.height} ${qw.keyNode?.ops(false)}", qw.keyNode?.ops(false).orEmpty().any { it is Op.Text && it.text == "1" })
        capture("keys14_bottom")
    }

    @Test
    @Uat("LS-53")
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-440dpi")
    fun landscape() {
        open()
        capture("full_landscape")
        tab(R.string.shortcut_page_function)
        capture("function_landscape")
        tab(R.string.shortcut_page_full)
        key("a").performClick()
        captureScreen("dialog_letter_landscape")
    }

    @Test
    fun theMarksWhereTheirSettingsSay() {
        for (at in KeyMarkPosition.entries.filter { it != KeyMarkPosition.TopLeft }) {
            if (at == KeyMarkPosition.TopRight) prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.BottomRight)
            prefs.set(AppPrefs.getInstance().keyboard.swipeDownPosition, at)
            open()
            assertEquals(if (at == KeyMarkPosition.None) 0 else 1, sceneIcons(key("a")))
            capture("full_${at.name}")
            tab(R.string.shortcut_page_function)
            capture("function_${at.name}")
            prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.TopRight)
        }
        prefs.set(AppPrefs.getInstance().keyboard.swipeDownPosition, KeyMarkPosition.BottomRight)
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.TopLeft)
        open()
        capture("full_symbols_TopLeft")
    }

    @Test
    fun noSymbolsWhereThePunctuationPositionIsNone() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.None)
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
        val letter = ShadowDialog.getLatestDialog() as AlertDialog
        KeyboardHarness.all(letter.window!!.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == st.s(R.string.shortcut_change) }.performClick()
        st.idle()
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
