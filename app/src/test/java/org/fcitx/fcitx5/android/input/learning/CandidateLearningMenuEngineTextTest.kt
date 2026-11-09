/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.core.FakeHostConnection
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [CandidateLearningMenu] where the candidate shown is converted (國家 shown, the engine's word 国家):
 * every act names the engine's text, not the one shown, so the engine finds the word it has.
 */
@RunWith(RobolectricTestRunner::class)
class CandidateLearningMenuEngineTextTest {
    private val context = PlaceholderStrings(RuntimeEnvironment.getApplication())

    private fun menu(kind: WordKind) = LearningResult.Menu(
        "国家", kind, listOf(MenuItem.DELETE, MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), Why(HabitRanker.Tier.USED, 3), BlockScope.EVERYWHERE
    )

    @Test
    fun aBlockOfAConvertedCandidateNamesTheEnginesText() = runTest {
        val connection = FakeHostConnection(this)
        connection.api.learned = { if (it is LearningOp.Menu) menu(WordKind.DICTIONARY) else LearningResult.Done(7) }
        val lines = CandidateLearningMenu(context, connection, this) { _, _ -> }.lines(2, "國家", paged = false)!!
        lines[1].pick()!![2].pick()
        testScheduler.advanceUntilIdle()
        assertEquals("learning(Menu(index=2, text=國家))", connection.api.calls.first())
        assertEquals("learning(Block(index=2, text=国家))", connection.api.calls.last())
    }

    @Test
    fun shiftDeleteOfAConvertedCandidateNamesTheEnginesText() = runTest {
        val connection = FakeHostConnection(this)
        connection.api.learned = { if ((it as LearningOp.OnPage).op is LearningOp.Menu) menu(WordKind.MINE) else LearningResult.Done(LearningResult.NO_UNDO) }
        val menu = CandidateLearningMenu(context, connection, this) { _, _ -> }
        assertTrue(menu.forget(0, "國家", paged = true))
        testScheduler.advanceUntilIdle()
        assertEquals("learning(OnPage(op=Delete(index=0, text=国家)))", connection.api.calls.last())
    }
}
