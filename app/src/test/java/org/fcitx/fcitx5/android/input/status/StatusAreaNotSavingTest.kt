/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⋯'s panel says, on top, that what is learned is not being saved while the engine says so
 * (R25 decode-1: a write refused and the record not opened again), and nothing while it saves.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class StatusAreaNotSavingTest {

    @get:Rule
    val prefs = PrefsRule()

    // the opened panel: whether it shows the line, and whether its entries are there (set with the line, once the engine answered)
    private class Panel(val line: Boolean, val entries: Boolean, val asked: List<String>)

    private fun opened(saving: () -> LearningResult): Panel = KeyboardHarness().use { h ->
        h.api.learned = { if (it == LearningOp.Saving) saving() else LearningResult.Done() }
        h.start()
        // the toolbar stays expanded from the last time
        runCatching { h.described(h.service.getString(R.string.expand_toolbar)) }.getOrNull()?.let {
            h.tap(it)
            h.after(500)
        }
        h.tap(h.described(h.service.getString(R.string.status_area)))
        h.after(100)
        val line = h.service.getString(R.string.learning_not_saving)
        val entry = h.service.getString(R.string.floating_keyboard)
        fun shows(text: String) = KeyboardHarness.all(h.inputView).any { h.shown(it) && it is TextView && it.text.toString() == text }
        Panel(
            line = shows(line),
            entries = shows(entry),
            asked = h.api.calls.filter { it.startsWith("learning(") },
        )
    }

    private fun shownWith(saving: Boolean): Boolean = opened { LearningResult.Saving(saving) }.line

    @Test
    @Uat("WD-46")
    fun thePanelSaysLearningIsNotSavedOnlyWhileItIsNot() {
        assertEquals(true, shownWith(saving = false))
        assertEquals(false, shownWith(saving = true))
    }

    // R25r2 F7897: the panel waited for a whole overview (the record's state, every list of the user's words counted)
    // for the one flag: it asks only whether learning saves
    @Test
    @Uat("WD-46")
    fun thePanelAsksOnlyWhetherLearningSavesNotTheOverview() {
        val asked = opened { LearningResult.Saving(true) }.asked
        assertTrue(asked.toString(), "learning(${LearningOp.Saving})" in asked)
        assertTrue(asked.toString(), "learning(${LearningOp.Overview})" !in asked)
    }

    // R25r2 F7885: the engine failing to answer (or answering something else: not there) leaves the notice off and
    // the panel as it is, not empty
    @Test
    fun theEngineNotAnsweringLeavesTheNoticeOffAndThePanelShown() {
        class Row(val why: String, val answer: () -> LearningResult)
        assertRows(
            listOf(
                Row("the engine throws (its data not read)", { error("no engine") }),
                Row("the engine is not there to say", { LearningResult.Unavailable }),
            ),
            { it.why },
        ) { row ->
            val panel = opened(row.answer)
            assertEquals(false, panel.line)
            assertTrue("the panel's entries", panel.entries)
        }
    }
}
