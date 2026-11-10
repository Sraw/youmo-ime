/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Content
import org.fcitx.fcitx5.android.scene.engine.Paint
import org.fcitx.fcitx5.android.scene.engine.Part
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// the engine's guards at the edges of its geometry and paint (no stop drawn, corners unequal, a squeeze on a
// squeeze, nothing spaced), each a case only its guard gets right
class GeometryGuardsTest {

    // R20 F6144: a gradient whose every stop is transparent draws nothing, as a transparent solid does
    @Test
    fun aGradientOfNothingButTransparentIsNotDrawn() {
        val shape = Shape.Rect(Box(0.0, 0.0, 10.0, 10.0), listOf(0.0, 0.0, 0.0, 0.0))
        val paint = Paint(Fixtures.materialLight, 1.0)
        val clear = ColorExpr.Literal(Argb.TRANSPARENT)
        assertNull(paint.fill(shape, Fill.Linear(90.0, listOf(clear to 0.0, clear to 1.0))))
        assertNotNull(paint.fill(shape, Fill.Linear(90.0, listOf(clear to 0.0, ColorExpr.Literal(Argb(0xff000000.toInt())) to 1.0))))
    }

    // R20 F6362: a frame's corners set unequal: the window rounded as its roundest corner
    @Test
    fun aFramesUnequalCornersGiveItsRoundest() {
        val source = testData("material-light.xml").replace("</theme>", "<style select=\"frame\" radius=\"2dp 9dp 4dp 1dp\"/>\n</theme>")
        assertEquals(9.0, WindowLooks(Cascades(ThemeReader.theme(source).ok())).frame(emptySet()).radiusDp)
    }

    // R20 F6248: squeezed again, the squeeze is on top of the one it had, not instead of it
    @Test
    fun aTextSqueezedTwiceIsSqueezedByBoth() {
        val theme = Fixtures.materialLight
        val look = TextLook(Cascade(theme, emptySet()).of(Selector.Target("key", "qwerty", "q", "q", part = "label")), theme)
        val m = Content.measure(FakeMeasurer, "Pinyin", look, 400, 20.0)
        val half = m.squeezed(m.box.width / 2)
        val quarter = half.squeezed(m.box.width / 4)
        assertEquals(0.25, quarter.from(0.0, 0.0, Part.Label).texts.single().scaleX, 1e-9)
    }

    // R20 F6357: letter spacing on no text at all leaves it as it is (no ink turned inside out)
    @Test
    fun noTextSpacedIsNoTextAtAll() {
        assertEquals(FakeMeasurer.measure("", "sans", 400, 20.0), FakeMeasurer.measure("", "sans", 400, 20.0, 0.5))
    }
}
