/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
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
 * Every shortcut's mark where 快捷键图标位置 says, every symbol where 标点位置 says, whichever way swipes to them, the
 * first time the keyboard opens as after any setting changes (they once followed 滑动's way).
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

    // T(op) or B(ottom), L(eft), C(entre) or R(ight); - for none drawn
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

    private fun place(shortcuts: KeyMarkPosition, symbols: KeyMarkPosition) {
        at(shortcuts)
        symbolsAt(symbols)
    }

    // turned by h.turn() (the input started again), not KeyboardHarness.inLandscape's onConfigurationChanged
    private fun inLandscapeRestarted(block: () -> Unit) {
        RuntimeEnvironment.setQualifiers("+land")
        h.turn()
        try {
            block()
        } catch (e: Throwable) {
            // a turn failing on a harness the row broke is added to the row's failure, not put in its place
            RuntimeEnvironment.setQualifiers("+port")
            runCatching { h.turn() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        RuntimeEnvironment.setQualifiers("+port")
        h.turn()
    }

    @Test
    @Uat("KB-66")
    fun eachWhereItsSettingSays() {
        h.start()
        assertEquals("a TL TR", marksOf("a"))
        assertEquals("z TL TR", marksOf("z"))
        assertEquals("s - TR", marksOf("s"))
        assertEquals("清除 at 上滑's place, 恢复 at 下滑's", "TR,TL", backspaceMarks())
        at(KeyMarkPosition.BottomLeft)
        assertEquals("a BL TR", marksOf("a"))
        assertEquals("TR,BL", backspaceMarks())
        at(KeyMarkPosition.BottomRight)
        assertEquals("z BR TR", marksOf("z"))
        assertEquals("s - TR", marksOf("s"))
        assertEquals("the owner's: 清除 top right, 恢复 bottom right", "TR,BR", backspaceMarks())
        at(KeyMarkPosition.Bottom)
        assertEquals("z BC TR", marksOf("z"))
        assertEquals("TR,BC", backspaceMarks())
        at(KeyMarkPosition.BottomRight)
        symbolsAt(KeyMarkPosition.TopLeft)
        assertEquals("z BR TL", marksOf("z"))
        assertEquals("s - TL", marksOf("s"))
        assertEquals("TL,BR", backspaceMarks())
        symbolsAt(KeyMarkPosition.BottomLeft)
        assertEquals("z BR BL", marksOf("z"))
        symbolsAt(KeyMarkPosition.Bottom)
        assertEquals("under the letter", "z BR BC", marksOf("z"))
        symbolsAt(KeyMarkPosition.None)
        assertEquals("z BR -", marksOf("z"))
        assertEquals("清除 not drawn", "BR", backspaceMarks())
        at(KeyMarkPosition.None)
        assertEquals("z - -", marksOf("z"))
        assertEquals("", backspaceMarks())
    }

    // 顶部: the top edge's middle; on a landscape keyboard, too low for it, the first corner the other's place is not
    @Test
    @Uat("KB-89")
    fun theTopIsTheTopEdgesMiddle() {
        h.start()
        assertRows(
            "下滑 at 顶部: the shortcut top centre, 恢复 too" to {
                place(shortcuts = KeyMarkPosition.Top, symbols = KeyMarkPosition.TopRight)
                assertEquals("z TC TR", marksOf("z"))
                assertEquals("TR,TC", backspaceMarks())
            },
            "上滑 at 顶部: the symbol over the letter, 清除 too" to {
                place(shortcuts = KeyMarkPosition.BottomLeft, symbols = KeyMarkPosition.Top)
                assertEquals("z BL TC", marksOf("z"))
                assertEquals("TC,BL", backspaceMarks())
            },
            "上滑 at 顶部, 下滑 at 底部: one at each edge's middle" to {
                place(shortcuts = KeyMarkPosition.Bottom, symbols = KeyMarkPosition.Top)
                assertEquals("z BC TC", marksOf("z"))
                assertEquals("TC,BC", backspaceMarks())
            },
            "the same in landscape: the symbol top right, the shortcut bottom right" to {
                place(shortcuts = KeyMarkPosition.Bottom, symbols = KeyMarkPosition.Top)
                inLandscapeRestarted {
                    assertEquals("z BR TR", marksOf("z"))
                    assertEquals("TR,BR", backspaceMarks())
                }
            },
            "swapped in landscape (上滑 底部, 下滑 顶部): each its own edge's right corner, the shortcut top right and the symbol bottom right, as 退格's ways (review 061-small S5)" to {
                place(shortcuts = KeyMarkPosition.Top, symbols = KeyMarkPosition.Bottom)
                inLandscapeRestarted { assertEquals("z TR BR", marksOf("z")) }
            },
            "下滑 alone at 顶部 in landscape, 上滑 at 右上: the top's other corner, 左上 (review 061-small N8; 右下 before)" to {
                place(shortcuts = KeyMarkPosition.Top, symbols = KeyMarkPosition.TopRight)
                inLandscapeRestarted {
                    assertEquals("z TL TR", marksOf("z"))
                    // ⌫'s own icon moved down for them on its narrow key: read as a mark at the bottom too
                    assertTrue(backspaceMarks(), backspaceMarks().endsWith("TR,TL"))
                }
            },
        )
    }

    // the default is the long press's first up: only turning it down shows the marks staying where they were
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
        for (choice in listOf(SwipeLayersChoice.SymbolUpShortcutDown, SwipeLayersChoice.ShortcutUpSymbolDown)) {
            prefs.setLayers(choice)
            h.idle()
            val changed = marks()
            fresh().start()
            assertEquals(choice.name, changed, marks())
            // 预设 turned the other way: the letters' marks stay; a function key's swipes stay, so its marks each go with its
            // way's place
            val function = setOf("Backspace", "Pinyin")
            fun letters(m: List<String>) = m.filter { it.substringBefore(' ') !in function }
            assertEquals(choice.name, letters(first), letters(marks()))
            val flipped = choice == SwipeLayersChoice.ShortcutUpSymbolDown
            assertEquals(choice.name, if (flipped) "TL,TR" else "TR,TL", backspaceMarks())
        }
        val turned = marks()
        val slots = AppPrefs.getInstance().internal.keySwipes
        val was = slots.getValue()
        prefs.setShortcuts(KeyShortcuts.Empty.with("full/s", KeyShortcut.Text("你好")))
        h.idle()
        assertEquals("s TL TR", marksOf("s"))
        prefs.set(slots, was)
        h.idle()
        assertEquals(turned, marks())
        fresh().start()
        assertEquals(turned, marks())
    }

    // a letter's long press turned off with the keyboard up: its shortcut's mark once stayed beside the symbol
    @Test
    @Uat("KB-66")
    fun aLongPressTurnedOffPlacesTheMarksAsAKeyboardMadeWithIt() {
        prefs.setShortcuts(KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好")))
        h.start()
        val before = marksOf("q")
        prefs.setList("full/q", off = true)
        h.idle()
        val changed = marksOf("q")
        assertTrue(before, before != changed)
        fresh().start()
        assertEquals(marksOf("q"), changed)
    }

    // KeySwipes places the marks of the keys it has swipes for again, but a key it has none for lays its symbol out
    // only as it is made
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

    // turned with the marks placed: a symbol at 底部 in the top right corner on a landscape key
    @Test
    @Uat("KB-66")
    fun turnedTheSymbolWhereTheNewOrientationPutsIt() {
        symbolsAt(KeyMarkPosition.Bottom)
        h.start()
        assertEquals("q - BC", marksOf("q"))
        inLandscapeRestarted { assertEquals("q - TR", marksOf("q")) }
    }

    private fun KeyboardHarness.keyboardShown(): View =
        inputView.findViewById<android.view.ViewGroup>(R.id.keyboard_view).getChildAt(0)
}
