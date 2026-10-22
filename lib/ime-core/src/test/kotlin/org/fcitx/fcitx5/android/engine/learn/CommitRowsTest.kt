/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.CommitRows.Pick
import org.fcitx.fcitx5.android.engine.learn.CommitRows.Shown
import org.junit.Assert.assertEquals
import org.junit.Test

/** [CommitRows]: the three levels a commit learns at (PLAN §2.1), once each. */
class CommitRowsTest {

    // keys from one place to another of the input [keys]
    private fun rows(keys: String, prev: String, vararg picks: Pick) =
        CommitRows.build(prev, picks.toList(), { from, to, _ -> Code(Kinds.FULL, keys.substring(from, to)) }) { Row.DICTIONARY }
            .map { "${it.code.keys}|${it.prev}|${it.words.text}|${it.how}|${if (it.corrected) "corrected" else "used"}|${it.flags and Row.LEVELS}" }

    @Test
    fun aWordRightOfASentenceCorrectedIsUsed() {
        // laoshizhidao: 老师知道 shown first, 老师指导 picked
        val shown = Shown(0, listOf("老师", "知道"), intArrayOf(6, 12))
        val pick = Pick(0, listOf(word("老师", "lao shi"), word("指导", "zhi dao")), intArrayOf(6, 12), 'B', "老师知道", shown)
        assertEquals(
            listOf(
                "laoshizhidao|我|老师指导|B|corrected|3",
                "laoshi|我|老师|B|used|4",
                "zhidao|老师|指导|B|corrected|4",
            ),
            rows("laoshizhidao", "我", pick),
        )
    }

    @Test
    fun aWordTheShownHasNoEndsAroundIsNotLearnedAlone() {
        // dl: 带来 shown, 点 picked then 了
        val shown = Shown(0, listOf("带来"), intArrayOf(2))
        val dian = Pick(0, listOf(word("点", "dian")), intArrayOf(1), 'C', "点", shown)
        val le = Pick(1, listOf(word("了", "le")), intArrayOf(2), 'A', "了", Shown(1, listOf("了"), intArrayOf(2)))
        assertEquals(
            listOf(
                "d||点|C|used|1",
                "l|点|了|A|used|5",
                "dl||点了|C|used|2",
            ),
            rows("dl", "", dian, le),
        )
    }

    // F2739: the decoder's own first picked over another shown first (a habit's) is marked so; picked where it is shown first, not
    @Test
    fun theDecodersOwnFirstPickedOverAnotherIsMarked() {
        val build = { reference: String -> CommitRows.build("", listOf(Pick(0, listOf(word("你", "ni")), intArrayOf(2), 'B', reference, null, own = true)), { _, _, _ -> Code(Kinds.FULL, "ni") }) { Row.DICTIONARY } }
        assertEquals(listOf(true), build("拟").map { it.ownFirst })
        assertEquals(listOf(false), build("你").map { it.ownFirst })
    }

    @Test
    fun aWordPickedAloneIsOneRowOfEveryLevel() {
        val shown = Shown(0, listOf("你"), intArrayOf(2))
        val pick = Pick(0, listOf(word("拟", "ni")), intArrayOf(2), 'B', "你", shown)
        assertEquals(listOf("ni||拟|B|corrected|7"), rows("ni", "", pick))
    }

    @Test
    fun theFirstPickedIsUsedEverywhere() {
        val shown = Shown(0, listOf("你好", "吗"), intArrayOf(5, 7))
        val pick = Pick(0, listOf(word("你好", "ni hao"), word("吗", "ma")), intArrayOf(5, 7), 'A', "你好吗", shown)
        assertEquals(
            listOf("nihaoma||你好吗|A|used|3", "nihao||你好|A|used|4", "ma|你好|吗|A|used|4"),
            rows("nihaoma", "", pick),
        )
    }

    @Test
    fun piecesAreCorrectedWholeIfOneWas() {
        val shown = Shown(0, listOf("你在"), intArrayOf(5))
        val ni = Pick(0, listOf(word("拟", "ni")), intArrayOf(2), 'C', "你", shown)
        val zai = Pick(2, listOf(word("再", "zai")), intArrayOf(5), 'A', "再", null)
        assertEquals(
            listOf("ni||拟|C|corrected|1", "zai|拟|再|A|used|1", "nizai||拟再|C|corrected|2"),
            rows("nizai", "", ni, zai),
        )
    }

    @Test
    fun noReferenceIsNoCorrection() {
        val pick = Pick(0, listOf(word("nihao")), intArrayOf(5), 'F', "", null)
        assertEquals(listOf("nihao||nihao|F|used|3"), rows("nihao", "", pick))
    }

    @Test
    fun aPickOfSyllablesTakenIsH() {
        val pick = Pick(0, listOf(word("你", "ni")), intArrayOf(2), 'H', "拟", null)
        assertEquals(listOf("ni||你|H|corrected|3"), rows("ni", "", pick))
    }
}
