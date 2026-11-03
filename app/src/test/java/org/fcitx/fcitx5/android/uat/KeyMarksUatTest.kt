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
 * Where the keys draw their marks (uat/02 KB-64): a shortcut's in the top left corner, the symbol
 * where 标点位置 puts it, the same the first time the keyboard opens as after any setting changes.
 * 0.5.1's testers saw the symbols pushed to the bottom right by the shortcuts at first, then the
 * shortcuts bottom right after a setting changed: they followed the way 滑动 swiped to them.
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

    // [v]'s corner of [key]: T(op) or B(ottom), L(eft) or R(ight); - for none drawn
    private fun corner(key: View, v: View?): String {
        if (v == null || !h.shown(v)) return "-"
        val k = h.box(key)
        val b = h.box(v)
        return (if (b.centerY() < k.centerY()) "T" else "B") + (if (b.centerX() < k.centerX()) "L" else "R")
    }

    // every key's marks: its top left and bottom left shortcut's corners, its symbol's
    private fun marks(): List<String> = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyView>().filter { h.shown(it) }.map { k ->
        val name = (k as? TextKeyView)?.mainText?.text ?: k.contentDescription
        val symbol = (k as? AltTextKeyView)?.altText
        "$name ${corner(k, k.topLeftMark)} ${corner(k, k.bottomLeftMark)} ${corner(k, symbol)}"
    }.toList()

    private fun marksOf(name: String) = marks().first { it.startsWith("$name ") }

    @Test
    @Uat("KB-64")
    fun theShortcutsTopLeftTheSymbolsTopRight() {
        h.start()
        assertEquals("a TL - TR", marksOf("a"))
        assertEquals("y TL - TR", marksOf("y"))
        assertEquals("s - - TR", marksOf("s"))
        val backspace = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyView>().first { it.def.viewId == R.id.button_backspace }
        assertEquals("清除 up, 恢复 down", "TL BL", "${corner(backspace, backspace.topLeftMark)} ${corner(backspace, backspace.bottomLeftMark)}")
    }

    @Test
    @Uat("KB-64")
    fun theFirstOpenIsAsAfterAnySettingChanges() {
        h.start()
        val first = marks()
        // the testers' second keyboard: swipes the other way, the marks where they were
        for (choice in listOf(SwipeLayersChoice.SymbolUpShortcutDown, SwipeLayersChoice.ShortcutUpSymbolDown)) {
            prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, choice)
            h.idle()
            assertEquals(choice.name, first, marks())
        }
        // a shortcut changed and back, 标点位置 changed and back
        val shortcuts = AppPrefs.getInstance().internal.keyShortcuts
        prefs.set(shortcuts, KeyShortcuts.Empty.with("full/s", KeyShortcut.Text("你好")).serialize())
        h.idle()
        assertEquals("s TL - TR", marksOf("s"))
        prefs.set(shortcuts, "")
        h.idle()
        assertEquals(first, marks())
        prefs.set(ThemeManager.prefs.punctuationPosition, PunctuationPosition.Bottom)
        h.idle()
        prefs.set(ThemeManager.prefs.punctuationPosition, PunctuationPosition.TopRight)
        h.idle()
        assertEquals(first, marks())
        // the keyboard made anew, as the next time it opens
        fresh().start()
        assertEquals(first, marks())
    }
}
