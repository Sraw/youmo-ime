/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipboardManager
import android.view.View
import android.widget.ListPopupWindow
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UAT cases of a candidate's long-press menu (uat/06): the menu the engine gives, worded and acted
 * on by the keyboard, the chip that says it with 撤销. Which rows the engine offers for a word, and
 * what blocking does to its candidates, are the engine's (its learning tests), not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CandidateMenuUatTest {

    private val h = KeyboardHarness()

    @After
    fun close() = h.close()

    private val ops = mutableListOf<LearningOp>()

    private fun engine(menu: LearningResult.Menu, report: String = "") {
        h.api.learned = { op ->
            ops += op
            val inner = (op as? LearningOp.OnPage)?.op ?: op
            when (inner) {
                is LearningOp.Menu -> menu
                is LearningOp.Report -> LearningResult.Text(report)
                is LearningOp.Undo -> LearningResult.Done()
                else -> LearningResult.Done(TOKEN)
            }
        }
    }

    private fun candidates(vararg words: String) {
        h.api.candidates = Array(words.size) { CandidateWord("", words[it], "") }
        h.emit(FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(words.size, h.api.candidates)))
    }

    private fun s(id: Int, vararg args: Any) = h.service.getString(id, *args)

    private val menu: ListPopupWindow?
        get() = h.inputView.candidateActionMenu

    /** The open menu's lines, its title (the word) first. */
    private fun lines(): List<String> {
        val m = menu ?: error("no menu")
        return (0 until m.listView!!.adapter.count).map { m.listView!!.adapter.getItem(it).toString() }
    }

    private fun pick(label: String) {
        val at = lines().indexOf(label)
        check(at > 0) { "no $label in ${lines()}" }
        menu!!.performItemClick(at)
        h.idle()
    }

    private fun longPress(word: String) {
        val text = KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text.toString() == word }
        val holder = generateSequence(text as View) { it.parent as? View }.first { it.isLongClickable }
        holder.performLongClick()
        h.idle()
    }

    // the chip saying what was done: a popup window over the keyboard
    private fun chip(): List<String>? = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        ?.takeIf { it.isShowing }
        ?.let { w -> KeyboardHarness.all(w.contentView).filterIsInstance<TextView>().map { it.text.toString() }.toList() }

    private fun undo() {
        val w = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow!!
        val button = KeyboardHarness.all(w.contentView).filterIsInstance<TextView>().first { it.text.toString() == s(R.string.learning_undo) }
        check(button.performClick())
        h.idle()
    }

    private val dictionary = LearningResult.Menu("是", WordKind.DICTIONARY, listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), null, BlockScope.EVERYWHERE)

    @Test
    @Uat("PY-29")
    fun blockingAWordSaysWhereAndAsksFirst() {
        engine(dictionary)
        h.start()
        candidates("是", "时", "事")
        longPress("是")
        assertEquals(listOf("是", s(R.string.learning_block), s(R.string.learning_pin), s(R.string.learning_why)), lines())
        pick(s(R.string.learning_block))
        // the reach first: typing, handwriting, voice and predictions alike
        assertEquals(listOf("是", s(R.string.learning_block_everywhere, "是"), s(android.R.string.cancel), s(R.string.learning_block_confirm)), lines())
        pick(s(R.string.learning_block_confirm))
        assertTrue(ops.toString(), ops.any { "Block(index=0, text=是)" in it.toString() })
    }

    @Test
    @Uat("PY-68")
    fun aBlockIsTakenBackOnTheSpot() {
        engine(dictionary)
        h.start()
        candidates("是", "时", "事")
        longPress("是")
        val blockAt = lines().indexOf(s(R.string.learning_block))
        pick(s(R.string.learning_block))
        // 确定屏蔽 not where the finger that picked 屏蔽 is: 取消 between
        assertNotEquals(blockAt, lines().indexOf(s(R.string.learning_block_confirm)))
        assertEquals(lines().indexOf(s(android.R.string.cancel)) + 1, lines().indexOf(s(R.string.learning_block_confirm)))
        pick(s(R.string.learning_block_confirm))
        val said = chip()
        assertTrue(said.toString(), said!!.contains(s(R.string.learning_done_block, "是")) && said.contains(s(R.string.learning_undo)))
        h.capture("CandidateMenuUatTest/blocked")
        undo()
        assertTrue(ops.toString(), ops.any { it == LearningOp.Undo(TOKEN) })
        // again, not taken back: typing on puts the chip away at once
        longPress("是")
        pick(s(R.string.learning_block))
        pick(s(R.string.learning_block_confirm))
        val w = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow!!
        assertTrue(w.isShowing)
        h.tap(h.key("a"))
        // the engine tells of the a it was sent: typing
        val typed = org.fcitx.fcitx5.android.core.FormattedText(arrayOf("a"), intArrayOf(0), 1)
        val empty = org.fcitx.fcitx5.android.core.FormattedText.Empty
        h.emit(FcitxEvent.InputPanelEvent(FcitxEvent.InputPanelEvent.Data(typed, empty, empty, emptyArray())))
        h.advance(500)
        assertFalse(w.isShowing)
    }

    @Test
    @Uat("PY-69")
    fun whyItIsThereAndTheReportCopied() {
        engine(
            LearningResult.Menu("点了", WordKind.DICTIONARY, listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), Why(org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier.FIRST_HERE), BlockScope.EVERYWHERE),
            report = "dl: 点了 2",
        )
        h.start()
        candidates("点了", "带来")
        longPress("点了")
        pick(s(R.string.learning_why))
        assertEquals(listOf("点了", s(R.string.learning_why_first_here), s(R.string.learning_copy_report)), lines())
        menu!!.dismiss()
        h.idle()
        engine(LearningResult.Menu("带来", WordKind.DICTIONARY, listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), Why(org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier.DICTIONARY), BlockScope.EVERYWHERE), report = "dl: 点了 2")
        longPress("带来")
        pick(s(R.string.learning_why))
        assertEquals(s(R.string.learning_why_dictionary), lines()[1])
        pick(s(R.string.learning_copy_report))
        // the engine's report, as it gave it, on the clipboard
        assertEquals("dl: 点了 2", h.service.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    private companion object {
        const val TOKEN = 5L
    }
}
