/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The toolbar's buttons evenly apart (uat/05 TB-02); the floating keyboard's bar the same (FloatingUatTest, TB-22). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi", shadows = [HideRecordingShadow::class])
class ToolbarSpacingUatTest {

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness ?: KeyboardHarness().also { harness = it }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        harness?.close()
    }

    private fun s(id: Int) = h.service.getString(id)

    // the bar's buttons evenly apart, open or shut (the user's call, 2026-11-15): shut, the menu key and the right's as
    // wide, the right's side by side; open, the toolbar's and ⋯ as wide as those too, side by side from the menu key
    // on, the room past them all after ⋯
    @Test
    @Uat("TB-02")
    fun theBarsButtonsAreEvenlyApartOpenOrShut() {
        h.start()
        val shut = barCells(h)
        assertEquals(listOf(R.string.expand_toolbar, R.string.hide_keyboard).map(::s), listOf(shut.first(), shut.last()).map { it.first })
        assertEvenlyApart(shut, bigGapAfter = 0)
        h.tap(h.described(s(R.string.expand_toolbar)))
        val open = barCells(h)
        assertEquals(shut.size + 5, open.size)
        assertTrue("no wider than shut: $shut, $open", open[0].second.width() <= shut[0].second.width())
        assertEvenlyApart(open, bigGapAfter = open.indexOfFirst { it.first == s(R.string.status_area) })
    }

    companion object {
        /** The bar's buttons in view, left to right: what each is (its description) and where. */
        fun barCells(h: KeyboardHarness): List<Pair<String, Rect>> {
            val bar = h.box(h.bar)
            return KeyboardHarness.all(h.bar).filter { it is ToolButton && h.shown(it) }.map { it.contentDescription.toString() to h.box(it) }
                .filter { (_, b) -> b.left >= bar.left - 1 && b.right <= bar.right + 1 }.sortedBy { it.second.left }.toList()
        }

        /** [cells] all as wide, each right beside the last but after [bigGapAfter]'s (the room past them all), at most [gap] px there. */
        fun assertEvenlyApart(cells: List<Pair<String, Rect>>, bigGapAfter: Int, gap: Int = Int.MAX_VALUE) {
            val widths = cells.map { it.second.width() }
            assertTrue("$cells: as wide", widths.max() - widths.min() <= 1)
            for (i in 1 until cells.size) {
                val between = cells[i].second.left - cells[i - 1].second.right
                val most = if (i - 1 == bigGapAfter) gap else 1
                assertTrue("${cells[i - 1].first} to ${cells[i].first}: $between px apart (at most $most) in $cells", between in -1..most)
            }
        }
    }
}
