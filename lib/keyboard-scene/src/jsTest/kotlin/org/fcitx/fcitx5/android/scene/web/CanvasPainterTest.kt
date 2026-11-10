/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.web

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.TextShadow
import org.fcitx.fcitx5.android.scene.TextStroke
import org.fcitx.fcitx5.android.scene.theme.Argb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the painter draws of the ops the app's own drawing is not compared on (web/theme-maker/test/engine.test.mjs
 * holds the rest to the app's ScenePainter: no snapshot has these): a nine-slice, the picture, a gradient, a
 * ripple, a shape less another, an inner shadow, outlined text, letters set one by one, an icon's stroke, on a
 * context that writes down each call. A nine-slice is cut as the app's NineSliceLayout cuts it
 * (KeyFaceGeometryTest's cases, here).
 */
class CanvasPainterTest {
    private val red = Argb.of(255, 255, 0, 0)
    private val half = Argb.of(128, 0, 0, 255)
    private val box = Shape.Rect(Box(10.0, 20.0, 110.0, 70.0))

    // the half-alpha blue as CSS has it (128/255 to the last digit)
    private val blue = CanvasPainter.css(half)

    private class Assets(val images: Map<String, dynamic> = emptyMap(), val picture: dynamic = null, val icons: Map<String, dynamic> = emptyMap()) : PaintAssets {
        val pictures = mutableListOf<String>()

        override fun family(font: String) = "sans-serif"

        override fun image(file: String): dynamic = images[file]

        override fun picture(blurDp: Int?, brightness: Int?): dynamic {
            pictures += "$blurDp $brightness"
            return picture
        }

        override fun icon(name: String): dynamic = icons[name]
    }

    private fun drawn(op: Op, assets: Assets = Assets(), ctx: dynamic = recorder()): List<String> {
        CanvasPainter(ctx, assets, 2.0, 400.0, 300.0).draw(op)
        return (ctx.calls as Array<String>).toList()
    }

    // a context with no canvas (an offscreen one made wrong) fails as the painter is made, before any op is half drawn
    @Test
    fun aContextWithoutACanvasFailsAtOnce() {
        val ctx: dynamic = js("({})")
        assertFailsWith<IllegalStateException> { CanvasPainter(ctx, Assets(), 2.0, 400.0, 300.0) }
    }

    @Test
    fun aNineSliceWithNoImageIsItsFallbackColourOrNothing() {
        val op = Op.NineSlice(box, "key.png", listOf(30, 30, 30, 30), 3, fallback = red)
        assertEquals(listOf("beginPath()", "rect(10,20,100,50)", "fillStyle=rgba(255,0,0,1)", "fill()"), drawn(op))
        assertEquals(emptyList(), drawn(op.copy(fallback = null)))
    }

    @Test
    fun aNineSliceIsItsNinePiecesOfWholeSourcePixelsClippedToItsShape() {
        val image: dynamic = js("({ image: 'IMG', width: 90, height: 90 })")
        val calls = drawn(Op.NineSlice(box, "key.png", listOf(30, 30, 30, 30), 3), Assets(mapOf("key.png" to image)))
        assertEquals(listOf("save()", "beginPath()", "rect(10,20,100,50)", "clip()"), calls.take(4))
        assertEquals("restore()", calls.last())
        val pieces = calls.filter { it.startsWith("drawImage") }
        assertEquals(9, pieces.size)
        // corners of 30 source px at 3 px a dp, drawn at 2: 20 px
        assertEquals("drawImage(IMG,0,0,30,30,10,20,20,20)", pieces[0])
        assertEquals("drawImage(IMG,30,30,30,30,30,40,60,10)", pieces[4])
        assertEquals("drawImage(IMG,60,60,30,30,90,50,20,20)", pieces[8])
        // no insets: the whole image over the whole shape
        val whole = drawn(Op.NineSlice(box, "key.png", listOf(0, 0, 0, 0), 1), Assets(mapOf("key.png" to image)))
        assertEquals(listOf("drawImage(IMG,0,0,90,90,10,20,100,50)"), whole.filter { it.startsWith("drawImage") })
    }

    @Test
    fun aNineSliceIsCutAsTheAppCutsIt() {
        val nine = NineSliceCut.of(90.0, 90.0, 30, 30, 30, 30, 3, Box(0.0, 0.0, 200.0, 100.0), 2.0)
        assertEquals(9, nine.size)
        assertEquals(NineSliceCut.Piece(Box(0.0, 0.0, 30.0, 30.0), Box(0.0, 0.0, 20.0, 20.0)), nine[0])
        assertEquals(NineSliceCut.Piece(Box(30.0, 30.0, 60.0, 60.0), Box(20.0, 20.0, 180.0, 80.0)), nine[4])
        assertEquals(NineSliceCut.Piece(Box(60.0, 60.0, 90.0, 90.0), Box(180.0, 80.0, 200.0, 100.0)), nine[8])
        // corners of 20 and 60 px in a 40 px target: 10 and 30, the middle column gone
        val tight = NineSliceCut.of(100.0, 10.0, 20, 0, 60, 0, 1, Box(0.0, 0.0, 40.0, 10.0), 1.0)
        assertEquals(listOf(Box(0.0, 0.0, 10.0, 10.0), Box(10.0, 0.0, 40.0, 10.0)), tight.map { it.dst })
        assertEquals(listOf(Box(0.0, 0.0, 20.0, 10.0), Box(40.0, 0.0, 100.0, 10.0)), tight.map { it.src })
        // insets past the image take nothing outside it
        val past = NineSliceCut.of(10.0, 10.0, 8, 8, 8, 8, 1, Box(0.0, 0.0, 100.0, 100.0), 1.0)
        assertEquals(true, past.isNotEmpty())
        past.forEach { assertEquals(true, it.src.left >= 0 && it.src.top >= 0 && it.src.right <= 10 && it.src.bottom <= 10, "$it") }
        assertEquals(listOf(NineSliceCut.Piece(Box(0.0, 0.0, 10.0, 10.0), Box(1.0, 2.0, 3.0, 4.0))), NineSliceCut.of(10.0, 10.0, 0, 0, 0, 0, 2, Box(1.0, 2.0, 3.0, 4.0), 1.0))
    }

    @Test
    fun thePictureIsTheScenesSizeClippedToTheShapeItsColourOver() {
        val assets = Assets(picture = "PIC")
        val calls = drawn(Op.Picture(box, 10.4, 80.0, over = half), assets)
        assertEquals(
            listOf(
                "save()", "beginPath()", "rect(10,20,100,50)", "clip()", "drawImage(PIC,0,0,400,300)", "restore()",
                "beginPath()", "rect(10,20,100,50)", "fillStyle=$blue", "fill()",
            ),
            calls,
        )
        assertEquals(listOf("10 80"), assets.pictures)
        // none yet: the colour alone; the theme's own blur and brightness asked for as none
        val none = Assets()
        assertEquals(listOf("beginPath()", "rect(10,20,100,50)", "fillStyle=$blue", "fill()"), drawn(Op.Picture(box, null, null, half), none))
        assertEquals(listOf("null null"), none.pictures)
        assertEquals(emptyList(), drawn(Op.Picture(box, null, null)))
    }

    @Test
    fun aGradientRunsAsCssAngles() {
        // 90deg: left to right across the middle; 180deg: top to bottom
        val across = drawn(Op.Gradient(box, 90.0, listOf(red to 0.0, half to 1.5)))
        assertEquals("createLinearGradient(10,45,110,45)", across[0])
        assertEquals(listOf("stop(0,rgba(255,0,0,1))", "stop(1,$blue)"), across.filter { it.startsWith("stop") })
        assertEquals(listOf("beginPath()", "rect(10,20,100,50)", "fillStyle=GRADIENT", "fill()"), across.takeLast(4))
        assertEquals("createLinearGradient(60,20,60,70)", drawn(Op.Gradient(box, 180.0, listOf(red to 0.0)))[0])
        // 45deg on a box twice as wide as tall: the corners reach the ends
        assertEquals("createLinearGradient(22.5,82.5,97.5,7.5)", drawn(Op.Gradient(box, 45.0, listOf(red to 0.0)))[0])
    }

    @Test
    fun aRippleIsItsShapeFilled() {
        val round = Shape.Rect(Box(0.0, 0.0, 40.0, 20.0), listOf(4.0, 4.0, 4.0, 4.0))
        val calls = drawn(Op.Ripple(round, half))
        assertEquals("beginPath()", calls.first())
        assertEquals(4, calls.count { it.startsWith("arc(") })
        assertEquals(listOf("closePath()", "fillStyle=$blue", "fill()"), calls.takeLast(3))
    }

    @Test
    fun radiiTooLargeForASideAreScaledDownAlikeAsSkiaDoes() {
        // 30 + 30 on a 40 px side: each two thirds, 20
        val calls = drawn(Op.Fill(Shape.Rect(Box(0.0, 0.0, 40.0, 100.0), listOf(30.0, 30.0, 30.0, 30.0)), red))
        assertEquals(listOf("arc(20,20,20,-1.571,0)", "arc(20,80,20,0,1.571)", "arc(20,80,20,1.571,3.142)", "arc(20,20,20,3.142,4.712)"), calls.filter { it.startsWith("arc") })
    }

    // the layer as [laid] sets it up over the device's [area], drawing in [style]; its calls after, then laid
    private fun layer(area: String, style: String, vararg calls: String): List<String> =
        listOf("layer.save()", "layer.setTransform(1,0,0,1,0,0)", "layer.clearRect($area)", "layer.setTransform(M)", "layer.fillStyle=$style", "layer.strokeStyle=$style") +
            calls.map { "layer.$it" } + "layer.restore()"

    // a pentagram: its middle inside it by the nonzero rule (Path.op's winding fill), outside by the even-odd one
    private val star = Shape.Path(
        listOf(
            Shape.Path.Op.MoveTo(60.0, 22.0), Shape.Path.Op.LineTo(80.0, 68.0), Shape.Path.Op.LineTo(30.0, 38.0),
            Shape.Path.Op.LineTo(90.0, 38.0), Shape.Path.Op.LineTo(40.0, 68.0), Shape.Path.Op.Close,
        ),
    )

    @Test
    fun aShapeLessAnotherIsTheOtherCutOutOfItOnALayerByTheNonzeroRuleThoughItCrossesItself() {
        val calls = drawn(Op.Fill(box, half, less = star))
        val cut = listOf("moveTo(60,22)", "lineTo(80,68)", "lineTo(30,38)", "lineTo(90,38)", "lineTo(40,68)", "closePath()")
        assertEquals(
            layer(
                "9,19,102,52", "rgba(0,0,255,1)",
                "beginPath()", "rect(10,20,100,50)", "fill()",
                "globalCompositeOperation=destination-out", "beginPath()", *cut.toTypedArray(), "fillStyle=#000000", "fill()",
                "globalCompositeOperation=source-over",
            ) + listOf("save()", "setTransform(1,0,0,1,0,0)", "globalAlpha=0.502", "drawImage(LAYER,9,19,102,52,9,19,102,52)", "restore()"),
            calls,
        )
        assertTrue(calls.none { "evenodd" in it }, "no even-odd rule anywhere")
    }

    @Test
    fun anInnerShadowIsItsRingLessItsHoleBlurredAsOneInsideTheFace() {
        val hole = Shape.Rect(Box(14.0, 24.0, 106.0, 66.0))
        val calls = drawn(Op.InnerShadow(box, hole, Box(0.0, 10.0, 120.0, 80.0), 4.0, half))
        assertEquals(
            listOf("save()", "beginPath()", "rect(10,20,100,50)", "clip()") +
                layer(
                    "0,9,121,72", "#000000",
                    "beginPath()", "rect(0,10,120,70)", "fill()",
                    "globalCompositeOperation=destination-out", "beginPath()", "rect(14,24,92,42)", "fillStyle=#000000", "fill()",
                    "globalCompositeOperation=source-over",
                ) +
                listOf(
                    "save()", "setTransform(1,0,0,1,0,0)",
                    "save()", "shadowColor=$blue", "shadowBlur=5.619", "shadowOffsetX=100000", "shadowOffsetY=0", "translate(-100000,0)",
                    "fillStyle=#000000", "strokeStyle=#000000", "drawImage(LAYER,0,9,121,72,0,9,121,72)", "restore()",
                    "restore()", "restore()",
                ),
            calls,
        )
    }

    // FILL_AND_STROKE: the outline and the letters drawn opaque on a layer, laid once at the colour's alpha
    @Test
    fun anOutlineNotOpaqueIsLaidOnceNotOverItsOwnFill() {
        val calls = drawn(Op.Text("ab", 10.0, 50.0, "system", 400, 20.0, red, stroke = TextStroke(1.5, half)))
        val glyphs = listOf(
            "save()", "font=400 20px sans-serif", "letterSpacing=0px", "textAlign=left", "textBaseline=alphabetic", "translate(10,50)",
            "lineWidth=3", "lineJoin=round", "strokeText(ab,0,0)", "fillText(ab,0,0)", "restore()",
        )
        // the layer over the text's room alone (its run 20 px, 20 px letters, the outline 1.5 px round), not the canvas:
        // how generous its margins are is the painter's, not pinned here
        val measured = listOf("save()", "font=400 20px sans-serif", "measureText(ab)", "restore()")
        val rect = layerRect(calls.first { it.startsWith("drawImage(LAYER") })
        assertLayerRound(rect, 8.5, 28.5, 31.5, 55.5)
        val r = rect.joinToString(",") { it.toInt().toString() }
        assertEquals(
            measured + layer(r, "rgba(0,0,255,1)", *glyphs.toTypedArray()) +
                listOf("save()", "setTransform(1,0,0,1,0,0)", "globalAlpha=0.502", "drawImage(LAYER,$r,$r)", "restore()") +
                listOf("fillStyle=rgba(255,0,0,1)") + glyphs.filter { !it.startsWith("line") && !it.startsWith("stroke") },
            calls,
        )
    }

    // a shadow of outlined text: the two together, blurred as one
    @Test
    fun anOutlinedTextsShadowIsItsOutlineAndLettersBlurredAsOne() {
        val calls = drawn(Op.Text("ab", 10.0, 50.0, "system", 400, 20.0, red, stroke = TextStroke(1.0, red), shadow = TextShadow(2.0, 3.0, 4.0, half)))
        val shadow = calls.subList(0, calls.indexOfFirst { it.startsWith("drawImage(LAYER") } + 1)
        // each laid over its own room: the shadow's moved and blurred 4 px round (its blur reaching twice that), the
        // outline's where the text is
        val (shadowLayer, outlineLayer) = calls.filter { it.startsWith("drawImage(LAYER") }.map(::layerRect)
        assertLayerRound(shadowLayer, 3.0, 24.0, 41.0, 66.0)
        assertLayerRound(outlineLayer, 9.0, 29.0, 31.0, 55.0)
        assertTrue("layer.translate(12,53)" in shadow && "layer.strokeText(ab,0,0)" in shadow && "layer.fillStyle=#000000" in shadow, "$shadow")
        assertEquals(listOf("shadowColor=$blue", "shadowBlur=5.619"), shadow.filter { it.startsWith("shadowColor") || it.startsWith("shadowBlur") })
        assertEquals(2, calls.count { it.startsWith("drawImage(LAYER") }, "the shadow, then the outline")
    }

    // a canvas without letterSpacing: each letter where one with it would put it, not the whole text off by the half
    @Test
    fun spacedTextOnACanvasWithoutLetterSpacingIsSetLetterByLetter() {
        val op = Op.Text("ab", 10.0, 50.0, "system", 400, 20.0, red, letterSpacing = 0.1)
        val without = drawn(op, ctx = recorder(letterSpacing = false))
        assertEquals(
            listOf("translate(11,50)", "fillText(a,0,0)", "measureText(a)", "fillText(b,12,0)", "measureText(b)"),
            without.filter { it.startsWith("translate") || it.startsWith("fillText") || it.startsWith("measureText") },
        )
        assertTrue(without.none { it.startsWith("letterSpacing") })
        val with = drawn(op)
        assertEquals(listOf("letterSpacing=2px", "translate(11,50)", "fillText(ab,0,0)"), with.filter { it.startsWith("translate") || it.startsWith("fillText") || it.startsWith("letterSpacing") })
    }

    // a viewBox whose origin is not 0 0 (Material Symbols' 0 -960 960 960): moved to the key's corner, else the
    // icon is drawn a whole key higher
    @Test
    fun anIconsViewBoxOriginIsMovedToTheCorner() {
        val icon: dynamic = js("({ vx: 0, vy: -960, vw: 960, vh: 960, paths: [{ d: 'M0 0' }] })")
        val calls = drawn(Op.Icon("x", Box(0.0, 0.0, 24.0, 24.0), red), Assets(icons = mapOf("x" to icon)))
        val scale = calls.indexOfFirst { it.startsWith("scale(") }
        assertEquals(listOf("translate(0,24)", "scale(0.025,0.025)"), calls.subList(scale - 1, scale + 1), "$calls")
    }

    // a wide theme SVG keeps its shape, centred down the box (SVG's xMidYMid meet), not stretched
    @Test
    fun aWideIconIsScaledAlikeAndCentred() {
        val icon: dynamic = js("({ vx: 0, vy: 0, vw: 48, vh: 24, paths: [{ d: 'M0 0L48 24Z' }] })")
        val calls = drawn(Op.Icon("x", Box(0.0, 0.0, 24.0, 24.0), red), Assets(icons = mapOf("x" to icon)))
        val scale = calls.indexOfFirst { it.startsWith("scale(") }
        assertEquals(listOf("translate(0,6)", "scale(0.5,0.5)"), calls.subList(scale - 1, scale + 1), "$calls")
    }

    // the app's own icons (no vx) fill the box as their VectorDrawable's bounds do, a box a px wider than tall too
    @Test
    fun anAppIconIsStretchedToItsBox() {
        val icon: dynamic = js("({ vw: 24, vh: 24, paths: [{ d: 'M0 0L24 24Z' }] })")
        val calls = drawn(Op.Icon("x", Box(0.0, 0.0, 25.0, 24.0), red), Assets(icons = mapOf("x" to icon)))
        assertTrue("scale(1.042,1)" in calls, "$calls")
        assertTrue(calls.none { it == "translate(0.5,0)" }, "$calls")
    }

    // a theme path's own colour (an ARGB integer from readIcon) keeps its hue, faded by the tint's alpha; one without takes the tint
    @Test
    fun anIconPathsOwnFillIsKeptAndTheTintsAlphaApplied() {
        val icon: dynamic = js("({ vw: 24, vh: 24, paths: [{ d: 'M0 0L1 1Z', fill: 0xff0000ff | 0 }, { d: 'M0 0L2 2Z' }] })")
        val halfRed = Argb.of(128, 255, 0, 0)
        val calls = drawn(Op.Icon("x", Box(0.0, 0.0, 24.0, 24.0), halfRed), Assets(icons = mapOf("x" to icon)))
        assertEquals(
            listOf("fillStyle=rgba(0,0,255,${128 / 255.0})", "fillStyle=rgba(255,0,0,${128 / 255.0})"),
            calls.filter { it.startsWith("fillStyle") },
        )
        // the faded alpha rounded, not cut: 200 × 200 / 255 is 156.86
        val faded = drawn(Op.Icon("x", Box(0.0, 0.0, 24.0, 24.0), Argb.of(200, 255, 0, 0)), Assets(icons = mapOf("x" to js("({ vw: 24, vh: 24, paths: [{ d: 'M0 0L1 1Z', fill: 0xc80000ff | 0 }] })"))))
        assertEquals(listOf("fillStyle=rgba(0,0,255,${157 / 255.0})"), faded.filter { it.startsWith("fillStyle") })
        // a transparent icon-color hides the whole icon, its own colours too
        val none = drawn(Op.Icon("x", Box(0.0, 0.0, 24.0, 24.0), Argb.TRANSPARENT), Assets(icons = mapOf("x" to icon)))
        assertTrue(none.none { it.startsWith("fill") }, "$none")
    }

    @Test
    fun anIconsStrokeIsVectorDrawablesDefaults() {
        val icon: dynamic = js("({ vw: 24, vh: 24, paths: [{ d: 'M0 0L24 24', stroke: 2 }] })")
        val calls = drawn(Op.Icon("x", Box(0.0, 0.0, 24.0, 24.0), red), Assets(icons = mapOf("x" to icon)))
        assertEquals(
            listOf("save()", "strokeStyle=rgba(255,0,0,1)", "lineWidth=2", "lineCap=butt", "lineJoin=miter", "miterLimit=4", "stroke(PATH M0 0L24 24)", "restore()"),
            calls.dropWhile { it != "scale(1,1)" }.drop(1).dropLast(1),
        )
    }
}

/**
 * A 2D context that writes down each call and each property set, numbers to three places; a gradient's stops too.
 * Its canvas is 400 × 300, and a canvas made of its document (a painter's layer) writes down on the same list,
 * `layer.` before each; its transform is none, written `M`; measureText's advance is 10 a letter. Without
 * [letterSpacing], it has none (a canvas from before 2023).
 */
internal fun recorder(letterSpacing: Boolean = true): dynamic = makeRecorder(letterSpacing)

// a layer's room (x, y, w, h) as its drawImage call wrote it
private fun layerRect(call: String): List<Double> = call.substringAfter("drawImage(LAYER,").split(",").take(4).map { it.toDouble() }

// [rect] within the recorder's canvas (400 × 300) and smaller, round the ink from [left], [top] to [right], [bottom]
private fun assertLayerRound(rect: List<Double>, left: Double, top: Double, right: Double, bottom: Double) {
    val x = rect[0]
    val y = rect[1]
    val w = rect[2]
    val h = rect[3]
    assertTrue(x >= 0 && y >= 0 && x + w <= 400 && y + h <= 300 && w * h < 400.0 * 300, "$rect: past the canvas")
    assertTrue(x <= left && y <= top && x + w >= right && y + h >= bottom, "$rect: does not hold the ink")
}

private val makeRecorder: dynamic = js(
    """(function (spacing) {
  var calls = [];
  function str(a) { return typeof a === 'number' ? String(Math.round(a * 1000) / 1000 + 0) : String(a); }
  var gradient = { addColorStop: function (at, c) { calls.push('stop(' + str(at) + ',' + c + ')'); }, toString: function () { return 'GRADIENT'; } };
  // a stand-in Path2D for the whole test run, on purpose: node has none, and every test that paints an icon or a
  // shape needs one; a runtime with its own keeps it
  if (typeof globalThis.Path2D === 'undefined') {
    globalThis.Path2D = function (d) { this.d = d; };
    globalThis.Path2D.prototype.toString = function () { return 'PATH ' + this.d; };
  }
  function make(prefix, canvas) {
    var target = {
      calls: calls,
      canvas: canvas,
      getTransform: function () { return { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0, toString: function () { return 'M'; } }; },
      measureText: function (t) { calls.push(prefix + 'measureText(' + t + ')'); return { width: 10 * t.length }; }
    };
    if (spacing) target.letterSpacing = '0px';
    return new Proxy(target, {
      get: function (t, k) {
        if (k in t) return t[k];
        return function () {
          calls.push(prefix + String(k) + '(' + Array.prototype.map.call(arguments, str).join(',') + ')');
          return k === 'createLinearGradient' ? gradient : undefined;
        };
      },
      set: function (t, k, v) { calls.push(prefix + String(k) + '=' + str(v)); return true; }
    });
  }
  var page = { width: 400, height: 300, ownerDocument: { createElement: function () {
    var layer = { width: 0, height: 0, toString: function () { return 'LAYER'; } };
    var ctx = make('layer.', layer);
    layer.getContext = function () { return ctx; };
    return layer;
  } } };
  return make('', page);
})"""
)
