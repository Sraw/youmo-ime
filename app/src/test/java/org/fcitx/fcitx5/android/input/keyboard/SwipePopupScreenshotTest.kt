/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.ClipboardManager
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.shortcut.EditMenuLayout
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a swipe shows while the finger is still down: a letter's shortcut named in its bubble, at
 * the size of the keys' words and no wider than [SwipePreviewLabel] lets it be; the edit menu up
 * where the notices go, clear of the finger. Against the goldens under src/test/screenshots,
 * recorded again with `-Proborazzi.test.record=true` when the look changes on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class SwipePopupScreenshotTest {

    @get:Rule
    val prefs = PrefsRule()

    // q a long text, w 快速输入, e 恢复刚删除的: the longest words a bubble shows
    private fun longShortcuts() = prefs.set(
        AppPrefs.getInstance().internal.keyShortcuts,
        KeyShortcuts.Empty
            .with("full/q", KeyShortcut.Text("我的邮箱是 someone@example.com"))
            .with("full/w", KeyShortcut.Command(KeyCommand.QuickInput))
            .with("full/e", KeyShortcut.Command(KeyCommand.Restore))
            .serialize(),
    )

    // [label]'s key, whichever draws it (a key view, the canvas's scene key): a finger goes where it is
    private fun KeyboardHarness.keyOf(label: String): ShownKey = key(label)

    // [label]'s key swiped up past the threshold and held there, the bubble shown
    private fun KeyboardHarness.held(label: String, name: String) {
        val key = keyOf(label)
        val finger = finger(key, at(key))
        for (i in 1..5) finger.move(at(key, dy = -10f * i, ms = 16))
        capture("SwipePopupScreenshotTest/$name", inputView)
        finger.up(at(key))
    }

    // [label]'s key swiped down to its symbol and held: a character in the bubble, scaled as the keys' text
    // (紧凑, floating). Held still, the long press would show its keys over it before the shot
    private fun KeyboardHarness.symbolHeld(label: String, name: String) {
        val key = keyOf(label)
        val finger = finger(key, at(key))
        for (i in 1..5) finger.move(at(key, dy = 10f * i, ms = 16))
        capture("SwipePopupScreenshotTest/$name", inputView)
        finger.up(at(key))
    }

    private fun KeyboardHarness.spaceHeld(name: String) {
        val space = inputView.findViewById<View>(R.id.button_space)
        val finger = finger(space, at(space))
        for (i in 1..6) finger.move(at(space, dy = -10f * i, ms = 16))
        capture("SwipePopupScreenshotTest/$name", inputView)
        finger.up(at(space))
    }

    private fun shoot(suffix: String, canvas: Boolean = false) {
        longShortcuts()
        // the keys drawn by the canvas, their popups by the engine (060-render-p2 S2): pictures of their own
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, canvas)
        val prefix = if (canvas) "canvas_" else ""
        // the edit menu's test copies "hello": its entry, kept in the app's object, would show in the bar of a later shot,
        // and one stored late (the clipboard's own thread, a test run before in the same JVM) would still reach the bar
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry = null
        prefs.set(AppPrefs.getInstance().clipboard.clipboardSuggestion, false)
        KeyboardHarness(ThemePreset.PixelLight).use { h ->
            h.start()
            h.symbolHeld("g", "${prefix}symbol_$suffix")
            h.held("a", "${prefix}select_all_$suffix")
            h.held("q", "${prefix}text_$suffix")
            h.held("w", "${prefix}quick_input_$suffix")
            h.held("e", "${prefix}restore_$suffix")
            h.spaceHeld("${prefix}edit_menu_$suffix")
        }
    }

    @Test
    fun upright() = shoot("port")

    @Test
    fun compact() {
        // 紧凑 is the level nearest both heights, so both are set (20 alone with landscape 49 is not it)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        shoot("compact")
    }

    @Test
    fun floating() {
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        shoot("floating")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-440dpi")
    fun turned() = shoot("land")

    // the same under the canvas: the bubbles and the edit menu the engine's scenes (060-render-p2 S2, §5.3's
    // differences: their text dp, not sp; their shadow drawn)
    @Test
    fun uprightOnTheCanvas() = shoot("port", canvas = true)

    @Test
    fun compactOnTheCanvas() {
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        shoot("compact", canvas = true)
    }

    @Test
    fun floatingOnTheCanvas() {
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        shoot("floating", canvas = true)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-440dpi")
    fun turnedOnTheCanvas() = shoot("land", canvas = true)

    // team-lead, 0.5.1: the letter in the bubble scaled as the keys' text, 紧凑 0.9 of the standard's
    @Test
    fun theLetterIsScaledAsTheKeys() {
        fun letterPx(): Float = KeyboardHarness(ThemePreset.PixelLight).use { h ->
            h.start()
            val key = h.key("g")
            val finger = h.finger(key, h.at(key))
            val size = KeyboardHarness.all(h.inputView).filterIsInstance<TextView>()
                .filter { h.shown(it) && it.text.toString() == "g" }
                .first { generateSequence(it.parent as? View) { p -> p.parent as? View }.none { p -> p is KeyView } }.textSize
            finger.up(h.at(key))
            size
        }
        val standard = letterPx()
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        assertEquals(standard * 0.9f, letterPx(), 0.5f)
    }

    // what the bubbles say: the words fitted, a cut text told by its ellipsis
    @Test
    @Uat("KB-65")
    fun theBubbleSaysTheShortcutFitted() {
        longShortcuts()
        KeyboardHarness(ThemePreset.PixelLight).use { h ->
            h.start()
            fun bubble(label: String): String {
                val key = h.key(label)
                val finger = h.finger(key, h.at(key))
                for (i in 1..5) finger.move(h.at(key, dy = -10f * i, ms = 16))
                val shown = KeyboardHarness.all(h.inputView).filterIsInstance<TextView>()
                    .filter { h.shown(it) && generateSequence(it.parent as? View) { p -> p.parent as? View }.none { p -> p is KeyView || p === h.bar } }
                    .map { it.text.toString() }.filter { it.isNotEmpty() }.toList()
                finger.up(h.at(key))
                return shown.last()
            }
            assertEquals("快速输入", bubble("w"))
            assertEquals("恢复刚删除的", bubble("e"))
            assertEquals("as much as fits at the least, then the ellipsis", "我的邮箱是 s…", bubble("q"))
        }
    }

    // KB-62: the edit menu over the app, above the keyboard, not under the finger; the notice there put
    // away; a cell left and lift copies, as when it was over the key
    @Test
    @Uat("KB-62")
    fun theEditMenuIsAboveTheKeyboardAndPicksAsBefore() {
        KeyboardHarness(ThemePreset.PixelLight).use { h ->
            h.start(prefill = { it.setText("hello") })
            h.service.say(R.string.text_copied)
            h.after(500)
            assertNotNull("a notice up first", h.notice())
            // the icons outside the keys: the bar's, then the menu's cells as well
            fun icons() = KeyboardHarness.all(h.inputView).filter { v ->
                v is ImageView && h.shown(v) && generateSequence(v.parent as? View) { it.parent as? View }.none { it is KeyView }
            }.toSet()
            val before = icons()
            val space = h.inputView.findViewById<View>(R.id.button_space)
            val finger = h.finger(space, h.at(space))
            for (i in 1..6) finger.move(h.at(space, dy = -10f * i, ms = 16))
            val cells = icons() - before
            assertEquals(5, cells.size)
            val keyboardTop = h.box(h.keyboard).top
            assertTrue("above the keyboard: ${cells.map(h::box)} over $keyboardTop", cells.all { h.box(it).bottom <= keyboardTop })
            h.after(500)
            assertNull("the notice put away", h.notice())
            finger.move(h.at(space, dx = -35f, dy = -60f, ms = 16))
            finger.up(h.at(space, dx = -35f, dy = -60f))
            h.idle()
            val clip = h.service.getSystemService(ClipboardManager::class.java).primaryClip
            assertEquals("copied", "hello", clip?.getItemAt(0)?.text?.toString())
        }
    }

    // KB-62: a failure said as a key failed is not put away by the menu (it would be lost unread): hidden
    // under it, back as it goes with nothing picked
    @Test
    @Uat("KB-62")
    fun aFailureIsHiddenUnderTheEditMenuAndBackAsItGoes() {
        KeyboardHarness(ThemePreset.PixelLight).use { h ->
            h.start(prefill = { it.setText("hello") })
            h.service.say("读不出来", steady = true)
            h.after(500)
            val said = h.notice()!!.contentView
            assertTrue("shown first", said.isShown)
            val space = h.inputView.findViewById<View>(R.id.button_space)
            val finger = h.finger(space, h.at(space))
            for (i in 1..6) finger.move(h.at(space, dy = -10f * i, ms = 16))
            h.after(500)
            assertNotNull("still up", h.notice())
            assertFalse("hidden under the menu", said.isShown)
            // down off the key: nothing picked, the menu gone
            finger.move(h.at(space, dy = 200f, ms = 16))
            finger.up(h.at(space, dy = 200f))
            h.idle()
            assertTrue("back", h.notice()?.contentView?.isShown == true)
            assertEquals("nothing typed", "hello", h.field.text.toString())
        }
    }

    // TalkBack on the edit menu: each cell is read by its name as the settings and the swipe's preview
    // say it (全选, 剪切 ...), never the command's enum name
    @Test
    @Uat("KB-62")
    fun theEditMenusCellsAreReadByTheirLabels() {
        KeyboardHarness(ThemePreset.PixelLight).use { h ->
            h.start(prefill = { it.setText("hello") })
            fun icons() = KeyboardHarness.all(h.inputView).filter { v ->
                v is ImageView && h.shown(v) && generateSequence(v.parent as? View) { it.parent as? View }.none { it is KeyView }
            }.toSet()
            val before = icons()
            val space = h.inputView.findViewById<View>(R.id.button_space)
            val finger = h.finger(space, h.at(space))
            for (i in 1..6) finger.move(h.at(space, dy = -10f * i, ms = 16))
            val read = (icons() - before).sortedBy { h.box(it).left }.map { it.contentDescription?.toString() }
            assertEquals(EditMenuLayout.CELLS.map { h.service.getString(KeyShortcutLooks.of(it).label) }, read)
            finger.move(h.at(space, dy = 200f, ms = 16))
            finger.up(h.at(space, dy = 200f))
            h.idle()
        }
    }

    // review L1: the menu taken down with everything (another window, the keyboard's onDetached): the
    // failure back then, and the finger lifted after does not bring it back a second time
    @Test
    fun aFailureIsBackWhenTheMenuGoesWithEverythingAndOnlyOnce() {
        KeyboardHarness(ThemePreset.PixelLight).use { h ->
            h.start(prefill = { it.setText("hello") })
            h.service.say("读不出来", steady = true)
            h.after(500)
            val said = h.notice()!!.contentView
            val popup = h.inputView.popup
            val gone = popup.onMenuGone
            var goes = 0
            popup.onMenuGone = { goes++; gone() }
            val space = h.inputView.findViewById<View>(R.id.button_space)
            val finger = h.finger(space, h.at(space))
            for (i in 1..6) finger.move(h.at(space, dy = -10f * i, ms = 16))
            h.after(500)
            assertFalse("hidden under the menu", said.isShown)
            popup.dismissAll()
            assertEquals(1, goes)
            assertTrue("back", said.isShown)
            finger.up(h.at(space, dy = -60f))
            h.idle()
            assertEquals("the lift after: nothing to bring back", 1, goes)
            assertTrue(h.notice()?.contentView?.isShown == true)
        }
    }

    @Test
    @Config(qualifiers = "en-w393dp-h851dp-port-440dpi")
    fun english() {
        prefs.set(
            AppPrefs.getInstance().internal.keyShortcuts,
            KeyShortcuts.Empty.with("full/w", KeyShortcut.Command(KeyCommand.TradSimp)).serialize(),
        )
        KeyboardHarness(ThemePreset.PixelLight).use { h ->
            h.start()
            h.held("w", "trad_simp_en")
        }
    }
}
