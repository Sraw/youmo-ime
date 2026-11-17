/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.cards

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.ui.cards.CardColumns.CHOICE
import org.fcitx.fcitx5.android.ui.cards.CardColumns.CHOICE_MIN_DP
import org.fcitx.fcitx5.android.ui.cards.CardColumns.GROUP
import org.fcitx.fcitx5.android.ui.cards.CardColumns.GROUP_MIN_DP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cards' columns, name size and samples (dev/design/061-settings-cards.md §2.3, §2.9, §3.1). */
class CardColumnsTest {

    private fun choice(width: Int, scale: Float) = CardColumns.columns(width.toFloat(), scale, CHOICE_MIN_DP, CHOICE)
    private fun group(width: Int, scale: Float) = CardColumns.columns(width.toFloat(), scale, GROUP_MIN_DP, GROUP)

    // §2.3's table: width, scale → columns, card width (dp), name sp
    @Test
    fun `keyboard cards as the design's four cases have them`() {
        data class Case(val width: Int, val scale: Float, val columns: Int, val card: Int, val sp: Int)
        listOf(
            Case(360, 1.0f, 4, 76, 12),
            Case(393, 1.0f, 4, 84, 13),
            Case(360, 1.3f, 3, 104, 12),
            Case(393, 1.3f, 3, 115, 13),
        ).forEach { case ->
            val at = "${case.width} dp × ${case.scale}"
            val got = choice(case.width, case.scale)
            assertEquals(at, case.columns, got)
            val w = CardColumns.cardWidth(case.width.toFloat(), got)
            assertEquals("$at card", case.card, w.toInt())
            assertEquals("$at name", case.sp, CardColumns.nameSp(w, case.scale))
        }
    }

    // R26 F8395: the columns' cases, a test each that differed only in width and scale, one table
    @Test
    fun `keyboard cards' columns`() = assertRows(
        "one row of eight from 696 dp" to {
            assertEquals(8, choice(696, 1.0f))
            assertEquals(4, choice(695, 1.0f))
            assertEquals(8, choice(780, 1.0f))
        },
        // n = 5, 6, 7 fit but would leave a short last row: the next candidate down
        "n = 5 leaves one card alone: four" to { assertEquals(4, choice(452, 1.0f)) },
        "n = 6: four" to { assertEquals(4, choice(536, 1.0f)) },
        "n = 7: four" to { assertEquals(4, choice(620, 1.0f)) },
        "two columns however large the text" to {
            assertEquals(2, choice(360, 1.5f))
            assertEquals(2, choice(200, 2.0f))
        },
    )

    @Test
    fun `the name's size turns at 82 dp times the scale`() {
        assertEquals(13, CardColumns.nameSp(82f, 1.0f))
        assertEquals(12, CardColumns.nameSp(81.9f, 1.0f))
        assertEquals(12, CardColumns.nameSp(106f, 1.3f))
        assertEquals(13, CardColumns.nameSp(107f, 1.3f))
    }

    // §2.9, §2.10: two columns on a phone, one at 1.5 or at 360 dp × 1.3, four from 584 dp
    @Test
    fun `group cards`() = assertRows(
        "360 dp: two" to { assertEquals(2, group(360, 1.0f)) },
        "393 dp: two" to { assertEquals(2, group(393, 1.0f)) },
        "393 dp × 1.3: two" to { assertEquals(2, group(393, 1.3f)) },
        "393 dp × 1.5: one" to { assertEquals(1, group(393, 1.5f)) },
        "360 dp × 1.3: one" to { assertEquals(1, group(360, 1.3f)) },
        "584 dp, the first width four fit: (584 − 24) ÷ 140 = 4" to { assertEquals(4, group(584, 1.0f)) },
        "600 dp: four" to { assertEquals(4, group(600, 1.0f)) },
        "780 dp: four" to { assertEquals(4, group(780, 1.0f)) },
        "583 dp, n = 3: three would leave one alone, so two" to { assertEquals(2, group(583, 1.0f)) },
    )

    @Test
    fun `nonsense is refused`() {
        assertThrows(IllegalArgumentException::class.java) { CardColumns.columns(393f, 1f, CHOICE_MIN_DP, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { CardColumns.columns(393f, 0f, CHOICE_MIN_DP, CHOICE) }
        assertThrows(IllegalArgumentException::class.java) { CardColumns.columns(393f, 1f, 0, CHOICE) }
        assertThrows(IllegalArgumentException::class.java) { CardColumns.cardWidth(393f, 0) }
    }

    @Test
    fun `samples are drawn from a window 600 dp high`() {
        assertFalse(CardColumns.samples(568f))
        assertFalse(CardColumns.samples(599f))
        assertTrue(CardColumns.samples(600f))
        assertTrue(CardColumns.samples(851f))
        assertFalse(CardColumns.samples(360f)) // a phone on its side
    }
}
