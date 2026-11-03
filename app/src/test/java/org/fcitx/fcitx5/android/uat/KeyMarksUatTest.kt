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
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.ShortcutMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the keys draw their marks (uat/02 KB-66): every shortcut's where 快捷键标记位置 says, the
 * symbol on the right by the way that swipes to it (the other right corner where the shortcut is),
 * the same the first time the keyboard opens as after any setting changes. 0.5.1's testers saw the
 * symbols pushed to the bottom right by the shortcuts at first, then the shortcuts bottom right after
 * a setting changed: they followed the way 滑动 swiped to them.
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

    // [v]'s place on [key]: T(op), M(iddle) or B(ottom); L(eft), C(entre) or R(ight); - for none drawn
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

    private fun at(position: ShortcutMarkPosition) {
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, position)
        h.idle()
    }

    @Test
    @Uat("KB-66")
    fun theShortcutsWhereChosenTheSymbolsByTheirWay() {
        h.start()
        // the default: 左上; 滑动 上滑快捷键，下滑符号, so the symbols bottom right
        assertEquals("a TL BR", marksOf("a"))
        assertEquals("z TL BR", marksOf("z"))
        assertEquals("s - BR", marksOf("s"))
        assertEquals("清除 up, 恢复 down", "TL,BL", backspaceMarks())
        at(ShortcutMarkPosition.BottomLeft)
        assertEquals("a BL BR", marksOf("a"))
        assertEquals("BL,TL", backspaceMarks())
        // z's 「，」 swiped down, bottom right: the shortcut chosen there, it goes up
        at(ShortcutMarkPosition.BottomRight)
        assertEquals("z BR TR", marksOf("z"))
        assertEquals("s - BR", marksOf("s"))
        assertEquals("BR,TR", backspaceMarks())
        at(ShortcutMarkPosition.TopRight)
        assertEquals("z TR BR", marksOf("z"))
        at(ShortcutMarkPosition.Bottom)
        assertEquals("z BC BR", marksOf("z"))
        assertEquals("BC,TC", backspaceMarks())
        // the symbols swiped up: top right, out of a top right shortcut's way
        prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, SwipeLayersChoice.SymbolUpShortcutDown)
        h.idle()
        assertEquals("z BC TR", marksOf("z"))
        at(ShortcutMarkPosition.TopRight)
        assertEquals("z TR BR", marksOf("z"))
        assertEquals("s - TR", marksOf("s"))
    }

    @Test
    @Uat("KB-66")
    fun theFirstOpenIsAsAfterAnySettingChanges() {
        h.start()
        val first = marks()
        // each position chosen with the keyboard up, and the keyboard opened anew with it: the same
        for (position in ShortcutMarkPosition.entries) {
            at(position)
            val changed = marks()
            fresh().start()
            assertEquals(position.name, changed, marks())
        }
        at(ShortcutMarkPosition.TopLeft)
        assertEquals(first, marks())
        // the testers' second keyboard: swiped the other way and back
        for (choice in listOf(SwipeLayersChoice.SymbolUpShortcutDown, SwipeLayersChoice.ShortcutUpSymbolDown)) {
            prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, choice)
            h.idle()
            val changed = marks()
            fresh().start()
            assertEquals(choice.name, changed, marks())
        }
        assertEquals(first, marks())
        // a shortcut changed and back, 标点位置 changed and back
        val shortcuts = AppPrefs.getInstance().internal.keyShortcuts
        prefs.set(shortcuts, KeyShortcuts.Empty.with("full/s", KeyShortcut.Text("你好")).serialize())
        h.idle()
        assertEquals("s TL BR", marksOf("s"))
        prefs.set(shortcuts, "")
        h.idle()
        assertEquals(first, marks())
        prefs.set(ThemeManager.prefs.punctuationPosition, PunctuationPosition.Bottom)
        h.idle()
        prefs.set(ThemeManager.prefs.punctuationPosition, PunctuationPosition.TopRight)
        h.idle()
        assertEquals(first, marks())
        fresh().start()
        assertEquals(first, marks())
    }
}
