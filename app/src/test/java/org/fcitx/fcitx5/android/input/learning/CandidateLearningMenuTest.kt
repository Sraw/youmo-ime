/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FakeFcitxConnection
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.placeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** [CandidateLearningMenu]: the engine's menu worded, a block asked first, the reason shown, an act sent. */
@RunWith(RobolectricTestRunner::class)
class CandidateLearningMenuTest {
    // the app's strings are not in a unit test's resources: each answered by its id
    private val context = PlaceholderStrings(RuntimeEnvironment.getApplication())

    private fun menu(kind: WordKind) = LearningResult.Menu(
        "指导", kind, listOf(MenuItem.DELETE, MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), Why(HabitRanker.Tier.USED, 3), BlockScope.EVERYWHERE
    )

    @Test
    fun theEnginesMenuIsWordedHere() = runTest {
        val connection = FakeFcitxConnection(this)
        // the ops themselves, not their text: a field renamed is not a behaviour changed
        val ops = mutableListOf<LearningOp>()
        connection.api.learned = { ops += it; if (it is LearningOp.Menu) menu(WordKind.DICTIONARY) else LearningResult.Done(1) }
        val menu = CandidateLearningMenu(context, connection, this) { _, _ -> error("no act yet") }
        val lines = menu.lines(2, "指导", paged = false)!!
        assertEquals(
            listOf(R.string.learning_forget, R.string.learning_block, R.string.learning_pin, R.string.learning_why).map(::placeholder),
            lines.map { it.label.toString() },
        )
        // the block asks first, saying where it reaches
        val confirm = lines[1].pick()!!
        assertFalse(confirm[0].enabled)
        assertEquals(placeholder(R.string.learning_block_everywhere), confirm[0].label)
        // never under the finger that picked the row (review-E-ui S1): a cancel there, it below
        assertEquals(placeholder(android.R.string.cancel), confirm[1].label)
        assertEquals(placeholder(R.string.learning_block_confirm), confirm[2].label)
        // the cancel closes the menu and blocks nothing: a slow double tap is no block (C18)
        assertNull(confirm[1].pick())
        testScheduler.advanceUntilIdle()
        // the reason, with what to copy
        val why = lines[3].pick()!!
        assertEquals(placeholder(R.plurals.learning_why_used), why[0].label)
        assertEquals(placeholder(R.string.learning_copy_report), why[1].label)
        assertEquals(listOf<LearningOp>(LearningOp.Menu(2, "指导")), ops)
    }

    @Test
    fun theUsersOwnWordIsDeleted() = runTest {
        val connection = FakeFcitxConnection(this)
        connection.api.learned = { menu(WordKind.MINE) }
        val lines = CandidateLearningMenu(context, connection, this) { _, _ -> error("not shown") }.lines(0, "指导", paged = true)!!
        assertEquals(placeholder(R.string.learning_delete), lines.first().label)
        assertEquals("learning(OnPage(op=Menu(index=0, text=指导)))", connection.api.calls.single())
    }

    @Test
    fun noMenuWhereTheEngineHasNone() = runTest {
        val connection = FakeFcitxConnection(this)
        connection.api.learned = { LearningResult.Unavailable }
        val menu = CandidateLearningMenu(context, connection, this) { _, _ -> error("not shown") }
        assertNull(menu.lines(0, "a", paged = false))
        assertFalse(menu.forget(0, "a", paged = false))
        connection.api.learned = { LearningResult.Menu("a", WordKind.DICTIONARY, emptyList(), null, BlockScope.EVERYWHERE) }
        assertNull(menu.lines(0, "a", paged = false))
    }

    @Test
    fun anActIsSaidWithTheTokenThatTakesItBack() = runTest {
        val connection = FakeFcitxConnection(this)
        connection.api.learned = { if (it is LearningOp.Menu) menu(WordKind.DICTIONARY) else LearningResult.Done(7) }
        val said = mutableListOf<Pair<String, Long>>()
        val lines = CandidateLearningMenu(context, connection, this) { message, token -> said += message to token }.lines(2, "指导", paged = false)!!
        assertNull(lines[1].pick()!![2].pick())
        testScheduler.advanceUntilIdle()
        assertEquals(placeholder(R.string.learning_done_block) to 7L, said.single())
        assertEquals("learning(Block(index=2, text=指导))", connection.api.calls.last())
    }

    @Test
    fun nothingDoneIsSaidWithNothingToUndo() = runTest {
        val connection = FakeFcitxConnection(this)
        connection.api.learned = { if (it is LearningOp.Menu) menu(WordKind.DICTIONARY) else LearningResult.Unavailable }
        val said = mutableListOf<Pair<String, Long>>()
        val menu = CandidateLearningMenu(context, connection, this) { m, t -> said += m to t }
        menu.lines(2, "指导", paged = false)!![2].pick()
        testScheduler.advanceUntilIdle()
        // on the bar, not a toast: an input method's is dropped without the notification permission
        assertEquals(listOf(placeholder(R.string.learning_not_done) to LearningResult.NO_UNDO), said)
    }

    @Test
    fun shiftDeleteForgetsAsTheLongPressWould() = runTest {
        val connection = FakeFcitxConnection(this)
        connection.api.learned = { if ((it as LearningOp.OnPage).op is LearningOp.Menu) menu(WordKind.MINE) else LearningResult.Done(LearningResult.NO_UNDO) }
        val said = mutableListOf<Long>()
        val menu = CandidateLearningMenu(context, connection, this) { _, token -> said += token }
        assertTrue(menu.forget(0, "指导", paged = true))
        testScheduler.advanceUntilIdle()
        // nothing to take it back with: the chip shows no 撤销
        assertEquals(listOf(LearningResult.NO_UNDO), said)
        assertEquals("learning(OnPage(op=Delete(index=0, text=指导)))", connection.api.calls.last())
    }
}
