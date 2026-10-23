/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.input.floating.FloatingSwitch.Bounds
import org.fcitx.fcitx5.android.input.floating.FloatingSwitch.Dock
import org.fcitx.fcitx5.android.input.floating.FloatingSwitch.Grab
import org.fcitx.fcitx5.android.input.floating.FloatingSwitch.Layout
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingSwitchTest {

    private val docked = Bounds(0f, 1200f, 1000f, 800f)
    private val s = FloatingSwitch()

    @Test
    fun grabbedTheMovesBeforeTheFirstLayoutWaitAndThenItsGripGoesUnderTheFinger() {
        s.grab(500f, 1900f, docked)
        assertFalse("kept, not moved: no size yet", s.drag(-20f, -100f))
        assertFalse(s.drag(-10f, -50f))
        assertEquals(Layout.Placed(Grab(470f, 1750f, docked, underFinger = true)), s.laidOut())
        // laid out: the drag moves it, a drop is the keyboard's (it may dock)
        assertTrue(s.drag(1f, 1f))
        assertTrue(s.drop())
        assertEquals(Layout.Spot, s.laidOut())
    }

    @Test
    fun endedBeforeTheFirstLayoutItsPlaceIsKeptAsItIsLaidOut() = assertRows(
        "lifted before the first layout" to {
            val sw = FloatingSwitch()
            sw.grab(500f, 1900f, docked)
            assertFalse(sw.drop())
            assertEquals(Layout.Placed(Grab(500f, 1900f, docked, underFinger = true, ended = true)), sw.laidOut())
        },
        // F3467: TalkBack's detach, no finger down and so no drop to come
        "TalkBack's detach, no finger down" to {
            val sw = FloatingSwitch()
            sw.grab(500f, 1900f, docked, ended = true)
            assertEquals(Layout.Placed(Grab(500f, 1900f, docked, underFinger = true, ended = true)), sw.laidOut())
        },
    )

    @Test
    fun switchedOtherwiseItShrinksAtItsSpotAndItsOwnHandleDragsAtOnce() {
        s.shrinkFrom(docked)
        assertTrue(s.drag(5f, 5f))
        assertTrue(s.drop())
        assertEquals(Layout.Placed(Grab(0f, 0f, docked, underFinger = false)), s.laidOut())
    }

    @Test
    fun aGrabIsNotUndoneByTheShrinkThatComesWithIt() {
        // the old view leaves its bounds as it goes, the new one takes them as it comes, then the grab
        s.shrinkFrom(docked)
        s.grab(500f, 1900f, docked)
        s.shrinkFrom(docked)
        assertEquals(true, (s.laidOut() as Layout.Placed).grab.underFinger)
    }

    @Test
    fun hiddenBeforeItWasLaidOutItDoesNotShrinkFromAKeyboardLongGone() {
        s.shrinkFrom(docked)
        s.inputEnded()
        assertEquals(Layout.Spot, s.laidOut())
    }

    @Test
    fun animationOffItDocksAtOnce() {
        assertEquals(Dock.Now, s.dock(animates = false))
        assertFalse(s.docking)
        assertTrue(s.drag(1f, 1f))
    }

    @Test
    fun slidingDownTheDragIsOverAndItDocksOnceTheSlideIsOver() {
        assertEquals(Dock.Slide, s.dock(animates = true))
        assertEquals("the dock button again", Dock.Sliding, s.dock(animates = true))
        assertFalse(s.drag(1f, 1f))
        assertFalse(s.drop())
        assertEquals("its place is the slide's", Layout.Sliding, s.laidOut())
        assertTrue(s.slideOver())
        // ended and cancelled both come, or the view goes and the end after: docked once
        assertFalse(s.slideOver())
        assertTrue(s.drag(1f, 1f))
    }
}
