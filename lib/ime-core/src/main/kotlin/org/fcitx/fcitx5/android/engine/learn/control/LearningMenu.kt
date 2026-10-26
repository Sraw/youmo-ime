/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Kinds

/**
 * A long press's menu as the keyboard shows it (U-82, U-81): its rows from the engine's
 * [LearningResult.Menu], what each asks of it, and the reason 为什么排在这 gives. The app only
 * puts words to them.
 */
object LearningMenu {
    /** A row of the menu, in its order; [DELETE] and [FORGET] are the one item named by what the word is. */
    enum class Row {
        /** 删除: the user's own word, added or imported. */
        DELETE,

        /** 忘掉学到的: the dictionary's. */
        FORGET,
        BLOCK,
        PIN,
        UNPIN,

        /** 从自定义词组中删除: a custom phrase, a built-in date too. */
        DELETE_PHRASE,
        NO_PREDICT,
        WHY,
    }

    fun rows(menu: LearningResult.Menu): List<Row> = menu.items.map { item ->
        when (item) {
            MenuItem.DELETE -> if (menu.kind == WordKind.DICTIONARY) Row.FORGET else Row.DELETE
            MenuItem.BLOCK -> Row.BLOCK
            MenuItem.PIN -> Row.PIN
            MenuItem.UNPIN -> Row.UNPIN
            MenuItem.DELETE_PHRASE -> Row.DELETE_PHRASE
            MenuItem.NO_PREDICT -> Row.NO_PREDICT
            MenuItem.WHY -> Row.WHY
        }
    }

    /**
     * What [row] asks of the engine for the candidate [text] at [index], counted from the page
     * shown if [paged]: null for [Row.WHY], which shows what the menu already has.
     */
    fun op(row: Row, index: Int, text: String, paged: Boolean): LearningOp? {
        val op = when (row) {
            Row.DELETE, Row.FORGET -> LearningOp.Delete(index, text)
            Row.BLOCK -> LearningOp.Block(index, text)
            Row.PIN -> LearningOp.Pin(index, text)
            Row.UNPIN, Row.DELETE_PHRASE -> LearningOp.Unpin(index, text)
            Row.NO_PREDICT -> LearningOp.NoPredict(index, text)
            Row.WHY -> return null
        }
        return onCandidate(op, paged)
    }

    /** [op] as the list it was asked on counts: from the page shown if [paged]. */
    fun onCandidate(op: LearningOp.OnCandidate, paged: Boolean): LearningOp = if (paged) LearningOp.OnPage(op) else op

    /** Whether [row] asks first: a block reaches everywhere (U-82 C12), so the menu says where and asks. */
    fun confirms(row: Row) = row == Row.BLOCK

    /** The reasons 为什么排在这 gives (U-81), one for each ranking tier and for a prediction. */
    enum class Reason {
        PINNED,
        FIRST_HERE,
        ACROSS,
        USED,

        /** No lower than second: picked over the first under its 简拼 (U-20, D32 B). */
        FLOORED,

        /** Up to third: used typed in full on this keyboard (U-20, D32 B). */
        LIFTED,
        MINE,
        IMPORTED,
        DICTIONARY,

        /** A prediction: it follows the word before. */
        PREDICTION,

        /** The ranking did not say: shown as not known. */
        UNKNOWN,
    }

    fun reason(why: Why?): Reason {
        val reason = why?.tier?.reason()
        return when {
            why == null -> Reason.UNKNOWN
            // a 联想 says so by its code, its tier telling only what the user did after that word: the
            // predictor's order is no dictionary's, nor keys typed (verify-int-step1a V1)
            why.code?.kind == Kinds.PREDICTION -> reason?.takeIf { it in DONE_AFTER } ?: Reason.PREDICTION
            else -> reason ?: Reason.UNKNOWN
        }
    }

    private fun HabitRanker.Tier.reason() = when (this) {
        HabitRanker.Tier.PINNED -> Reason.PINNED
        HabitRanker.Tier.FIRST_HERE -> Reason.FIRST_HERE
        HabitRanker.Tier.ACROSS -> Reason.ACROSS
        HabitRanker.Tier.USED -> Reason.USED
        HabitRanker.Tier.FLOORED -> Reason.FLOORED
        HabitRanker.Tier.LIFTED -> Reason.LIFTED
        HabitRanker.Tier.MINE -> Reason.MINE
        HabitRanker.Tier.IMPORTED -> Reason.IMPORTED
        HabitRanker.Tier.DICTIONARY -> Reason.DICTIONARY
    }

    // a prediction's tiers that tell what the user did after the word before
    private val DONE_AFTER = setOf(Reason.PINNED, Reason.FIRST_HERE, Reason.USED)
}
