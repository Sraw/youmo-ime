/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.KeySwipe.Key
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe.Outcome
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe.Preview
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers.Layer
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.junit.Assert.assertEquals
import org.junit.Test

class KeySwipeTest {

    private val selectAll = KeyShortcut.Command(KeyCommand.SelectAll)
    private val copy = KeyShortcut.Command(KeyCommand.Copy)
    private val a = Key.Char(hasSymbol = true, shortcut = selectAll)
    private val s = Key.Char(hasSymbol = true, shortcut = KeyShortcut.None)
    private val space = Key.Function(up = KeyShortcut.Command(KeyCommand.EditMenu), down = KeyShortcut.None)
    private val off = SwipeLayers(Layer.Off, Layer.Off)

    @Suppress("LongParameterList")
    private fun up(
        key: Key,
        totalY: Int,
        layers: SwipeLayers = SwipeLayers.Default,
        swiped: Boolean = totalY != 0,
        consumed: Boolean = false,
        cancelled: Boolean = false,
        password: Boolean = false,
    ) = KeySwipe.decide(key, layers, totalY, swiped, consumed, cancelled, password)

    @Test
    fun byDefaultUpIsTheShortcutDownTheSymbol() {
        assertEquals(Outcome.Shortcut(selectAll), up(a, -1))
        assertEquals(Outcome.Symbol, up(a, 2))
    }

    @Test
    fun swapped() {
        val layers = SwipeLayers(Layer.Symbol, Layer.Shortcut)
        assertEquals(Outcome.Symbol, up(a, -1, layers))
        assertEquals(Outcome.Shortcut(selectAll), up(a, 1, layers))
    }

    @Test
    fun anEmptyLayerOrAWayOffIsATap() {
        assertEquals("no shortcut", Outcome.Tap, up(s, -1))
        assertEquals("long press off: no symbol", Outcome.Tap, up(Key.Char(false, selectAll), 1))
        assertEquals("the way off", Outcome.Tap, up(a, -1, SwipeLayers(Layer.Off, Layer.Symbol)))
    }

    @Test
    fun bothOffLeavesTheKeyAsItWas() {
        assertEquals(Outcome.None, up(a, -1, off))
        assertEquals(Outcome.None, up(a, 0, off, swiped = true))
    }

    @Test
    fun swipedAndBackIsCancelled() {
        assertEquals(Outcome.Cancel, up(a, 0, swiped = true))
        assertEquals(Outcome.Cancel, up(space, 0, swiped = true))
    }

    @Test
    fun noSwipeALongPressOrTheSystemsIsNotTheSwipes() {
        assertEquals(Outcome.None, up(a, 0))
        assertEquals(Outcome.None, up(a, -1, consumed = true))
        assertEquals(Outcome.None, up(a, -1, cancelled = true))
    }

    @Test
    fun aPasswordRunsNoCopyNorCut() {
        assertEquals(Outcome.Cancel, up(Key.Char(true, copy), -1, password = true))
        assertEquals(Outcome.Shortcut(selectAll), up(a, -1, password = true))
        assertEquals(Outcome.Cancel, up(Key.Function(copy, copy), 1, password = true))
    }

    @Test
    fun aFunctionKeyHasItsShortcutEitherWayThatIsOn() {
        assertEquals(Outcome.Shortcut(KeyShortcut.Command(KeyCommand.EditMenu)), up(space, -1))
        assertEquals("nothing set down", Outcome.None, up(space, 1))
        val both = Key.Function(up = selectAll, down = copy)
        // the symbol's way reaches the down shortcut as well: a function key has no symbol
        assertEquals(Outcome.Shortcut(copy), up(both, 1))
        assertEquals("the way off", Outcome.None, up(both, 1, SwipeLayers(Layer.Shortcut, Layer.Off)))
        assertEquals(Outcome.None, up(both, -1, off))
    }

    @Test
    fun thePreviewIsWhatTheLiftWouldDo() {
        assertEquals(Preview.Shortcut(selectAll), KeySwipe.preview(a, SwipeLayers.Default, -1, password = false))
        assertEquals(Preview.Symbol, KeySwipe.preview(a, SwipeLayers.Default, 1, password = false))
        assertEquals(Preview.Own, KeySwipe.preview(s, SwipeLayers.Default, -1, password = false))
        assertEquals(Preview.Own, KeySwipe.preview(a, SwipeLayers.Default, 0, password = false))
        assertEquals(Preview.Own, KeySwipe.preview(Key.Char(true, copy), SwipeLayers.Default, -1, password = true))
    }
}
