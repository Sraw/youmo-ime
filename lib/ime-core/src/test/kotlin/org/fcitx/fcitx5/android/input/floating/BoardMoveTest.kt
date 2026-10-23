/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.input.floating.BoardMove.Kind
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard.Dock
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardMoveTest {

    @Test
    fun itsSettingSlidesItToASideOrAcrossAndWidensItBack() {
        assertEquals(Kind.Slide, BoardMove.kind(bySetting = true, placed = true, was = null, now = Dock.RIGHT))
        assertEquals(Kind.Slide, BoardMove.kind(bySetting = true, placed = true, was = Dock.RIGHT, now = Dock.LEFT))
        assertEquals(Kind.Widen, BoardMove.kind(bySetting = true, placed = true, was = Dock.LEFT, now = null))
    }

    @Test
    fun aWindowOrLayoutTheFirstPlaceOrNoChangeIsNotAnimated() {
        assertEquals(Kind.None, BoardMove.kind(bySetting = false, placed = true, was = null, now = Dock.RIGHT))
        assertEquals(Kind.None, BoardMove.kind(bySetting = true, placed = false, was = null, now = Dock.RIGHT))
        assertEquals(Kind.None, BoardMove.kind(bySetting = true, placed = true, was = Dock.RIGHT, now = Dock.RIGHT))
    }

    @Test
    fun midWidenItIsMovedOnFromWhereItIsDrawn() {
        // a 1000-wide bar halfway through widening from 0..420: scale 0.71 about the pivot at 0
        assertEquals(0f, BoardMove.drawnLeft(0f, 0f, 0f, 0.71f), 0f)
        assertEquals(710f, BoardMove.drawnWidth(1000f, 0.71f), 0.01f)
        // from 580..1000 (pivot 1000): drawn from 290
        assertEquals(290f, BoardMove.drawnLeft(0f, 0f, 1000f, 0.71f), 0.01f)
        assertEquals("slid, not scaled", 300f, BoardMove.drawnLeft(580f, -280f, 500f, 1f), 0f)
    }
}
