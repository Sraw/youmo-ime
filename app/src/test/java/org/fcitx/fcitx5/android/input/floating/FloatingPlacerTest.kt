/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The floating keyboard let go in the docking zone: docked only when the finger lifted there. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class FloatingPlacerTest {

    private val prefs = AppPrefs.getInstance().internal
    private val zone = mutableListOf<Boolean>()

    private fun placer(): FloatingPlacer {
        val context = RuntimeEnvironment.getApplication()
        val parent = View(context).apply { layout(0, 0, 1000, 2000) }
        val view = View(context).apply { layout(0, 1600, 1000, 2000) }
        return FloatingPlacer(parent, view).also { it.onZone = { inZone -> zone += inZone } }
    }

    @Test
    fun aDragCancelledInTheZoneDoesNotDockAndKeepsWhereItFloated() {
        val p = placer()
        p.drag(0f, -300f)
        assertFalse("not in the zone", p.drop(lifted = true))
        val kept = prefs.floatingKeyboardY.getValue()
        // down into the zone, then the system takes the touch (or a second finger comes)
        p.drag(0f, 3000f)
        assertEquals(listOf(true), zone)
        assertFalse(p.drop(lifted = false))
        assertEquals(listOf(true, false), zone)
        assertTrue("kept where the drag ended", prefs.floatingKeyboardY.getValue() != kept)
    }

    @Test
    fun liftedInTheZoneItDocksAndTheFloatingPlaceStays() {
        val p = placer()
        p.drag(0f, -300f)
        p.drop(lifted = true)
        val kept = prefs.floatingKeyboardX.getValue() to prefs.floatingKeyboardY.getValue()
        p.drag(0f, 3000f)
        assertTrue(p.drop(lifted = true))
        assertEquals(listOf(true, false), zone)
        // not the bottom it was dragged to: the next float starts where it last floated
        assertEquals(kept, prefs.floatingKeyboardX.getValue() to prefs.floatingKeyboardY.getValue())
    }
}
