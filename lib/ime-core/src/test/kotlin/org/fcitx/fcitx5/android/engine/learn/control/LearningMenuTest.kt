/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu.Reason
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LearningMenu]: the delete named by the word, the op each row asks, the reason of each tier. */
class LearningMenuTest {
    private fun menu(kind: WordKind) =
        LearningResult.Menu("指导", kind, MenuItem.entries, null, BlockScope.EVERYWHERE)

    @Test
    fun theDeleteIsNamedByWhatTheWordIs() {
        val rows = mapOf(
            MenuItem.DELETE to Row.FORGET, MenuItem.BLOCK to Row.BLOCK, MenuItem.PIN to Row.PIN, MenuItem.UNPIN to Row.UNPIN,
            MenuItem.DELETE_PHRASE to Row.DELETE_PHRASE, MenuItem.NO_PREDICT to Row.NO_PREDICT, MenuItem.WHY to Row.WHY,
        )
        // each item its row, in the menu's order whatever the enum's
        assertEquals(MenuItem.entries.map(rows::getValue), LearningMenu.rows(menu(WordKind.DICTIONARY)))
        for (kind in listOf(WordKind.MINE, WordKind.ADDED, WordKind.IMPORTED)) assertEquals(Row.DELETE, LearningMenu.rows(menu(kind)).first())
    }

    @Test
    fun eachRowAsksItsOp() {
        assertEquals(LearningOp.Delete(2, "指导"), LearningMenu.op(Row.FORGET, 2, "指导", paged = false))
        assertEquals(LearningOp.Delete(2, "指导"), LearningMenu.op(Row.DELETE, 2, "指导", paged = false))
        assertEquals(LearningOp.OnPage(LearningOp.Block(2, "指导")), LearningMenu.op(Row.BLOCK, 2, "指导", paged = true))
        assertEquals(LearningOp.Pin(0, "a"), LearningMenu.op(Row.PIN, 0, "a", paged = false))
        assertEquals(LearningOp.Unpin(0, "a"), LearningMenu.op(Row.UNPIN, 0, "a", paged = false))
        assertEquals(LearningOp.Unpin(0, "a"), LearningMenu.op(Row.DELETE_PHRASE, 0, "a", paged = false))
        assertEquals(LearningOp.NoPredict(0, "a"), LearningMenu.op(Row.NO_PREDICT, 0, "a", paged = false))
        assertNull(LearningMenu.op(Row.WHY, 0, "a", paged = false))
        assertTrue(LearningMenu.confirms(Row.BLOCK))
        assertFalse(LearningMenu.confirms(Row.FORGET))
    }

    @Test
    fun eachTierHasItsReason() {
        val reasons = mapOf(
            Tier.PINNED to Reason.PINNED, Tier.FIRST_HERE to Reason.FIRST_HERE, Tier.ACROSS to Reason.ACROSS, Tier.USED to Reason.USED, Tier.FLOORED to Reason.FLOORED, Tier.LIFTED to Reason.LIFTED,
            Tier.MINE to Reason.MINE, Tier.DICTIONARY to Reason.DICTIONARY, Tier.IMPORTED to Reason.IMPORTED,
        )
        assertEquals(HabitRanker.Tier.entries.toSet(), reasons.keys)
        for ((tier, reason) in reasons) assertEquals(reason, LearningMenu.reason(Why(tier, 3)))
        // a 联想: what the user did after the word before, else that it follows it
        val predicted = Code(Kinds.PREDICTION, "")
        val afterWord = setOf(Tier.PINNED, Tier.FIRST_HERE, Tier.USED)
        for (tier in Tier.entries) {
            assertEquals(if (tier in afterWord) reasons.getValue(tier) else Reason.PREDICTION, LearningMenu.reason(Why(tier, 3, predicted)))
        }
        assertEquals(Reason.PREDICTION, LearningMenu.reason(Why(null, 0, predicted)))
        // a 联想's, as the session gives it: EnginesLearningTest.aPredictionsWhyIsThatItFollowsTheWordBefore
        assertEquals(Reason.UNKNOWN, LearningMenu.reason(Why(null)))
        assertEquals(Reason.UNKNOWN, LearningMenu.reason(null))
    }

    @Test
    fun anOpOnTheCandidateCanMoveToAnotherIndex() {
        val ops = listOf(
            LearningOp.Menu(0, "a"), LearningOp.Delete(0, "a"), LearningOp.Block(0, "a"), LearningOp.Pin(0, "a"),
            LearningOp.Unpin(0, "a"), LearningOp.NoPredict(0, "a"), LearningOp.Report(0, "a"),
        )
        for (op in ops) assertEquals(5, op.at(5).index)
    }
}
