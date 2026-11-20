/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickerLongPressTest {

    @Test
    fun aCellOffersItsVariantsNotItself() {
        assertArrayEquals(arrayOf("¡"), PickerLongPress["!"])
        assertArrayEquals(arrayOf("∅", "ⁿ", "⁰"), PickerLongPress["0"])
        assertArrayEquals(arrayOf("¢", "€", "£", "¥", "₹", "₽", "₺", "₩", "₱", "₿"), PickerLongPress["$"])
    }

    @Test
    fun aLetterHasNoneHere() {
        // the keys' lists are the scene's (PresetLists): no cell of the panel is a letter
        assertNull(PickerLongPress["e"])
    }

    @Test
    fun aVariantHasNoneOfItsOwn() {
        assertNull(PickerLongPress["€"])
    }
}
