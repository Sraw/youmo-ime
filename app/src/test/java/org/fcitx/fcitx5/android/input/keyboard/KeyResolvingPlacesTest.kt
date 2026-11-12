/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.keyboard.KeyResolving.Places
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a long press's items go once those it cannot type are dropped ([KeyResolving.placesOf]): the
 * row's place under the finger and the focus moved to the items kept.
 */
class KeyResolvingPlacesTest {

    // Q W 1 q w, then ! ?: the row the first five, 1 the focus and its own middle
    private fun of(kept: List<Int>) = KeyResolving.placesOf(kept, inRow = 5, focus = 2, own = 2)

    @Test
    fun theItemsKeptKeepTheirPlaces() = assertRows(
        "nothing dropped: the row of five round the first" to {
            assertEquals(Places(split = 5, inRow = 2, focus = 2), of(listOf(0, 1, 2, 3, 4, 5, 6)))
        },
        "a letter before the first dropped: the first one place left" to {
            assertEquals(Places(split = 4, inRow = 1, focus = 1), of(listOf(1, 2, 3, 4, 5, 6)))
        },
        "the first dropped, its own middle too: round the middle of what is kept of the row" to {
            assertEquals(Places(split = 4, inRow = 2, focus = -1), of(listOf(0, 1, 3, 4, 5, 6)))
        },
        "the first dropped, its own middle elsewhere and kept: round its own middle" to {
            assertEquals(Places(split = 4, inRow = 0, focus = -1), KeyResolving.placesOf(listOf(0, 1, 3, 4, 5), inRow = 5, focus = 2, own = 0))
        },
        "the whole row dropped: the symbols set apart, no row" to {
            assertEquals(Places(split = 0, inRow = -1, focus = -1), of(listOf(5, 6)))
        },
        "a list of no row (the resolver's -1): all symbols, no row" to {
            assertEquals(Places(split = 0, inRow = -1, focus = 0), KeyResolving.placesOf(listOf(0, 2), inRow = -1, focus = 0, own = -1))
        },
    )
}
