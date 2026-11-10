/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.theme.IconPath
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Line
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Move
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An arc whose radii or chord are so small their squares come to 0 found no centre and became NaN cubics, which
 * the app's Skia draws nothing of and the web's Path2D stops reading at: such an arc is drawn as a line.
 */
class IconPathTinyArcTest {

    private fun read(d: String) = (IconPath.parse(d) as IconPath.Read.Ok).segments

    @Test
    fun anArcTooSmallToHaveACentreIsALine() {
        assertEquals(listOf(Move(0.0, 0.0), Line(1.0, 1.0)), read("M0 0A1e-200 1e-200 0 0 1 1 1"))
        assertEquals(listOf(Move(0.0, 0.0), Line(1e-170, 0.0)), read("M0 0A1 1 0 0 1 1e-170 0"))
        // what follows goes on from the line's end
        assertEquals(listOf(Move(0.0, 0.0), Line(1.0, 1.0), Line(2.0, 1.0)), read("M0 0A1e-200 1e-200 0 0 1 1 1h1"))
    }
}
