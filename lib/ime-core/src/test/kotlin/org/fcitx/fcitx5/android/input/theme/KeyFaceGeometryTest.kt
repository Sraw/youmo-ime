/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.KeyKind
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFaceGeometryTest {

    private val keys = LookDefaults.KEYS

    @Test
    fun aBlockIsInsetByTheGapWithItsShadowBelow() {
        // a 100×160 px cell at 2 px a dp: 3 dp across, 7 dp down
        val face = KeyFaceGeometry.of(100f, 160f, KeyKind.Letter, keys, landscape = false, density = 2f)
        assertEquals(Area(6f, 14f, 94f, 146f), face.face)
        assertEquals(Area(6f, 14f, 94f, 148f), face.shadow)
        assertEquals(face.face, face.pressed)
        assertEquals(8f, face.radius)
        assertEquals(0f, face.border)
        // landscape's gaps; a border and no shadow
        val land = KeyFaceGeometry.of(100f, 160f, KeyKind.Space, keys.copy(borderDp = 2, shadowDp = 0), landscape = true, density = 2f)
        assertEquals(Area(6f, 8f, 94f, 152f), land.face)
        assertNull(land.shadow)
        assertEquals(4f, land.border)
        // no margin: to the cell's edges
        assertEquals(Area(0f, 0f, 100f, 160f), KeyFaceGeometry.of(100f, 160f, KeyKind.Letter, keys, false, 2f, margin = false).face)
    }

    // a key's shadow deeper than the gap below it was drawn past its cell, over the row below
    @Test
    fun aShadowDeeperThanTheGapEndsAtTheCellsBottom() {
        val face = KeyFaceGeometry.of(100f, 160f, KeyKind.Letter, keys.copy(shadowDp = 3, gap = keys.gap.copy(v = 1)), landscape = false, density = 2f)
        // a 1 dp gap under a 3 dp shadow: 158 + 6 px would reach into the row below
        assertEquals(160f, face.shadow!!.bottom)
    }

    @Test
    fun aRadiusOverHalfTheKeyIsKeptAsSet() {
        // the drawing clamps it, as a round rect does; the geometry says what was asked
        assertEquals(48f, KeyFaceGeometry.of(40f, 40f, KeyKind.Letter, keys.copy(radiusDp = 24), false, 2f).radius)
    }

    @Test
    fun flatKeysHaveNoFaceButTheSpaceBarAndReturnsCircle() {
        val flat = keys.copy(blocks = false)
        val letter = KeyFaceGeometry.of(100f, 160f, KeyKind.Letter, flat, false, 2f)
        assertNull(letter.face)
        assertEquals(Area(6f, 14f, 94f, 146f), letter.pressed)
        // the bar: 10 dp in from the sides, 26 dp high at least, at most 16 dp in from top and bottom
        val space = KeyFaceGeometry.of(400f, 160f, KeyKind.Space, flat, false, 2f)
        assertEquals(Area(20f, 32f, 380f, 128f), space.face)
        assertEquals(6f, space.radius)
        val lowSpace = KeyFaceGeometry.of(400f, 40f, KeyKind.Space, flat, false, 2f)
        assertEquals(Area(20f, 0f, 380f, 40f), lowSpace.face)
        val midSpace = KeyFaceGeometry.of(400f, 60f, KeyKind.Space, flat, false, 2f)
        assertEquals(Area(20f, 4f, 380f, 56f), midSpace.face)
        // Return: a circle at most 35 dp
        val enter = KeyFaceGeometry.of(150f, 160f, KeyKind.Enter, flat, false, 2f)
        assertEquals(Area(40f, 45f, 110f, 115f), enter.face)
        assertTrue(enter.oval)
        assertEquals(35f, enter.radius)
        val small = KeyFaceGeometry.of(50f, 60f, KeyKind.Enter, flat, false, 2f)
        assertEquals(Area(0f, 5f, 50f, 55f), small.face)
    }

    @Test
    fun aKeysOwnBorderOverrulesTheStyle() {
        val flat = keys.copy(blocks = false)
        val b = KeyFaceGeometry.Border.entries
        fun of(kind: KeyKind, k: Keys, border: KeyFaceGeometry.Border) = KeyFaceGeometry.of(150f, 160f, kind, k, false, 2f, true, border)
        // 确定 is a block on a flat keyboard; a lit 查词 key in Return's colour is no circle there
        assertEquals(KeyFaceGeometry.of(150f, 160f, KeyKind.Enter, keys, false, 2f), of(KeyKind.Enter, flat, KeyFaceGeometry.Border.On))
        assertNull(of(KeyKind.Enter, flat, KeyFaceGeometry.Border.Default).face)
        assertTrue(of(KeyKind.Enter, flat, KeyFaceGeometry.Border.Special).oval)
        // a cell the column is drawn over: pressed as a block is, no face
        val off = of(KeyKind.Function, keys, KeyFaceGeometry.Border.Off)
        assertNull(off.face)
        assertNull(off.shadow)
        assertEquals(8f, off.radius)
        // blocks: Default and Special alike
        for (border in b - KeyFaceGeometry.Border.Off) {
            assertEquals(border.name, KeyFaceGeometry.of(150f, 160f, KeyKind.Space, keys, false, 2f), of(KeyKind.Space, keys, border))
        }
    }

    @Test
    fun nineSliceKeepsCornersAndStretchesTheRest() {
        // a 90×90 image, 30 px corners at 3 px a dp, drawn at 2 px a dp: 20 px corners
        val slice = NineSlice("f", Insets(30, 30, 30, 30), 3)
        val pieces = NineSliceLayout.of(90, 90, slice, Area(0f, 0f, 200f, 100f), 2f)
        assertEquals(9, pieces.size)
        assertEquals(NineSliceLayout.Piece(Area(0f, 0f, 30f, 30f), Area(0f, 0f, 20f, 20f)), pieces[0])
        assertEquals(NineSliceLayout.Piece(Area(30f, 30f, 60f, 60f), Area(20f, 20f, 180f, 80f)), pieces[4])
        assertEquals(NineSliceLayout.Piece(Area(60f, 60f, 90f, 90f), Area(180f, 80f, 200f, 100f)), pieces[8])
    }

    @Test
    fun cornersShrinkAlikeInATargetSmallerThanThem() {
        // corners of 20 and 60 px in a 40 px target: 10 and 30
        val slice = NineSlice("f", Insets(20, 0, 60, 0), 1)
        val pieces = NineSliceLayout.of(100, 10, slice, Area(0f, 0f, 40f, 10f), 1f)
        // the middle column has no room: two pieces
        assertEquals(listOf(Area(0f, 0f, 10f, 10f), Area(10f, 0f, 40f, 10f)), pieces.map { it.dst })
        assertEquals(listOf(Area(0f, 0f, 20f, 10f), Area(40f, 0f, 100f, 10f)), pieces.map { it.src })
    }

    // a theme's nine-slice insets larger than its image were read past the image's edges
    @Test
    fun insetsLargerThanTheImageTakeNoSourceOutsideIt() {
        val pieces = NineSliceLayout.of(10, 10, NineSlice("f", Insets(8, 8, 8, 8), 1), Area(0f, 0f, 100f, 100f), 1f)
        assertTrue(pieces.isNotEmpty())
        pieces.forEach { p ->
            assertTrue("$p", p.src.left >= 0f && p.src.top >= 0f && p.src.right <= 10f && p.src.bottom <= 10f)
        }
    }

    @Test
    fun noInsetsStretchTheWholeImage() {
        val pieces = NineSliceLayout.of(64, 32, NineSlice("f", Insets(0, 0, 0, 0), 2), Area(5f, 5f, 105f, 55f), 3f)
        assertEquals(listOf(NineSliceLayout.Piece(Area(0f, 0f, 64f, 32f), Area(5f, 5f, 105f, 55f))), pieces)
    }

    // ST-101's imported Baidu skin: 1 px lines a quarter of the panel's colour across its keys' faces, where pieces
    // met mid-pixel and each anti-aliased edge covered half the pixel (half over half: 75%)
    @Test
    fun piecesMeetOnWholePixelsLeavingNoSeam() {
        val slice = NineSlice("f", Insets(24, 24, 24, 24), 3)
        val target = Area(5.5f, 5.5f, 291.5f, 407.3f)
        val pieces = NineSliceLayout.of(150, 168, slice, target, 2.75f)
        // each pixel's cover, the pieces drawn one over another as an anti-aliased canvas draws them
        val cover = Array(410) { DoubleArray(300) }
        for (p in pieces) for (y in 0 until 410) for (x in 0 until 300) {
            val a = overlap(p.dst.left, p.dst.right, x) * overlap(p.dst.top, p.dst.bottom, y)
            cover[y][x] += (1 - cover[y][x]) * a
        }
        for (y in 7 until 406) for (x in 7 until 290) assertEquals("($x,$y)", 1.0, cover[y][x], 1e-6)
    }

    private fun overlap(lo: Float, hi: Float, px: Int): Double = maxOf(0.0, minOf(hi.toDouble(), px + 1.0) - maxOf(lo.toDouble(), px.toDouble()))

    @Test
    fun aKeyShowsThePictureUnderIt() {
        // a wide picture on a taller keyboard: fit to height, the sides cut
        val wide = PictureWindow.centerCrop(2000, 1000, 1000f, 1000f)
        assertEquals(PictureWindow.Placement(1f, -500f, 0f), wide)
        assertEquals(Area(500f, 0f, 600f, 100f), PictureWindow.under(wide, Area(0f, 0f, 100f, 100f)))
        // a tall picture: fit to width, scaled up, top and bottom cut
        val tall = PictureWindow.centerCrop(500, 2000, 1000f, 1000f)
        assertEquals(PictureWindow.Placement(2f, 0f, -1500f), tall)
        assertEquals(Area(450f, 750f, 500f, 1250f), PictureWindow.under(tall, Area(900f, 0f, 1000f, 1000f)))
    }

    @Test
    fun blurRadiusZeroLeavesThePixels() {
        val px = intArrayOf(1, 2, 3, 4)
        assertArrayEquals(px, BoxBlur.blur(px, 2, 2, 0))
        assertThrows(IllegalArgumentException::class.java) { BoxBlur.blur(px, 2, 2, -1) }
    }

    @Test
    fun blurSpreadsAPointEvenly() {
        // one white pixel in black, a row of 5, radius 1: three passes of [1 1 1]/3, the ends clamped (so they gain)
        val row = intArrayOf(0, 0, 0xffffffff.toInt(), 0, 0).map { it or (0xff shl 24) }.toIntArray()
        val out = BoxBlur.blur(row, 5, 1, 1)
        val red = out.map { it ushr 16 and 0xff }
        assertEquals(listOf(38, 57, 66, 57, 38), red)
        // alpha stays opaque
        assertTrue(out.all { it ushr 24 == 0xff })
        // an even field stays even
        val flat = IntArray(12) { 0x80406020.toInt() }
        assertArrayEquals(flat, BoxBlur.blur(flat, 4, 3, 2))
    }

    @Test
    fun shrinkAveragesBlocks() {
        val px = intArrayOf(
            0, 4, 8, 8, 1,
            4, 0, 8, 8, 1,
            9, 9, 9, 9, 9,
        )
        // 2×2 blocks; the fifth column and the third row are a partial block, left out
        assertArrayEquals(intArrayOf(2, 8), BoxBlur.shrink(px, 5, 3, 2))
        // a picture smaller than a block: one pixel of what there is
        assertArrayEquals(intArrayOf(2), BoxBlur.shrink(intArrayOf(1, 3), 2, 1, 4))
    }

    @Test
    fun aCornerIsHalfTheShorterSideAtMostAndNoneOnAKeySqueezedToNothing() {
        assertEquals(6f, cornerRadius(6f, 40f, 30f))
        assertEquals(15f, cornerRadius(20f, 40f, 30f))
        assertEquals(0f, cornerRadius(-2f, 40f, 30f))
        // split screen (ST-57): the border's inset wider than the key, a negative height threw an empty range
        assertEquals(0f, cornerRadius(6f, 40f, -12.5f))
        assertEquals(0f, cornerRadius(6f, -1f, -1f))
    }

    @Test
    fun aKeySqueezedUnderItsGapHasAFaceOfNoSize() {
        // split screen (ST-57): a 4×4 px cell inside a 3 dp gap at 2 px a dp; KeyFaceDrawable draws no face then
        val face = KeyFaceGeometry.of(4f, 4f, KeyKind.Letter, keys, landscape = false, density = 2f)
        assertTrue(face.face?.hasSize != true)
    }

    // A4: a see-through key over the animated picture, darkened from the board's brightness to its own
    @Test
    fun aSeeThroughKeyIsDarkenedFromTheBoards() {
        assertEquals(0, PictureWindow.seeThroughDarken(80, 80))
        assertEquals(50, PictureWindow.seeThroughDarken(80, 40))
        assertEquals(30, PictureWindow.seeThroughDarken(100, 70))
        assertEquals(100, PictureWindow.seeThroughDarken(100, 0))
        // never lighter than the board: none
        assertEquals(0, PictureWindow.seeThroughDarken(60, 90))
        assertEquals(0, PictureWindow.seeThroughDarken(0, 50))
    }
}
