/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingKeyboardTest {

    @Test
    fun itIsAPhonesWidthAndItsKeysAsNarrowedButNotTooShort() {
        assertEquals(350, FloatingKeyboard.widthDp(500))
        assertEquals(FloatingKeyboard.MAX_DP, FloatingKeyboard.widthDp(1280))
        assertEquals(FloatingKeyboard.MIN_DP, FloatingKeyboard.widthDp(360))
        assertEquals(300, FloatingKeyboard.widthDp(300))
        assertEquals(850, FloatingKeyboard.height(1000, 300, 1000))
        assertEquals(900, FloatingKeyboard.height(1000, 450, 500))
        assertEquals(1000, FloatingKeyboard.height(1000, 500, 500))
    }

    @Test
    fun aSpotIsAShareOfTheRoomSoATurnOfTheScreenKeepsItsCorner() {
        assertEquals(150f to 600f, FloatingKeyboard.place(FloatingKeyboard.DEFAULT, 100f, 400f, 400f, 1000f))
        val spot = FloatingKeyboard.spot(300f, 0f, 100f, 400f, 400f, 1000f)
        assertEquals(Spot(1f, 0f), spot)
        // turned: the same corner of a wider, shorter screen
        assertEquals(900f to 0f, FloatingKeyboard.place(spot, 100f, 400f, 1000f, 400f))
        // dragged past an edge: kept within
        assertEquals(Spot(0f, 1f), FloatingKeyboard.spot(-50f, 900f, 100f, 400f, 400f, 1000f))
        // no room to move: as far as there is
        assertEquals(Spot(1f, 1f), FloatingKeyboard.spot(0f, 0f, 400f, 1000f, 400f, 1000f))
        assertEquals(0f to 0f, FloatingKeyboard.place(Spot(0.5f, 0.5f), 400f, 1000f, 400f, 1000f))
    }

    @Test
    fun itsCornersAreTheKeysRoundedByTheirMarginToTheEdge() {
        // the default theme: keys 4 dp round, 3 dp from the sides, 7 dp from the bottom
        assertEquals(7, FloatingKeyboard.cornerDp(keyRadius = 4, hMargin = 3, vMargin = 7))
        // square keys, a corner as near square; round keys, as round
        assertEquals(0, FloatingKeyboard.cornerDp(0, 3, 7))
        assertEquals(15, FloatingKeyboard.cornerDp(10, 5, 9))
        // keys with no margin: the keys' radius alone; keys drawn square (no border): the margin alone
        assertEquals(4, FloatingKeyboard.cornerDp(4, 0, 0))
        assertEquals(2, FloatingKeyboard.cornerDp(10, 2, 2, bordered = false))
    }

    @Test
    fun noCornerKeysFaceIsClipped() {
        // any radius and margins, keys drawn with a border or square
        for (r in 0..16) for (h in 0..12) for (v in 0..12) for (bordered in listOf(true, false)) {
            assertTrue("r=$r h=$h v=$v bordered=$bordered: a corner key clipped", !clipsTheCornerKey(r, h, v, bordered))
        }
    }

    // the bottom-left corner, the keyboard's at (0, 0): whether a point of the corner key's face in
    // the corner's square is outside its arc; the face's corner is an arc round (h + r, v + r), or
    // the point (h, v) for a square face
    private fun clipsTheCornerKey(r: Int, h: Int, v: Int, bordered: Boolean): Boolean {
        val big = FloatingKeyboard.cornerDp(r, h, v, bordered).toDouble()
        val face = if (bordered) r.toDouble() else 0.0
        return (0..90).map { Math.toRadians(180.0 + it) }.any { a ->
            val x = h + face + face * Math.cos(a)
            val y = v + face + face * Math.sin(a)
            x < big && y < big && Math.hypot(big - x, big - y) > big + 1e-9
        }
    }

    @Test
    fun theKeysTextIsAsMuchSmallerAsTheKeysAreShorter() {
        // a phone: 70% as wide, its keys 85% as tall, and their text
        assertEquals(FloatingKeyboard.MIN_HEIGHT_SHARE, FloatingKeyboard.textScale(300, 411), 1e-6f)
        assertEquals(850, FloatingKeyboard.height(1000, 300, 411))
        // a wider share than that: the share itself
        assertEquals(0.9f, FloatingKeyboard.textScale(450, 500), 1e-6f)
    }

    @Test
    fun letGoOfByTheDockedOneItsGripIsUnderTheFingerWithinTheRoom() {
        // a 400×300 keyboard, its grip at the middle of its bottom strip, in a 1000×2000 room
        fun at(fingerX: Float, fingerY: Float) =
            FloatingKeyboard.underFinger(fingerX, fingerY, gripX = 200f, gripY = 288f, w = 400f, h = 300f, parentW = 1000f, room = 2000f)
        // its left 300, its top 850: the middle of the room it moves in
        assertEquals(Spot(0.5f, 0.5f), at(500f, 1138f))
        // the docked grip, below the room's bottom: as low as it goes, the drag going on from there
        assertEquals(Spot(0.5f, 1f), at(500f, 2100f))
        // by the left edge, at the top: kept in
        assertEquals(Spot(0f, 0f), at(50f, 10f))
    }

    @Test
    fun itShrinksFromTheDockedKeyboardAboutOnePoint() {
        // docked 0..1000 wide; floating 300..700
        val scale = FloatingKeyboard.startScale(1000f, 400f)
        assertEquals(2.5f, scale, 0f)
        val pivot = FloatingKeyboard.pivot(0f, 1000f, 300f, 400f)
        // scaled about the pivot, its edges are the docked one's
        assertEquals(0f, 300f + pivot * (1 - scale), 0.01f)
        assertEquals(1000f, 300f + pivot + (400f - pivot) * scale, 0.01f)
        // the same size: no shrink, any pivot
        assertEquals(1f, FloatingKeyboard.startScale(400f, 400f), 0f)
        assertEquals(200f, FloatingKeyboard.pivot(0f, 400f, 300f, 400f), 0f)
        assertEquals("not laid out", 1f, FloatingKeyboard.startScale(400f, 0f), 0f)
    }

    // 061-floating-resize §2.1: never resized, its size is 0.6.0's to the pixel
    @Test
    @Uat("TB-61")
    fun neverResizedItIsAsItWas() {
        for (screen in listOf(300, 360, 411, 500, 600, 841, 1280)) for (docked in listOf(531, 700, 1000)) {
            val w0 = FloatingKeyboard.widthDp(screen)
            val bounds = FloatingKeyboard.bounds(screen, screen, docked, 100, 5000)
            val size = FloatingKeyboard.size(-1f, screen, docked, bounds)
            assertEquals(w0, size.widthDp)
            assertEquals(FloatingKeyboard.height(docked, w0, screen), size.keysHeightPx)
            assertEquals(FloatingKeyboard.textScale(w0, screen), size.textScale, 0f)
            assertEquals(1f, size.resize, 0f)
            // resized to its default width, the same
            assertEquals(size, FloatingKeyboard.size(w0.toFloat(), screen, docked, bounds))
        }
    }

    // the user's ①: all of it as much bigger or smaller, not held at 0.85 of the docked keys as the default width is
    @Test
    fun resizedItsKeysAndTextGoWithItsWidth() {
        val bounds = FloatingKeyboard.bounds(411, 411, 1000, 100, 5000)
        val big = FloatingKeyboard.size(360f, 411, 1000, bounds)
        assertEquals(360, big.widthDp)
        assertEquals(1.2f, big.resize, 0.0001f)
        assertEquals("850 × 1.2", 1020, big.keysHeightPx)
        assertEquals("the default's text, drawn 1.2 times as big (SceneEnvs.of)", 0.85f, big.textScale, 0f)
        val small = FloatingKeyboard.size(270f, 411, 1000, bounds)
        assertEquals(0.9f, small.resize, 0.0001f)
        assertEquals(765, small.keysHeightPx)
    }

    @Test
    fun itIsResizedWithinItsBounds() {
        // a phone upright: from 260 to the docked width
        assertEquals(FloatingKeyboard.Bounds(FloatingKeyboard.MIN_RESIZE_DP, 411), FloatingKeyboard.bounds(411, 411, 1000, 100, 5000))
        val bounds = FloatingKeyboard.bounds(411, 411, 1000, 100, 5000)
        assertEquals(260, FloatingKeyboard.size(100f, 411, 1000, bounds).widthDp)
        assertEquals(411, FloatingKeyboard.size(900f, 411, 1000, bounds).widthDp)
        // R26 F8169: a width not a number (a backup edited by hand) threw in roundToInt; never resized instead
        assertEquals(FloatingKeyboard.size(-1f, 411, 1000, bounds), FloatingKeyboard.size(Float.NaN, 411, 1000, bounds))
        // a tablet turned, 1280 × 800: the height stops it long before the docked width. Its keys 500 × 0.85 high at
        // 420 dp, the bars 64, the room 696: (696 - 64) / (425 / 420) = 624
        assertEquals(FloatingKeyboard.Bounds(260, 624), FloatingKeyboard.bounds(1280, 1280, 500, 64, 696))
        // never less than its default, never more than it at the least: a screen too low for more, or too narrow for 260
        assertEquals(FloatingKeyboard.Bounds(260, 420), FloatingKeyboard.bounds(1280, 1280, 500, 64, 300))
        assertEquals(FloatingKeyboard.Bounds(240, 240), FloatingKeyboard.bounds(240, 240, 500, 64, 5000))
    }

    // 竖屏 and 横屏 each their own, the one place 0.6.0 kept for both till then
    @Test
    fun aShapeNeverKeptHasTheOldPlace() {
        val old = Spot(0.2f, 0.3f)
        assertEquals(old, FloatingKeyboard.keptSpot(-1f, -1f, old))
        assertEquals(old, FloatingKeyboard.keptSpot(0.5f, -1f, old))
        assertEquals(Spot(0f, 1f), FloatingKeyboard.keptSpot(0f, 1f, old))
        // R26 F8169: not a number, as a backup edited by hand may have it: never kept
        assertEquals(old, FloatingKeyboard.keptSpot(Float.NaN, 0.5f, old))
        assertEquals(old, FloatingKeyboard.keptSpot(0.5f, Float.NaN, old))
    }

    // a 300 dp keyboard, density 2: 600 × 800 px, its keys 600 of that; the strip at the bottom, the handle at its
    // bottom left, the top right staying, room enough around
    private fun resize(bounds: FloatingKeyboard.Bounds = FloatingKeyboard.Bounds(260, 411), across: Float = 5000f, along: Float = 5000f, down: Boolean = true) =
        FloatingKeyboard.Resize(300, 2f, 600f, 800f, 600f, down, bounds, across, along)

    @Test
    fun theHandleMovedAlongTheDiagonalResizesIt() {
        val r = resize()
        assertEquals(300, r.widthDp(0f, 0f))
        // out along the diagonal (left and down) by a fifth of it, 200 px: 1.2 times as big
        assertEquals(360, r.widthDp(-120f, 160f))
        // in by a tenth
        assertEquals(270, r.widthDp(60f, -80f))
        // across the diagonal: no change
        assertEquals(300, r.widthDp(80f, 60f))
        // growing up (the strip on top, or at the bottom of the room): out is left and up
        assertEquals(360, resize(down = false).widthDp(-120f, -160f))
    }

    @Test
    @Uat("TB-58")
    fun atTheRoomsEdgeOnTheStripsSideItGrowsTheOtherWay() {
        // a room 100 to 1100 px, the keyboard 400 px tall: the strip at the bottom, it grows down, unless at the bottom
        assertTrue(FloatingKeyboard.growsDown(handleBottom = true, top = 300f, bottom = 700f, roomTop = 100f, roomBottom = 1100f))
        assertTrue(FloatingKeyboard.growsDown(handleBottom = true, top = 100f, bottom = 500f, roomTop = 100f, roomBottom = 1100f))
        assertFalse(FloatingKeyboard.growsDown(handleBottom = true, top = 700f, bottom = 1100f, roomTop = 100f, roomBottom = 1100f))
        // a place kept as a share, back a hair off the edge: still at it
        assertFalse(FloatingKeyboard.growsDown(handleBottom = true, top = 699.6f, bottom = 1099.6f, roomTop = 100f, roomBottom = 1100f))
        // the strip on top: up, unless at the top
        assertFalse(FloatingKeyboard.growsDown(handleBottom = false, top = 300f, bottom = 700f, roomTop = 100f, roomBottom = 1100f))
        assertFalse(FloatingKeyboard.growsDown(handleBottom = false, top = 700f, bottom = 1100f, roomTop = 100f, roomBottom = 1100f))
        assertTrue(FloatingKeyboard.growsDown(handleBottom = false, top = 100f, bottom = 500f, roomTop = 100f, roomBottom = 1100f))
    }

    @Test
    @Uat("TB-59")
    fun aResizeStopsAtItsBoundsAndTheScreensEdgesAndSaysSo() {
        val r = resize()
        assertEquals(260, r.widthDp(3000f, -4000f))
        assertEquals(FloatingKeyboard.Limit.Min, r.limit(260))
        assertEquals(411, r.widthDp(-3000f, 4000f))
        assertEquals(FloatingKeyboard.Limit.Max, r.limit(411))
        assertEquals(FloatingKeyboard.Limit.None, r.limit(300))
        // the screen's left 700 px from the staying corner: 350 dp; the room's bottom 1000 px down: (1000 - 200) / 2 = 400
        assertEquals(350, resize(across = 700f).widthDp(-3000f, 4000f))
        assertEquals(400, resize(along = 1000f).widthDp(-3000f, 4000f))
        // already past an edge some other way: not shrunk for it
        assertEquals(300, resize(across = 100f).widthDp(-3000f, 4000f))
    }

    @Test
    fun talkBacksStepIsATenth() {
        val r = resize()
        assertEquals(330, r.step(300, bigger = true))
        assertEquals(270, r.step(300, bigger = false))
        assertEquals(411, r.step(400, bigger = true))
        assertEquals(260, r.step(270, bigger = false))
    }
}
