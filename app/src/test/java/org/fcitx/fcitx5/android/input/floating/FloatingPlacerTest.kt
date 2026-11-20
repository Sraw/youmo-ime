/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class FloatingPlacerTest {

    private val prefs = AppPrefs.getInstance().internal
    private val zone = mutableListOf<Boolean>()

    // AppPrefs outlives the test: put back as it was
    @get:Rule
    val prefsBack = PrefsRule().apply {
        set(prefs.floatingKeyboardX, prefs.floatingKeyboardX.getValue())
        set(prefs.floatingKeyboardY, prefs.floatingKeyboardY.getValue())
    }

    private fun placer(): FloatingPlacer {
        val context = RuntimeEnvironment.getApplication()
        val parent = View(context).apply { layout(0, 0, 1000, 2000) }
        val view = View(context).apply { layout(0, 1600, 1000, 2000) }
        return FloatingPlacer(parent, view, landscape = false).also { it.onZone = { inZone -> zone += inZone } }
    }

    private val view = View(RuntimeEnvironment.getApplication())

    // a 400×400 keyboard in a 1000×2000 screen, the mark's 32 px over the bottom: 1568 of room down
    private fun grabbedPlacer(): FloatingPlacer {
        val parent = View(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 1000, 2000) }
        view.layout(300, 1600, 700, 2000)
        view.translationX = 0f
        view.translationY = 0f
        return FloatingPlacer(parent, view, landscape = false)
    }

    @Test
    fun aDragCancelledInTheZoneDoesNotDockAndKeepsWhereItFloated() {
        val p = placer()
        p.drag(0f, -300f)
        assertFalse("not in the zone", p.drop(lifted = true))
        val kept = FloatingStore.spot(false).y
        // into the zone, then the system takes the touch (or a second finger comes)
        p.drag(0f, 3000f)
        assertEquals(listOf(true), zone)
        assertFalse(p.drop(lifted = false))
        assertEquals(listOf(true, false), zone)
        assertTrue("kept where the drag ended", FloatingStore.spot(false).y != kept)
    }

    @Test
    fun liftedInTheZoneItDocksAndTheFloatingPlaceStays() {
        val p = placer()
        p.drag(0f, -300f)
        p.drop(lifted = true)
        val kept = FloatingStore.spot(false).let { it.x to it.y }
        p.drag(0f, 3000f)
        assertTrue(p.drop(lifted = true))
        assertEquals(listOf(true, false), zone)
        // not the bottom it was dragged to: the next float starts where it last floated
        assertEquals(kept, FloatingStore.spot(false).let { it.x to it.y })
    }

    @Test
    fun grabbedItsGripIsUnderTheFingerAndKeptAsTheFingerLifts() {
        val p = grabbedPlacer()
        p.grabbed(fingerX = 500f, fingerY = 1000f, gripX = 200f, gripY = 388f, keep = false)
        assertEquals("its left 300, where it is laid out", 0f, view.translationX, 0.01f)
        assertEquals("its top 612", 612f - 1600f, view.translationY, 0.01f)
        assertFalse(p.drop(lifted = true))
        assertEquals(0.5f, FloatingStore.spot(false).x, 0.001f)
        assertEquals(612f / 1568f, FloatingStore.spot(false).y, 0.001f)
        grabbedPlacer().grabbed(fingerX = 500f, fingerY = 600f, gripX = 200f, gripY = 388f, keep = true)
        assertEquals(212f / 1568f, FloatingStore.spot(false).y, 0.001f)
    }

    @Test
    fun grabbedThenDockedTheFloatingPlaceKeptStays() {
        prefs.floatingKeyboardX.setValue(0f)
        prefs.floatingKeyboardY.setValue(0f)
        val p = grabbedPlacer()
        p.grabbed(fingerX = 500f, fingerY = 1950f, gripX = 200f, gripY = 388f, keep = false)
        p.drag(0f, 100f)
        assertTrue("docks", p.drop(lifted = true))
        assertEquals(0f to 0f, FloatingStore.spot(false).let { it.x to it.y })
    }

    // lifted before it was laid out: no slack left for the next drag to take up first
    @Test
    fun grabbedAndKeptTheNextDragMovesItAtOnce() {
        val p = grabbedPlacer()
        p.grabbed(fingerX = 500f, fingerY = 1990f, gripX = 200f, gripY = 388f, keep = true)
        assertEquals("clamped to the lowest top, 1568 (the slack 34 below)", 1568f - 1600f, view.translationY, 0.01f)
        p.drag(0f, -100f)
        assertEquals(1468f - 1600f, view.translationY, 0.01f)
    }
}
