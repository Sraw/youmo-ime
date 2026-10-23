/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard.Spot
import org.junit.Assert.assertEquals
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
    fun theHandleIsAShadeOfTheBarTowardsTheText() {
        // a light bar with dark text: a little darker; a dark bar with light text: a little lighter
        assertEquals(0xFFE6E6E6.toInt(), FloatingKeyboard.handleColor(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals(0xFF1A1A1A.toInt(), FloatingKeyboard.handleColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        // the bar's alpha kept, each channel on its own
        assertEquals(0x80F2ECE9.toInt(), FloatingKeyboard.handleColor(0x80FFFFFF.toInt(), 0x00804020))
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
}
