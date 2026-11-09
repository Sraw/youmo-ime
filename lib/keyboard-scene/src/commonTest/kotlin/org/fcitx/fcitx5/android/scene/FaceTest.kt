/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Faces
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Paint
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.layout.Face
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// rules B §2 (KeyFaceGeometry) as Faces has it, and the layers Paint draws
class FaceTest {
    private val d = 2.0
    private val theme = Fixtures.materialLight
    private val cell = Box(0.0, 0.0, 100.0, 120.0)

    private fun look(vararg values: Pair<String, String>) =
        KeyLook(values.associate { (k, v) -> k to assertNotNull(Values.read(k, v), "$k=$v") }, theme)

    // a rectangle's box and corners (a face of a path is [Shape.Path]: the tests of those say so)
    private val Shape.box: Box get() = assertIs<Shape.Rect>(this).box
    private val Shape.radii: List<Double> get() = assertIs<Shape.Rect>(this).radii

    private fun near(e: Box, a: Box) = assertTrue(
        listOf(e.left - a.left, e.top - a.top, e.right - a.right, e.bottom - a.bottom).all { abs(it) < 1e-9 },
        "expected $e, was $a",
    )

    @Test
    fun aRectIsTheCellLessItsInsetRoundedNoMoreThanHalf() {
        val g = Faces.of(cell, look("inset" to "7dp 3dp", "radius" to "4dp"), Face.Default, margin = true, flat = false, density = d)
        near(Box(6.0, 14.0, 94.0, 106.0), assertNotNull(g.face).box)
        assertEquals(List(4) { 8.0 }, g.face?.radii)
        assertEquals(g.face, g.pressed)
        near(Box(6.0, 14.0, 94.0, 106.0), g.content)
        val round = Faces.of(Box(0.0, 0.0, 40.0, 120.0), look("radius" to "24dp"), Face.Default, true, false, d)
        assertEquals(List(4) { 20.0 }, round.face?.radii, "half the shorter side at most")
        assertEquals(List(4) { 50.0 }, Faces.of(cell, look("radius" to "50%"), Face.Default, true, false, d).face?.radii)
        val noMargin = Faces.of(cell, look("inset" to "7dp 3dp"), Face.Default, margin = false, flat = false, density = d)
        assertEquals(cell, noMargin.face?.box, "margin=false: the whole cell")
    }

    @Test
    fun theShadowIsTheFaceMovedKeptInTheCell() {
        val g = Faces.of(cell, look("inset" to "7dp 3dp", "shadow" to "0 1dp 0 #ff000000"), Face.Default, true, false, d)
        near(Box(6.0, 14.0, 94.0, 108.0), assertNotNull(g.shadow).box)
        val far = Faces.of(cell, look("inset" to "1dp", "shadow" to "0 16dp 0 #ff000000"), Face.Default, true, false, d)
        assertEquals(120.0, far.shadow?.box?.bottom, "never on the row below")
        assertNull(Faces.of(cell, look("shadow" to "none"), Face.Default, true, false, d).shadow)
    }

    @Test
    fun barAndCircleOfTheFlatSpaceAndReturn() {
        val bar = Faces.of(cell, look("shape" to "bar", "radius" to "3dp"), Face.Special, true, flat = true, density = d)
        // 10 dp a side; (120 - 52) / 2 = 34 top and bottom, but 16 dp (32) at most
        near(Box(20.0, 32.0, 80.0, 88.0), assertNotNull(bar.face).box)
        assertEquals(List(4) { 6.0 }, bar.face?.radii)
        val low = Faces.of(Box(0.0, 0.0, 100.0, 50.0), look("shape" to "bar"), Face.Special, true, true, d)
        near(Box(20.0, 0.0, 80.0, 50.0), assertNotNull(low.face).box)
        val circle = Faces.of(Box(0.0, 0.0, 200.0, 120.0), look("shape" to "circle"), Face.Special, true, true, d)
        near(Box(65.0, 25.0, 135.0, 95.0), assertNotNull(circle.face).box)
        assertEquals(List(4) { 35.0 }, circle.face?.radii)
        val small = Faces.of(Box(0.0, 0.0, 40.0, 60.0), look("shape" to "circle"), Face.Special, true, true, d)
        near(Box(0.0, 10.0, 40.0, 50.0), assertNotNull(small.face).box)
    }

    @Test
    fun faceDefaultIsARectWhenFlatNoneDrawsNothingButItsPressedLayer() {
        val flatDefault = Faces.of(cell, look("shape" to "circle", "inset" to "1dp"), Face.Default, true, flat = true, density = d)
        near(Box(2.0, 2.0, 98.0, 118.0), assertNotNull(flatDefault.face).box)
        val none = Faces.of(cell, look("inset" to "1dp", "shadow" to "0 1dp 0 #ff000000"), Face.None, true, false, d)
        assertNull(none.face)
        assertNull(none.shadow)
        assertEquals(0.0, none.border)
        near(Box(2.0, 2.0, 98.0, 118.0), none.pressed.box)
        assertNull(Faces.of(Box(0.0, 0.0, 4.0, 4.0), look("inset" to "2dp"), Face.Default, true, false, d).face, "no size, no face")
        // a path's neither: its box turned inside out would draw it mirrored, out of the cell
        val path = Faces.of(Box(0.0, 0.0, 4.0, 4.0), look("inset" to "2dp", "shape" to "path(M0 0 H1 V1 Z)", "border" to "1dp #ff000000"), Face.Default, true, false, d)
        assertNull(path.face, "no size, no path")
        assertNull(path.line)
        assertNull(path.depth)
    }

    // 060-render-p3 T4b: a path's face is drawn in the cell less its inset, its shadow the face moved
    // kept in the cell; its marks' box the face's box; a flat keyboard's plain key a rectangle still
    @Test
    fun aPathFaceIsDrawnInTheCellLessItsInset() {
        val triangle = "path(M.5 0 L1 1 L0 1 Z)"
        val g = Faces.of(cell, look("shape" to triangle, "inset" to "7dp 3dp", "radius" to "8dp", "shadow" to "0 4dp 0 #ff000000"), Face.Default, true, false, d)
        val face = assertIs<Shape.Path>(g.face)
        near(Box(6.0, 14.0, 94.0, 106.0), face.bounds)
        assertTrue(face.contains(50.0, 20.0) && !face.contains(10.0, 20.0), "the triangle, not its box; no radius")
        assertEquals(g.face, g.pressed)
        near(Box(6.0, 14.0, 94.0, 106.0), g.content)
        val shadow = assertIs<Shape.Path>(g.shadow)
        near(Box(6.0, 22.0, 94.0, 114.0), shadow.bounds)
        val far = Faces.of(cell, look("shape" to triangle, "inset" to "1dp", "shadow" to "0 16dp 0 #ff000000"), Face.Default, true, false, d)
        assertEquals(120.0, far.shadow?.bounds?.bottom, "never on the row below")
        // its border's middle: the path again, half the border in from the box
        val bordered = Faces.of(cell, look("shape" to triangle, "border" to "2dp #ff000000"), Face.Default, true, false, d)
        assertEquals(4.0, bordered.border)
        near(Box(2.0, 2.0, 98.0, 118.0), assertIs<Shape.Path>(bordered.line).bounds)
        val flat = Faces.of(cell, look("shape" to triangle), Face.Default, true, flat = true, density = d)
        assertIs<Shape.Rect>(flat.face, "a flat keyboard's plain key: a rectangle")
        assertIs<Shape.Path>(Faces.of(cell, look("shape" to triangle), Face.Special, true, flat = true, density = d).face)
        assertNull(Faces.of(cell, look("shape" to triangle), Face.None, true, false, d).face)
    }

    // `depth`: the face up by it, the band under it the face moved down, its colour the fill's darker
    @Test
    fun aCapsDepthIsABandUnderTheFace() {
        val g = Faces.of(cell, look("inset" to "7dp 3dp", "radius" to "4dp", "depth" to "4dp", "shadow" to "0 1dp 0 #ff000000"), Face.Default, true, false, d)
        val face = assertIs<Shape.Rect>(g.face)
        near(Box(6.0, 14.0, 94.0, 98.0), face.box)
        assertEquals(List(4) { 8.0 }, face.radii)
        assertEquals(Shape.Rect(face.box.moved(0.0, 8.0), face.radii), g.depth)
        assertEquals(g.face, g.pressed, "the pressed layer over the face, not the band")
        near(Box(6.0, 14.0, 94.0, 98.0), g.content)
        near(Box(6.0, 14.0, 94.0, 108.0), assertNotNull(g.shadow).bounds) // the shadow of the whole cap
        // no more than half the face; a path's band its outline moved
        val low = Faces.of(Box(0.0, 0.0, 100.0, 20.0), look("depth" to "8dp"), Face.Default, false, false, d)
        assertEquals(10.0, assertNotNull(low.depth).bounds.top)
        val path = Faces.of(cell, look("shape" to "path(M.5 0 L1 1 L0 1 Z)", "depth" to "2dp"), Face.Default, true, false, d)
        assertEquals(assertNotNull(path.face).moved(0.0, 4.0), path.depth)
        // not under a bar, a circle, a face of none; none by default
        assertNull(Faces.of(cell, look("shape" to "bar", "depth" to "2dp"), Face.Special, true, true, d).depth)
        assertNull(Faces.of(cell, look("shape" to "circle", "depth" to "2dp"), Face.Special, true, true, d).depth)
        assertNull(Faces.of(cell, look("depth" to "2dp"), Face.None, true, false, d).depth)
        assertNull(Faces.of(cell, look(), Face.Default, true, false, d).depth)
    }

    // the layers: shadow, band, fill, inner shadow, pressed, border (CSS's inset shadow over the background)
    @Test
    fun anInnerShadowIsInsideTheFaceUnderItsBorder() {
        val look = look(
            "fill" to "#ff808080", "overlay" to "#1f000000", "border" to "1dp #ff112233", "shadow" to "0 1dp 0 #ff000000",
            "depth" to "2dp", "inner-shadow" to "1dp 2dp 3dp #80000000",
        )
        val g = Faces.of(cell, look, Face.Default, true, false, d)
        val ops = Paint(theme, d).face(g, look, pressed = true)
        assertEquals(listOf("Shadow", "Fill", "Fill", "InnerShadow", "Fill", "Stroke"), ops.map { it::class.simpleName })
        val band = assertIs<Op.Fill>(ops[1])
        assertEquals(g.depth, band.shape)
        assertEquals(g.face, band.less, "the band less the face: none of it under a translucent face")
        assertNull(assertIs<Op.Fill>(ops[2]).less)
        assertEquals(theme.color(assertNotNull(org.fcitx.fcitx5.android.scene.theme.ColorExpr.parse("darker(#ff808080, 20%)"))), band.color)
        val inner = assertIs<Op.InnerShadow>(ops[3])
        val face = assertNotNull(g.face)
        assertEquals(face, inner.shape)
        assertEquals(face.moved(2.0, 4.0), inner.hole)
        assertEquals(6.0, inner.blur)
        assertEquals(Argb(0x80000000.toInt()), inner.color)
        // the ring as far out as the blur reaches, round the face and the hole
        near(face.bounds.union(inner.hole.bounds).inset(-13.0, -13.0, -13.0, -13.0), inner.ring)
        // none, transparent: no op; a path's hole is the path moved
        assertTrue(Paint(theme, d).face(g, look("inner-shadow" to "none"), false).none { it is Op.InnerShadow })
        assertTrue(Paint(theme, d).face(g, look("inner-shadow" to "0 1dp 0 #00000000"), false).none { it is Op.InnerShadow })
        val pathLook = look("shape" to "path(M.5 0 L1 1 L0 1 Z)", "inner-shadow" to "0 1dp 0 #ff000000", "depth-color" to "#ff00ff00", "depth" to "1dp")
        val pg = Faces.of(cell, pathLook, Face.Default, true, false, d)
        val pathOps = Paint(theme, d).face(pg, pathLook, false)
        assertEquals(Argb(0xFF00FF00.toInt()), pathOps.filterIsInstance<Op.Fill>().first().color, "depth-color")
        assertEquals(assertNotNull(pg.face).moved(0.0, 2.0), pathOps.filterIsInstance<Op.InnerShadow>().single().hole)
    }

    @Test
    fun layersInOrderShadowFillPressedBorderRuleB3() {
        val t = ThemeReader.theme(
            testData("material-light.xml").replace(
                "</theme>",
                "<style select=\"key.letter\" border=\"1dp #ff112233\"/>\n<style select=\"key.space\" press=\"ripple\"/>\n" +
                    "<style select=\"key.function\" fill=\"linear(90deg, @key 0%, @altKey 100%)\"/>\n" +
                    "<style select=\"key.enter\" fill=\"image(enter.png, 4 4 4 4, 3x)\"/>\n<style select=\"key[role=lang]\" fill=\"picture\"/>\n</theme>",
            ),
        ).ok()
        val s = SceneBuilder(t, Fixtures.portrait, FakeMeasurer).build(Fixtures.qwerty).ok()
        val q = s.keys.first { it.id == "q" }
        val pressed = q.ops(true)
        assertEquals(listOf("Shadow", "Fill", "Fill", "Stroke", "Text", "Text"), pressed.map { it::class.simpleName })
        val stroke = assertIs<Op.Stroke>(pressed[3])
        assertEquals(2.625, stroke.width)
        val face = (q.shape as Shape.Rect)
        assertEquals(face.box.left + 2.625 / 2, (stroke.shape as Shape.Rect).box.left, "inside the face")
        assertEquals(face.radii[0] - 2.625 / 2, (stroke.shape as Shape.Rect).radii[0])
        assertTrue(s.keys.first { it.id == "space" }.ops(true).any { it is Op.Ripple })
        assertTrue(s.keys.first { it.id == "comma" }.ops(false).any { it is Op.Gradient })
        assertTrue(s.keys.first { it.id == "enter" }.ops(false).any { it is Op.NineSlice })
        assertTrue(s.keys.first { it.id == "lang" }.ops(false).any { it is Op.Picture })
    }

    @Test
    fun flatKeysAndFaceKeep() {
        val table = Fixtures.portrait.copy(flat = true, input = Input(ime = "table", table = "wubi", lookingUp = true, composing = true))
        val s = Fixtures.scene(table)
        val q = s.keys.first { it.id == "q" }
        val normal = q.ops(false)
        assertTrue(normal.none { it is Op.Fill || it is Op.Shadow }, "flat: no face, no shadow ($normal)")
        val overlay = q.ops(true).filterIsInstance<Op.Fill>().single()
        assertEquals(Argb(0x1F000000), overlay.color)
        assertEquals(List(4) { 0.0 }, (overlay.shape as Shape.Rect).radii, "@flat key radius=0: a square pressed area")
        val space = s.keys.first { it.id == "space" }.shape as Shape.Rect
        assertEquals(10 * 2.625, space.box.left - s.keys.first { it.id == "space" }.bounds.left, 1e-9)
        // face="keep": the block face 3.0 drew whatever 按键样式 said
        val keep = s.keys.first { it.id == "lookup-on" }.ops(false)
        assertTrue(keep.any { it is Op.Shadow }, "$keep")
        assertEquals(Argb(0xFF5CB5AB.toInt()), keep.filterIsInstance<Op.Fill>().single().color)
        val confirm = s.keys.first { it.id == "confirm" }.shape as Shape.Rect
        assertTrue(confirm.box.width > confirm.box.height, "确定 a block, not the flat Return's circle")
    }

    @Test
    fun theKeyboardsOwnFill() {
        val s = Fixtures.scene(Fixtures.floating)
        val base = assertIs<Op.Fill>(s.base.single())
        assertEquals(Argb(0xFFECEFF1.toInt()), base.color)
        assertEquals(List(4) { 7 * 2.625 }, (base.shape as Shape.Rect).radii, "@floating keyboard radius=7dp")
        val flat = Fixtures.scene(Fixtures.portrait.copy(flat = true))
        assertEquals(Argb(0xFFECEFF1.toInt()), assertIs<Op.Fill>(flat.base.single()).color)
    }

    @Test
    fun pressFaceIsThePressedFillAloneAndANothingBoxHasNoPress() {
        val t = ThemeReader.theme(
            testData("material-light.xml").replace(
                "</theme>",
                "<style select=\"*/space\" press=\"face\"/>\n<style select=\"*/space:pressed\" fill=\"#ff0000\"/>\n</theme>",
            ),
        ).ok()
        val space = Fixtures.scene(Fixtures.portrait, theme = t).keys.first { it.id == "space" }
        val fills = space.ops(true).filterIsInstance<Op.Fill>()
        assertEquals(listOf(Argb(0xFFFF0000.toInt())), fills.map { it.color }, "the pressed face, no overlay on it")
        // L5: a key too narrow for its inset has a face of nothing, and nothing pressed
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"a\" width=\"0.01\"/><key id=\"b\" label=\"b\" width=\"fill\"/></row></keyboard>",
        ).ok()
        val a = Fixtures.scene(Fixtures.portrait, l).keys.first { it.id == "a" }
        assertTrue(a.ops(true).none { it is Op.Fill && it.color == Argb(0x1F000000) }, "${a.ops(true)}")
    }

    @Test
    fun keyStatesAreASet() {
        val e = Fixtures.portrait.copy(states = mapOf("lang" to setOf(KeyState.On, KeyState.Disabled)))
        val t = ThemeReader.theme(
            testData("material-light.xml").replace("</theme>", "<style select=\"*/lang:on:disabled\" fill=\"#00ff00\"/>\n</theme>"),
        ).ok()
        val lang = Fixtures.scene(e, theme = t).keys.first { it.id == "lang" }
        assertEquals(setOf(KeyState.On, KeyState.Disabled), lang.states)
        // its fill the style's, faded as it takes no press (S6)
        val green = { op: Op -> op is Op.Fill && op.color.value and 0xFFFFFF == 0x00FF00 && (op.color.value ushr 24) < 0xFF }
        assertTrue(lang.ops(false).any(green), "${lang.ops(false)}")
        assertTrue(lang.ops(true).any(green), "pressed keeps the others")
        val shift = Fixtures.scene(Fixtures.portrait.copy(shift = org.fcitx.fcitx5.android.scene.engine.Shift.Lock)).keys.first { it.id == "shift" }
        assertEquals(setOf(KeyState.Locked), shift.states)
    }

    // a key the app says takes no press now: every op of its face faded, none drawn at full alpha as an enabled one is
    @Test
    fun aDisabledKeyIsFaded() {
        val shown = Fixtures.scene(Fixtures.portrait).keys.first { it.id == "lang" }.ops(false)
        val off = Fixtures.scene(Fixtures.portrait.copy(states = mapOf("lang" to setOf(KeyState.Disabled)))).keys.first { it.id == "lang" }.ops(false)
        assertEquals(shown.size, off.size)
        assertTrue(shown.any { it is Op.Fill && (it.color.value ushr 24) == 0xFF }, "$shown")
        assertTrue(off.none { it is Op.Fill && (it.color.value ushr 24) == 0xFF }, "$off")
    }

    @Test
    fun aKeyWithFaceNoneStillShowsItsPress() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"a\" width=\"10\" face=\"none\"/></row></keyboard>",
        ).ok()
        val a = Fixtures.scene(Fixtures.portrait, l).keys.single()
        assertTrue(a.ops(false).none { it is Op.Fill || it is Op.Shadow })
        assertEquals(1, a.ops(true).count { it is Op.Fill })
    }
}
