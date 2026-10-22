/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import android.content.ClipData
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu.Reason
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu.Row
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.utils.clipboardManager

/**
 * A long press on a candidate of the engine's input methods (U-82): the menu the engine gives
 * ([LearningMenu]), worded here, the app's own and in its language rather than the addon's.
 * Blocking asks first in the same popup, which says where it reaches; 为什么排在这 shows the
 * reason in it, with 复制给开发者. Each act is said by [done] (the undo chip: its message and the
 * token that takes it back), to be taken back.
 */
class CandidateLearningMenu(
    private val context: Context,
    private val fcitx: FcitxConnection,
    private val scope: CoroutineScope,
    private val done: (message: String, token: Long) -> Unit,
) {
    /** A line of the popup, shown as [label]; [pick] gives the lines to show in its place, or null to close it. Not [enabled]: a line to read. */
    class Line(val label: CharSequence, val enabled: Boolean = true, val pick: () -> List<Line>? = { null })

    /**
     * The lines for the candidate [text] at [index] (counted from the page shown if [paged]), or
     * null where the engine has no menu for it: not one of its input methods, or nothing to do
     * with it (a field that learns nothing).
     */
    suspend fun lines(index: Int, text: String, paged: Boolean): List<Line>? {
        val menu = fcitx.runOnReady { learning(LearningMenu.onCandidate(LearningOp.Menu(index, text), paged)) }
        if (menu !is LearningResult.Menu || menu.items.isEmpty()) return null
        return LearningMenu.rows(menu).map { row ->
            val op = LearningMenu.op(row, index, text, paged)
            when {
                op == null -> Line(context.getString(R.string.learning_why)) { why(menu.why, index, text, paged) }
                // the confirmation never where the finger that picked the row still is (C18: a slow
                // double tap blocks nothing): the reach, a cancel, then it (review-E-ui S1)
                LearningMenu.confirms(row) -> Line(label(row)) {
                    listOf(
                        Line(context.getString(scope(menu.scope), text), enabled = false),
                        Line(context.getString(android.R.string.cancel)),
                        Line(context.getString(R.string.learning_block_confirm)) { act(op, row, text) },
                    )
                }
                else -> Line(label(row)) { act(op, row, text) }
            }
        }
    }

    /** Shift+Delete on the candidate (U-82): its 删除 or 忘掉学到的, as the menu's. Whether it had one. */
    suspend fun forget(index: Int, text: String, paged: Boolean): Boolean {
        val menu = fcitx.runOnReady { learning(LearningMenu.onCandidate(LearningOp.Menu(index, text), paged)) }
        if (menu !is LearningResult.Menu) return false
        val row = LearningMenu.rows(menu).firstOrNull { it == Row.DELETE || it == Row.FORGET } ?: return false
        val op = LearningMenu.op(row, index, text, paged) ?: return false
        act(op, row, text)
        return true
    }

    private fun scope(scope: BlockScope) = when (scope) {
        BlockScope.EVERYWHERE -> R.string.learning_block_everywhere
        BlockScope.UNDER_CODE -> R.string.learning_block_under_code
    }

    private fun label(row: Row) = context.getString(
        when (row) {
            Row.DELETE -> R.string.learning_delete
            Row.FORGET -> R.string.learning_forget
            Row.BLOCK -> R.string.learning_block
            Row.PIN -> R.string.learning_pin
            Row.UNPIN -> R.string.learning_unpin
            Row.DELETE_PHRASE -> R.string.learning_delete_phrase
            Row.NO_PREDICT -> R.string.learning_no_predict
            Row.WHY -> R.string.learning_why
        }
    )

    private fun said(row: Row, text: String) = context.getString(
        when (row) {
            Row.DELETE -> R.string.learning_done_delete
            Row.FORGET -> R.string.learning_done_forget
            Row.BLOCK -> R.string.learning_done_block
            Row.PIN -> R.string.learning_done_pin
            Row.UNPIN -> R.string.learning_done_unpin
            Row.DELETE_PHRASE -> R.string.learning_done_delete_phrase
            Row.NO_PREDICT -> R.string.learning_done_no_predict
            // listed so a new row must say its own: 为什么 explains, it is never acted on
            Row.WHY -> error("WHY is not an act")
        },
        text
    )

    private fun act(op: LearningOp, row: Row, text: String): List<Line>? {
        scope.launch {
            when (val result = fcitx.runOnReady { learning(op) }) {
                is LearningResult.Done -> done(said(row, text), result.undo)
                // the list changed under the menu: nothing done, and said so (review-E-ui N7)
                else -> done(context.getString(R.string.learning_not_done), LearningResult.NO_UNDO)
            }
        }
        return null
    }

    private fun why(why: Why?, index: Int, text: String, paged: Boolean): List<Line> = listOf(
        Line(reason(why), enabled = false),
        Line(context.getString(R.string.learning_copy_report)) {
            scope.launch {
                val report = fcitx.runOnReady { learning(LearningMenu.onCandidate(LearningOp.Report(index, text), paged)) }
                if (report is LearningResult.Text) {
                    context.clipboardManager.setPrimaryClip(ClipData.newPlainText(null, report.text))
                    done(context.getString(R.string.learning_report_copied), LearningResult.NO_UNDO)
                }
            }
            null
        },
    )

    private fun reason(why: Why?): String = when (LearningMenu.reason(why)) {
        Reason.PINNED -> context.getString(R.string.learning_why_pinned)
        Reason.FIRST_HERE -> context.getString(R.string.learning_why_first_here)
        Reason.ACROSS -> context.getString(R.string.learning_why_across)
        Reason.USED -> (why?.uses ?: 0).let { context.resources.getQuantityString(R.plurals.learning_why_used, it, it) }
        Reason.MINE -> context.getString(R.string.learning_why_mine)
        Reason.IMPORTED -> context.getString(R.string.learning_why_imported)
        Reason.DICTIONARY -> context.getString(R.string.learning_why_dictionary)
        Reason.PREDICTION -> context.getString(R.string.learning_why_prediction)
        Reason.UNKNOWN -> context.getString(R.string.learning_why_unknown)
    }
}
