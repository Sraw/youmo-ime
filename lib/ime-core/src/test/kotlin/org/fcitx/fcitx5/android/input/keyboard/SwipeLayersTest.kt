/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeLayersTest {

    @Test
    fun theChoicesTheDefaultFirstNoneRepeated() {
        assertEquals(SwipeLayers.entries.size, SwipeLayers.entries.toSet().size)
        assertEquals(SwipeLayers(Layer.Shortcut, Layer.Symbol), SwipeLayers.entries.first())
        assertEquals(SwipeLayers.Default, SwipeLayers.entries.first())
    }

    @Test
    fun theTwoWaysNeverReachOneLayerButMayBothBeOff() {
        assertThrows(IllegalArgumentException::class.java) { SwipeLayers(Layer.Symbol, Layer.Symbol) }
        assertThrows(IllegalArgumentException::class.java) { SwipeLayers(Layer.Shortcut, Layer.Shortcut) }
        assertTrue(SwipeLayers(Layer.Off, Layer.Off).allOff)
        assertFalse(SwipeLayers.Default.allOff)
    }

    @Test
    fun upIsNegative() {
        val layers = SwipeLayers.Default
        assertEquals(Layer.Shortcut, layers.layerOf(-1))
        assertEquals(Layer.Symbol, layers.layerOf(2))
        assertEquals(Layer.Off, layers.layerOf(0))
        assertEquals(true, layers.wayOf(Layer.Shortcut))
        assertEquals(false, layers.wayOf(Layer.Symbol))
        assertNull(SwipeLayers(Layer.Off, Layer.Symbol).wayOf(Layer.Shortcut))
    }

    @Test
    fun theOldSettingMovesOver() {
        assertEquals(SwipeLayers(Layer.Shortcut, Layer.Symbol), SwipeLayers.fromLegacy("Down"))
        assertEquals(SwipeLayers(Layer.Symbol, Layer.Shortcut), SwipeLayers.fromLegacy("Up"))
        assertEquals(SwipeLayers(Layer.Off, Layer.Off), SwipeLayers.fromLegacy("Disabled"))
        assertEquals(SwipeLayers.Default, SwipeLayers.fromLegacy(null))
        assertEquals(SwipeLayers.Default, SwipeLayers.fromLegacy("Sideways"))
    }
}
