/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Faces
import org.fcitx.fcitx5.android.scene.engine.Input
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
        assertTrue(lang.ops(false).any { it is Op.Fill && it.color == Argb(0xFF00FF00.toInt()) })
        assertTrue(lang.ops(true).any { it is Op.Fill && it.color == Argb(0xFF00FF00.toInt()) }, "pressed keeps the others")
        val shift = Fixtures.scene(Fixtures.portrait.copy(shift = org.fcitx.fcitx5.android.scene.engine.Shift.Lock)).keys.first { it.id == "shift" }
        assertEquals(setOf(KeyState.Locked), shift.states)
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
