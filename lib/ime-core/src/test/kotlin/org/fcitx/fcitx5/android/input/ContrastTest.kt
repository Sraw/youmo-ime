/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {

    private val white = 0xffffffff.toInt()
    private val black = 0xff000000.toInt()

    @Test
    fun blackOnWhiteIsTheMostAndAColourOnItselfTheLeast() {
        assertEquals(21.0, Contrast.ratio(black, white), 0.01)
        assertEquals(1.0, Contrast.ratio(0xff4285f4.toInt(), 0xff4285f4.toInt()), 0.0001)
    }

    @Test
    fun aColourThatReadsIsKeptAsItIs() = assertRows(
        "a legible colour is kept as it is" to {
            val darkBlue = 0xff0d47a1.toInt()
            assertEquals(darkBlue, Contrast.legible(darkBlue, white, toward = black))
        },
        "a translucent one that reads, drawn on the background, keeps its alpha" to {
            val translucent = 0xcc000000.toInt()
            assertEquals(translucent, Contrast.legible(translucent, white, toward = black))
        },
    )

    /** F3477: alpha was ignored, so 0x990d47a1 (9:1 opaque, 3.2:1 drawn on white) was kept unread. */
    @Test
    fun aTranslucentColourIsJudgedAsDrawn() {
        val translucent = 0x990d47a1.toInt()
        assertEquals(3.2, Contrast.ratio(translucent, white), 0.1)
        val c = Contrast.legible(translucent, white, toward = black)
        assertNotEquals(translucent, c)
        assertEquals("a blended one is opaque", 0xff, c ushr 24)
        assertTrue(Contrast.ratio(c, white) >= Contrast.TEXT)
    }

    @Test
    fun anIllegibleOneIsBlendedTillItReads() = assertRows(
        // MaterialLight's accent on its popup colour: 1.75:1
        "a pale one is blended toward the text till it is legible" to {
            val accent = 0xff80cbc4.toInt()
            val popup = 0xffd9dbdd.toInt()
            val c = Contrast.legible(accent, popup, toward = 0xff212121.toInt())
            assertNotEquals(accent, c)
            assertTrue(Contrast.ratio(c, popup) >= Contrast.TEXT)
            // the least blend that does: not the text colour itself
            assertNotEquals(0xff212121.toInt(), c)
        },
        "with the text not legible either it goes toward black or white" to {
            val grey = 0xff777777.toInt()
            val c = Contrast.legible(0xff888888.toInt(), grey, toward = 0xff808080.toInt())
            assertTrue(Contrast.ratio(c, grey) >= Contrast.TEXT)
        },
        "an unreachable minimum ends at the extreme" to {
            assertEquals(white, Contrast.legible(0xff222222.toInt(), black, toward = 0xff333333.toInt(), min = 30.0))
        },
    )
}
