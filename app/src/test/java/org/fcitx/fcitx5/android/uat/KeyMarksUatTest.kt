/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.res.Configuration
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the keys draw their marks (uat/02 KB-66): every shortcut's where 快捷键图标位置 says, every
 * symbol where 标点位置 says, whichever way swipes to them, the same the first time the keyboard opens
 * as after any setting changes. 0.5.1's testers saw the symbols pushed to the bottom right by the
 * shortcuts at first, then the shortcuts bottom right after a setting changed: they followed the way
 * 滑动 swiped to them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyMarksUatTest {

    private var made: KeyboardHarness? = null
    private val h get() = made ?: fresh()

    private fun fresh(): KeyboardHarness {
        made?.close()
        return KeyboardHarness().also { made = it }
    }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        made?.close()
    }

    // [v]'s place on [key]: T(op) or B(ottom); L(eft), C(entre) or R(ight); - for none drawn
    private fun corner(key: View, v: View?): String {
        if (v == null || !h.shown(v) || v.alpha == 0f) return "-"
        val k = h.box(key)
        val b = h.box(v)
        val x = when {
            kotlin.math.abs(b.centerX() - k.centerX()) <= 1 -> "C"
            b.centerX() < k.centerX() -> "L"
            else -> "R"
        }
        return (if (b.centerY() < k.centerY()) "T" else "B") + x
    }

    // every key's marks: its shortcuts' places, its symbol's
    private fun marks(): List<String> = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyView>().filter { h.shown(it) }.map { k ->
        val name = (k as? TextKeyView)?.mainText?.text ?: k.contentDescription
        val symbol = (k as? AltTextKeyView)?.altText
        "$name ${k.shortcutMarks.values.joinToString(",") { corner(k, it) }.ifEmpty { "-" }} ${corner(k, symbol)}"
    }.toList()

    private fun marksOf(name: String) = marks().first { it.startsWith("$name ") }

    private fun backspace() = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyView>().first { it.def.viewId == R.id.button_backspace }

    private fun backspaceMarks() = backspace().let { b -> b.shortcutMarks.values.joinToString(",") { corner(b, it) } }

    private fun at(position: KeyMarkPosition) {
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, position)
        h.idle()
    }

    private fun symbolsAt(position: KeyMarkPosition) {
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, position)
        h.idle()
    }

    @Test
    @Uat("KB-66")
    fun eachWhereItsSettingSays() {
        h.start()
        // the defaults: 快捷键图标位置 左上, 标点位置 右上
        assertEquals("a TL TR", marksOf("a"))
        assertEquals("z TL TR", marksOf("z"))
        assertEquals("s - TR", marksOf("s"))
        assertEquals("清除 up, 恢复 down", "TL,BL", backspaceMarks())
        at(KeyMarkPosition.BottomLeft)
        assertEquals("a BL TR", marksOf("a"))
        assertEquals("BL,TL", backspaceMarks())
        at(KeyMarkPosition.BottomRight)
        assertEquals("z BR TR", marksOf("z"))
        assertEquals("s - TR", marksOf("s"))
        assertEquals("BR,TR", backspaceMarks())
        at(KeyMarkPosition.Bottom)
        assertEquals("z BC TR", marksOf("z"))
        assertEquals("BC,TC", backspaceMarks())
        // 滑动 the other way moves nothing
        prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, SwipeLayersChoice.SymbolUpShortcutDown)
        h.idle()
        assertEquals("z BC TR", marksOf("z"))
        at(KeyMarkPosition.BottomRight)
        symbolsAt(KeyMarkPosition.TopLeft)
        assertEquals("z BR TL", marksOf("z"))
        assertEquals("s - TL", marksOf("s"))
        symbolsAt(KeyMarkPosition.BottomLeft)
        assertEquals("z BR BL", marksOf("z"))
        symbolsAt(KeyMarkPosition.Bottom)
        assertEquals("under the letter", "z BR BC", marksOf("z"))
        // 无: that one not drawn, the swipe still there
        symbolsAt(KeyMarkPosition.None)
        assertEquals("z BR -", marksOf("z"))
        at(KeyMarkPosition.None)
        assertEquals("z - -", marksOf("z"))
        assertEquals("", backspaceMarks())
    }

    @Test
    @Uat("KB-66")
    fun onePlaceStoredInBothTheSymbolYields() {
        // the settings page offers neither; an import or a backup can write them
        at(KeyMarkPosition.TopRight)
        h.start()
        assertEquals("z TR BR", marksOf("z"))
        at(KeyMarkPosition.TopLeft)
        symbolsAt(KeyMarkPosition.TopLeft)
        assertEquals("z TL BL", marksOf("z"))
        at(KeyMarkPosition.Bottom)
        symbolsAt(KeyMarkPosition.Bottom)
        assertEquals("z BC TR", marksOf("z"))
    }

    @Test
    @Uat("KB-66")
    fun theFirstOpenIsAsAfterAnySettingChanges() {
        h.start()
        val first = marks()
        // each place chosen with the keyboard up, and the keyboard opened anew with it: the same
        for (position in KeyMarkPosition.entries) {
            at(position)
            val changed = marks()
            fresh().start()
            assertEquals(position.name, changed, marks())
        }
        at(KeyMarkPosition.TopLeft)
        for (position in KeyMarkPosition.entries.filter { it != KeyMarkPosition.TopLeft }) {
            symbolsAt(position)
            val changed = marks()
            fresh().start()
            assertEquals("标点位置 $position", changed, marks())
        }
        symbolsAt(KeyMarkPosition.TopRight)
        assertEquals(first, marks())
        // the testers' second keyboard: swiped the other way and back
        for (choice in listOf(SwipeLayersChoice.SymbolUpShortcutDown, SwipeLayersChoice.ShortcutUpSymbolDown)) {
            prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, choice)
            h.idle()
            val changed = marks()
            fresh().start()
            assertEquals(choice.name, changed, marks())
            assertEquals(choice.name, first, marks())
        }
        // a shortcut changed and back
        val shortcuts = AppPrefs.getInstance().internal.keyShortcuts
        prefs.set(shortcuts, KeyShortcuts.Empty.with("full/s", KeyShortcut.Text("你好")).serialize())
        h.idle()
        assertEquals("s TL TR", marksOf("s"))
        prefs.set(shortcuts, "")
        h.idle()
        assertEquals(first, marks())
        fresh().start()
        assertEquals(first, marks())
    }

    // a letter's long press turned off with the keyboard up: its corner emptied, its shortcut's mark
    // once stayed where it was placed beside the symbol, not where a keyboard made with the setting puts it
    @Test
    @Uat("KB-66")
    fun aLongPressTurnedOffPlacesTheMarksAsAKeyboardMadeWithIt() {
        prefs.set(AppPrefs.getInstance().internal.keyShortcuts, KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好")).serialize())
        h.start()
        val before = marksOf("q")
        prefs.set(AppPrefs.getInstance().internal.popupOverrides, "q")
        h.idle()
        val changed = marksOf("q")
        assertTrue(before, before != changed)
        fresh().start()
        assertEquals(marksOf("q"), changed)
    }

    // either remakes the keyboard (FcitxInputMethodService.recreateInputViewPrefs): KeySwipes places
    // the marks of the keys it has swipes for again, but a key it has none for (a keyboard without
    // shortcuts, 五笔's every root) lays its symbol out only as it is made
    @Test
    @Uat("KB-66")
    fun eitherChangedTheKeyboardIsMadeAnew() {
        h.start()
        for ((pref, position) in listOf(
            AppPrefs.getInstance().keyboard.punctuationPosition to KeyMarkPosition.BottomRight,
            AppPrefs.getInstance().keyboard.shortcutMarkPosition to KeyMarkPosition.BottomLeft,
        )) {
            val before = h.inputView
            prefs.set(pref, position)
            h.idle()
            assertNotSame(pref.key, before, h.inputView)
        }
    }

    // turned with the marks placed: a symbol at 底部 under the letter upright, in the top right corner
    // on a landscape key (KeyMarks.onKeys), as a keyboard made landscape has it
    @Test
    @Uat("KB-66")
    fun turnedTheSymbolWhereTheNewOrientationPutsIt() {
        symbolsAt(KeyMarkPosition.Bottom)
        h.start()
        val q = h.key("q") as AltTextKeyView
        assertEquals(KeyMarks.Slot.Below, q.symbolSlot)
        val landscape = Configuration(q.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
        q.dispatchConfigurationChanged(landscape)
        assertEquals(KeyMarks.Slot.TopRight, q.symbolSlot)
    }
}
