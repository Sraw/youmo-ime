/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * The column a popup's first key takes, centred over the key pressed. Its keys may now be wider
 * than that key (as wide as the bubble), and at an edge no column may leave them all on screen:
 * the 9 of nine keys took the far column, its popup starting a key's width off the screen.
 */
@RunWith(RobolectricTestRunner::class)
class PopupContainerUiTest {

    private val outer = Rect(0, 0, 1000, 600)

    private val ui = object : PopupContainerUi(RuntimeEnvironment.getApplication(), ThemePreset.PixelDark, outer, Rect(), {}) {
        override val root = View(ctx)
        override val offsetX = 0
        override val offsetY = 0
        override fun onChangeFocus(x: Float, y: Float) = false
        override fun onTrigger(): KeyAction? = null
    }

    private fun column(columns: Int, width: Int, left: Int, right: Int, bounds: Rect = outer): Int {
        val trigger = Rect(left, 500, right, 550)
        // Robolectric's timeout only interrupts the test's thread, which a search that never ends
        // never looks at: run apart, such a regression fails here instead of hanging the run
        val search = FutureTask(Callable { ui.calcInitialFocusedColumn(columns, width, bounds, trigger) })
        Thread(search).apply { isDaemon = true }.start()
        return search.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun theColumnNearestTheMiddleThatFitsIsTaken() {
        assertEquals(2, column(5, 40, 480, 520))
        assertEquals(0, column(5, 40, 0, 40))
        assertEquals(1, column(5, 40, 40, 80))
        assertEquals(4, column(5, 40, 960, 1000))
    }

    @Test
    fun keysWiderThanTheTriggerOverhangItAtBothSides() {
        // 80 over 40 at the left edge: 20 past it whatever the column, the first the least
        assertEquals(0, column(2, 80, 0, 40))
        // two columns of 80 to its left would fit beside its left side, not beside the key centred over it
        assertEquals(1, column(5, 80, 160, 200))
    }

    @Test
    fun whereNoneFitsTheOneLeastPastTheBoundsIsTaken() {
        // nine keys' 9: five keys of a fifth of the width over a key a little wider; the far column
        // started the popup 190 off the screen, the one before it ends 10 past the other side
        assertEquals(3, column(5, 200, 600, 820))
        assertEquals(1, column(5, 200, 160, 380))
    }

    // before the popups' root is laid out its bounds are empty; a column at a time, that never ended
    @Test
    fun keysOfNoWidthStillTakeAColumn() {
        assertEquals(2, column(5, 0, 480, 520, Rect()))
        // bounds narrower than the columns, as their keys are cut to none
        assertEquals(2, column(5, 0, 480, 520, Rect(0, 0, 3, 600)))
    }

    // nothing to fit in, the search took a column at the right: the popup opened left of the key
    @Test
    fun boundsNotYetLaidOutTakeTheMiddleColumn() {
        assertEquals(1, column(3, 40, 480, 520, Rect()))
        assertEquals(2, column(5, 40, 480, 520, Rect()))
    }
}
