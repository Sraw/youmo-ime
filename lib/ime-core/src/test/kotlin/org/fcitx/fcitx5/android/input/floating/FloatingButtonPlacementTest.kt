/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.input.floating.FloatingButtonPlacement.Area
import org.fcitx.fcitx5.android.input.floating.FloatingButtonPlacement.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingButtonPlacementTest {

    private val area = Area(width = 1000f, height = 2000f, top = 100f, bottom = 1900f)

    @Test
    fun noCursorNothingSavedIsTopRightBelowTheStatusBar() {
        val p = FloatingButtonPlacement()
        assertEquals(Spot(1000f - 50f - 8f, 108f), p.noCursor(null, 50f, 50f, area, 8f))
    }

    @Test
    fun noCursorSavedIsTheSavedPlaceClampedToASmallerParent() {
        val p = FloatingButtonPlacement()
        assertEquals(Spot(500f, 1000f), p.noCursor(Spot(0.5f, 0.5f), 50f, 50f, area, 8f))
        val small = Area(width = 400f, height = 600f, top = 50f, bottom = 550f)
        // the far corner saved: kept inside, clear of the navigation bar
        assertEquals(Spot(350f, 500f), p.noCursor(Spot(0.99f, 0.99f), 50f, 50f, small, 8f))
    }

    @Test
    fun cursorKnownIsBesideTheCursor() {
        val p = FloatingButtonPlacement()
        assertEquals(Spot(300f, 400f), p.besideCursor(300f, 400f, 50f, 50f, area))
    }

    @Test
    fun dragBesideTheCursorIsAnOffsetForThisFieldOnly() {
        val p = FloatingButtonPlacement()
        p.besideCursor(300f, 400f, 50f, 50f, area)
        assertNull(p.drop(Spot(500f, 300f), area))
        // the cursor moves on: the button keeps its distance from it
        assertEquals(Spot(220f, 410f), p.besideCursor(20f, 510f, 50f, 50f, area))
        p.newField()
        assertEquals(Spot(20f, 510f), p.besideCursor(20f, 510f, 50f, 50f, area))
    }

    @Test
    fun dropWithNoCursorIsSavedAndOutlivesANewField() {
        val p = FloatingButtonPlacement()
        p.noCursor(null, 50f, 50f, area, 8f)
        val saved = p.drop(Spot(250f, 1000f), area)
        assertEquals(Spot(0.25f, 0.5f), saved)
        p.newField()
        assertEquals(Spot(250f, 1000f), p.noCursor(saved, 50f, 50f, area, 8f))
    }

    @Test
    fun rtlTranslationCountsFromTheRightEdge() {
        // laid out at the right edge of a 1000 wide parent: translation 0 is left 950
        assertEquals(950f, FloatingButtonPlacement.toLeft(0f, 50f, 1000f, rtl = true))
        assertEquals(-950f, FloatingButtonPlacement.toTranslation(0f, 50f, 1000f, rtl = true))
        assertEquals(300f, FloatingButtonPlacement.toLeft(300f, 50f, 1000f, rtl = false))
        assertEquals(300f, FloatingButtonPlacement.toTranslation(300f, 50f, 1000f, rtl = false))
        for (left in listOf(0f, 123f, 950f)) {
            val t = FloatingButtonPlacement.toTranslation(left, 50f, 1000f, rtl = true)
            assertEquals(left, FloatingButtonPlacement.toLeft(t, 50f, 1000f, rtl = true))
        }
    }

    @Test
    fun scrollIsWithinTheButtonsOwnSize() {
        assertTrue(FloatingButtonPlacement.isScroll(36, -36, 36, 36, sameSize = true))
        assertFalse(FloatingButtonPlacement.isScroll(37, 0, 36, 36, sameSize = true))
        assertFalse(FloatingButtonPlacement.isScroll(0, -37, 36, 36, sameSize = true))
        // resized: its window moves at once
        assertFalse(FloatingButtonPlacement.isScroll(0, 0, 36, 36, sameSize = false))
    }

    @Test
    fun dragIsPastTheSlop() {
        assertFalse(FloatingButtonPlacement.isDrag(3f, 4f, 5f))
        assertTrue(FloatingButtonPlacement.isDrag(3f, 5f, 5f))
    }

    @Test
    fun clampKeepsItInsideThePanel() {
        val p = FloatingButtonPlacement()
        assertEquals(Spot(0f, 100f), p.clamp(-30f, -30f, 50f, 50f, area))
        assertEquals(Spot(950f, 1850f), p.clamp(5000f, 5000f, 50f, 50f, area))
        assertNull(p.drop(Spot(1f, 1f), Area(0f, 0f, 0f, 0f)))
    }
}
