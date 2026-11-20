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
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu.Reason
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu.Row
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.utils.clipboardManager

/** Worded here, in the app's language rather than the addon's. Each act is said by [done], to be taken back. */
class CandidateLearningMenu(
    private val context: Context,
    private val fcitx: HostConnection,
    private val scope: CoroutineScope,
    private val done: (message: String, token: Long) -> Unit,
) {
    /** [pick] null closes it. Not [enabled]: a line to read. */
    class Line(val label: CharSequence, val enabled: Boolean = true, val pick: () -> List<Line>? = { null })

    /** Null where the engine has no menu: not one of its input methods, or a field that learns nothing. */
    suspend fun lines(index: Int, text: String, paged: Boolean): List<Line>? =
        linesOf(fcitx.runOnReady { learning(menuOf(index, text, paged)) }, index, text, paged)

    /** For a caller asking it with more in one round trip. */
    fun menuOf(index: Int, text: String, paged: Boolean): LearningOp = LearningMenu.onCandidate(LearningOp.Menu(index, text), paged)

    fun linesOf(menu: LearningResult, index: Int, text: String, paged: Boolean): List<Line>? {
        if (menu !is LearningResult.Menu || menu.items.isEmpty()) return null
        return LearningMenu.rows(menu).map { row ->
            // the engine's text (國家 shown, 国家 its): what the act names, checked by the engine
            val op = LearningMenu.op(row, index, menu.text, paged)
            when {
                op == null -> Line(context.getString(R.string.learning_why)) { why(menu.why, index, text, paged) }
                // never where the finger that picked the row still is: a slow double tap blocked
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

    /** As the menu's. Whether it had one. */
    suspend fun forget(index: Int, text: String, paged: Boolean): Boolean {
        val menu = fcitx.runOnReady { learning(LearningMenu.onCandidate(LearningOp.Menu(index, text), paged)) }
        if (menu !is LearningResult.Menu) return false
        val row = LearningMenu.rows(menu).firstOrNull { it == Row.DELETE || it == Row.FORGET } ?: return false
        val op = LearningMenu.op(row, index, menu.text, paged) ?: return false
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
            Row.DELETE_COMMON_WORD -> R.string.learning_delete_common_word
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
            Row.DELETE_COMMON_WORD -> R.string.learning_done_delete_common_word
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
                // the list changed under the menu: nothing done, and said so
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
        Reason.ALONE -> context.getString(R.string.learning_why_alone)
        Reason.USED -> (why?.uses ?: 0).let { context.resources.getQuantityString(R.plurals.learning_why_used, it, it) }
        Reason.FLOORED -> context.getString(R.string.learning_why_floored)
        Reason.LIFTED -> context.getString(R.string.learning_why_lifted)
        Reason.MINE -> context.getString(R.string.learning_why_mine)
        Reason.IMPORTED -> context.getString(R.string.learning_why_imported)
        Reason.DICTIONARY -> context.getString(R.string.learning_why_dictionary)
        Reason.PREDICTION -> context.getString(R.string.learning_why_prediction)
        Reason.PHRASE -> (why?.uses ?: 0).let { context.resources.getQuantityString(R.plurals.learning_why_phrase, it, it) }
        Reason.UNKNOWN -> context.getString(R.string.learning_why_unknown)
    }
}
