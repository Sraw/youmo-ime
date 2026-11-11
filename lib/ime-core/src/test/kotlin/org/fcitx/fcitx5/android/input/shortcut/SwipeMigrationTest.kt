/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.keyboard.KeyMarks.At
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeMigrationTest {

    private val none = KeyShortcuts.Empty
    private fun of(layers: SwipeLayers?, upgraded: Boolean = true, shortcuts: KeyShortcuts = none, symbolAt: At = At.TopRight, shortcutAt: At = At.TopLeft) =
        SwipeMigration.of(layers, upgraded, shortcuts, symbolAt, shortcutAt)

    // what 0.5's KeySwipe made of a swipe on [key]: S the symbol, a shortcut, T a tap, N its own
    private fun old(layers: SwipeLayers, up: Boolean, symbol: Boolean, shortcut: KeyShortcut): String {
        if (layers.up == Layer.Off && layers.down == Layer.Off) return "N"
        return when (if (up) layers.up else layers.down) {
            Layer.Symbol -> if (symbol) "S" else "T"
            Layer.Shortcut -> if (shortcut == KeyShortcut.None) "T" else shortcut.toString()
            Layer.Off -> "T"
        }
    }

    private fun now(r: SwipeMigration.Result, key: String, up: Boolean, symbol: Boolean): String {
        val k = KeySwipe.Key(char = true, r.slots.resolve(key, true), r.slots.resolve(key, false), hasFirst = symbol)
        return when (val o = KeySwipe.decide(k, KeySwipe.Ways(r.up, r.down), if (up) -1 else 1, true, false, false, false)) {
            KeySwipe.Outcome.Symbol -> "S"
            KeySwipe.Outcome.Tap -> "T"
            KeySwipe.Outcome.None -> "N"
            is KeySwipe.Outcome.Shortcut -> o.shortcut.toString()
            KeySwipe.Outcome.Cancel -> "C"
        }
    }

    @Test
    fun everyOldChoiceSwipesAsItDid() {
        val shortcuts = KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("Q")).with("full/a", KeyShortcut.None)
        for (layers in SwipeLayers.entries) {
            val r = of(layers, shortcuts = shortcuts)
            for (key in listOf("full/a", "full/c", "full/q", "full/s", "t9/2", "g14/cv")) for (up in listOf(true, false)) for (symbol in listOf(true, false)) {
                val shortcut = shortcuts.resolve(key)
                assertEquals("$layers $key up=$up symbol=$symbol", old(layers, up, symbol, shortcut), now(r, key, up, symbol))
            }
        }
    }

    @Test
    fun orientationAndWays() {
        assertEquals(SwipeOrientation.FirstDown, of(SwipeLayers.Default).slots.orientation)
        assertEquals(SwipeOrientation.FirstUp, of(SwipeLayers(Layer.Symbol, Layer.Shortcut)).slots.orientation)
        assertEquals(SwipeOrientation.FirstUp, of(SwipeLayers(Layer.Off, Layer.Shortcut)).slots.orientation)
        assertEquals(SwipeOrientation.FirstDown, of(SwipeLayers(Layer.Off, Layer.Off)).slots.orientation)
        val upOnly = of(SwipeLayers(Layer.Shortcut, Layer.Off))
        assertTrue(upOnly.up)
        assertFalse(upOnly.down)
    }

    @Test
    fun aNewInstallIsFreshAnUpgradeKeepsTheOldDefault() {
        assertEquals(SwipeMigration.Fresh, of(null, upgraded = false))
        val up = of(null, upgraded = true)
        assertEquals(SwipeOrientation.FirstDown, up.slots.orientation)
        assertEquals("the shortcut up, where 快捷键图标位置 was", At.TopLeft, up.upAt)
        assertEquals("the symbol down, where 标点位置 was", At.TopRight, up.downAt)
    }

    @Test
    fun aNewInstallWithSomethingSetIsMigratedTheNewWay() {
        val r = of(null, upgraded = false, shortcuts = KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("Q")))
        assertEquals(SwipeOrientation.FirstUp, r.slots.orientation)
        assertEquals(SwipeSlot.Text("Q"), r.slots.resolve("full/q", up = false))
    }

    @Test
    fun functionKeysKeepTheirWays() {
        val shortcuts = KeyShortcuts.Empty
            .with(KeyShortcuts.functionKey("space", up = true), KeyShortcut.None)
            .with(KeyShortcuts.functionKey("return", up = false), KeyShortcut.Command(KeyCommand.Paste))
        val r = of(SwipeLayers.Default, shortcuts = shortcuts)
        assertEquals(SwipeSlot.Empty, r.slots.resolve("fn/space", up = true))
        assertEquals(SwipeSlot.Command(KeyCommand.Paste), r.slots.resolve("fn/return", up = false))
        assertEquals(SwipeSlot.Command(KeyCommand.Clear), r.slots.resolve("fn/backspace", up = true))
    }

    @Test
    fun positionsFollowWhatTheyMarked() {
        val r = of(SwipeLayers(Layer.Symbol, Layer.Shortcut), symbolAt = At.Bottom, shortcutAt = At.BottomLeft)
        assertEquals(At.Bottom, r.upAt)
        assertEquals(At.BottomLeft, r.downAt)
        // both at the same place: 0.5 drew the symbol elsewhere (settled)
        val same = of(SwipeLayers.Default, symbolAt = At.TopLeft, shortcutAt = At.TopLeft)
        assertEquals(At.TopLeft, same.upAt)
        assertTrue(same.downAt != At.TopLeft)
    }
}
