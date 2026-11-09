/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

// the scale an icon's raster is sized by: a matrix gone NaN or infinite reads as 1, not a throw in the draw
class ScenePainterScaleTest {
    @Test
    fun aScaleNotFiniteIsOne() {
        assertEquals(1f, ScenePainter.scaleOf(Float.NaN))
        assertEquals(1f, ScenePainter.scaleOf(Float.POSITIVE_INFINITY))
        assertEquals(0.5f, ScenePainter.scaleOf(-0.5f))
        assertEquals(2f, ScenePainter.scaleOf(2f))
    }
}
