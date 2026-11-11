/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
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
import org.robolectric.RuntimeEnvironment
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

    // every key's marks as the scene drew them (SceneShownKey.markPlaces): its shortcuts' places, its
    // symbol's; T(op) or B(ottom), L(eft), C(entre) or R(ight); - for none drawn
    private fun marks(): List<String> = h.keys().map { k ->
        val m = (k as SceneShownKey).markPlaces()
        "${k.label.ifEmpty { k.description }} ${m.shortcuts.joinToString(",").ifEmpty { "-" }} ${m.symbol ?: "-"}"
    }

    private fun marksOf(name: String) = marks().first { it.startsWith("$name ") }

    private fun backspaceMarks() = (h.keyNamed("backspace") as SceneShownKey).markPlaces().shortcuts.joinToString(",")

    private fun at(position: KeyMarkPosition) {
        prefs.set(AppPrefs.getInstance().keyboard.swipeDownPosition, position)
        h.idle()
    }

    private fun symbolsAt(position: KeyMarkPosition) {
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, position)
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
        assertEquals("清除 up over 恢复 down", "TL,BL", backspaceMarks())
        at(KeyMarkPosition.BottomRight)
        assertEquals("z BR TR", marksOf("z"))
        assertEquals("s - TR", marksOf("s"))
        assertEquals("TR,BR", backspaceMarks())
        at(KeyMarkPosition.Bottom)
        assertEquals("z BC TR", marksOf("z"))
        assertEquals("TC,BC", backspaceMarks())
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

    // the default is the long press's first up: only turning it down (the two places swapped with it) shows the
    // marks staying where they were
    @Test
    @Uat("KB-66")
    fun theSwipesTurnedTheMarksStay() {
        h.start()
        at(KeyMarkPosition.Bottom)
        assertEquals("z BC TR", marksOf("z"))
        prefs.setLayers(SwipeLayersChoice.ShortcutUpSymbolDown)
        h.idle()
        assertEquals("z BC TR", marksOf("z"))
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
            prefs.setLayers(choice)
            h.idle()
            val changed = marks()
            fresh().start()
            assertEquals(choice.name, changed, marks())
            assertEquals(choice.name, first, marks())
        }
        // a shortcut changed and back
        val slots = AppPrefs.getInstance().internal.keySwipes
        val was = slots.getValue()
        prefs.setShortcuts(KeyShortcuts.Empty.with("full/s", KeyShortcut.Text("你好")))
        h.idle()
        assertEquals("s TL TR", marksOf("s"))
        prefs.set(slots, was)
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
        prefs.setShortcuts(KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好")))
        h.start()
        val before = marksOf("q")
        prefs.set(AppPrefs.getInstance().internal.popupOverrides, "q")
        h.idle()
        val changed = marksOf("q")
        assertTrue(before, before != changed)
        fresh().start()
        assertEquals(marksOf("q"), changed)
    }

    // either remakes the keyboards (FcitxInputMethodService.remakeKeyboardsPrefs): KeySwipes places
    // the marks of the keys it has swipes for again, but a key it has none for (a keyboard without
    // shortcuts, 五笔's every root) lays its symbol out only as it is made
    @Test
    @Uat("KB-66")
    fun eitherChangedTheKeyboardIsMadeAnew() {
        h.start()
        for ((pref, position) in listOf(
            AppPrefs.getInstance().keyboard.swipeUpPosition to KeyMarkPosition.BottomRight,
            AppPrefs.getInstance().keyboard.swipeDownPosition to KeyMarkPosition.BottomLeft,
        )) {
            val before = h.keyboardShown()
            prefs.set(pref, position)
            h.idle()
            assertNotSame(pref.key, before, h.keyboardShown())
        }
    }

    // turned with the marks placed: a symbol at 底部 under the letter upright, in the top right corner
    // on a landscape key (KeyMarks.onKeys), as a keyboard made landscape has it
    @Test
    @Uat("KB-66")
    fun turnedTheSymbolWhereTheNewOrientationPutsIt() {
        symbolsAt(KeyMarkPosition.Bottom)
        h.start()
        assertEquals("q - BC", marksOf("q"))
        RuntimeEnvironment.setQualifiers("+land")
        h.turn()
        assertEquals("q - TR", marksOf("q"))
    }

    private fun KeyboardHarness.keyboardShown(): View =
        inputView.findViewById<android.view.ViewGroup>(R.id.keyboard_view).getChildAt(0)
}
