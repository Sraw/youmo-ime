/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.web

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.theme.Argb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the painter draws of the ops the app's own drawing is not compared on (web/theme-maker/test/engine.test.mjs
 * holds the rest to the app's ScenePainter: no snapshot has these): a nine-slice, the picture, a gradient, a
 * ripple, on a context that writes down each call. A nine-slice is cut as the app's NineSliceLayout cuts it
 * (KeyFaceGeometryTest's cases, here).
 */
class CanvasPainterTest {
    private val red = Argb.of(255, 255, 0, 0)
    private val half = Argb.of(128, 0, 0, 255)
    private val box = Shape.Rect(Box(10.0, 20.0, 110.0, 70.0))

    // the half-alpha blue as CSS has it (128/255 to the last digit)
    private val blue = CanvasPainter.css(half)

    private class Assets(val images: Map<String, dynamic> = emptyMap(), val picture: dynamic = null) : PaintAssets {
        val pictures = mutableListOf<String>()

        override fun family(font: String) = "sans-serif"

        override fun image(file: String): dynamic = images[file]

        override fun picture(blurDp: Int?, brightness: Int?): dynamic {
            pictures += "$blurDp $brightness"
            return picture
        }

        override fun icon(name: String): dynamic = null
    }

    private fun drawn(op: Op, assets: Assets = Assets()): List<String> {
        val ctx = recorder()
        CanvasPainter(ctx, assets, 2.0, 400.0, 300.0).draw(op)
        return (ctx.calls as Array<String>).toList()
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
}

/** A 2D context that writes down each call and each property set, numbers to three places; a gradient's stops too. */
internal fun recorder(): dynamic = js(
    """(function () {
  var calls = [];
  function str(a) { return typeof a === 'number' ? String(Math.round(a * 1000) / 1000 + 0) : String(a); }
  var gradient = { addColorStop: function (at, c) { calls.push('stop(' + str(at) + ',' + c + ')'); }, toString: function () { return 'GRADIENT'; } };
  var target = { calls: calls, getTransform: function () { return { a: 1, b: 0 }; } };
  return new Proxy(target, {
    get: function (t, k) {
      if (k in t) return t[k];
      return function () {
        calls.push(String(k) + '(' + Array.prototype.map.call(arguments, str).join(',') + ')');
        return k === 'createLinearGradient' ? gradient : undefined;
      };
    },
    set: function (t, k, v) { calls.push(String(k) + '=' + str(v)); return true; }
  });
})()""",
)
