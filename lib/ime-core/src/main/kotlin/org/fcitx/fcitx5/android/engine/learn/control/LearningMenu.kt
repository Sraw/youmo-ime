/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Kinds

/** A long-press menu as the keyboard shows it: rows from [LearningResult.Menu], their ops, and 为什么排在这's reason. */
object LearningMenu {
    /** [DELETE] and [FORGET] are one item, named by the word's kind. */
    enum class Row {
        /** 删除: the user's own word, added or imported. */
        DELETE,

        /** 忘掉学到的: the dictionary's. */
        FORGET,
        BLOCK,
        PIN,
        UNPIN,

        /** 从自定义词组中删除: a custom phrase or built-in date. */
        DELETE_PHRASE,

        /** 从常用词中删除: a common word's replacement. */
        DELETE_COMMON_WORD,
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
            MenuItem.DELETE_COMMON_WORD -> Row.DELETE_COMMON_WORD
            MenuItem.NO_PREDICT -> Row.NO_PREDICT
            MenuItem.WHY -> Row.WHY
        }
    }

    /** The op [row] sends for candidate [text] at [index] (from the shown page if [paged]); null for [Row.WHY], shown from the menu itself. */
    fun op(row: Row, index: Int, text: String, paged: Boolean): LearningOp? {
        val op = when (row) {
            Row.DELETE, Row.FORGET -> LearningOp.Delete(index, text)
            Row.BLOCK -> LearningOp.Block(index, text)
            Row.PIN -> LearningOp.Pin(index, text)
            Row.UNPIN, Row.DELETE_PHRASE, Row.DELETE_COMMON_WORD -> LearningOp.Unpin(index, text)
            Row.NO_PREDICT -> LearningOp.NoPredict(index, text)
            Row.WHY -> return null
        }
        return onCandidate(op, paged)
    }

    fun onCandidate(op: LearningOp.OnCandidate, paged: Boolean): LearningOp = if (paged) LearningOp.OnPage(op) else op

    /** A block reaches everywhere, so it asks first. */
    fun confirms(row: Row) = row == Row.BLOCK

    enum class Reason {
        PINNED,
        FIRST_HERE,
        ACROSS,

        /** 双拼: a user-typed word read by lone keys, put up front. */
        ALONE,
        USED,

        /** No lower than second: picked over the first under its 简拼. */
        FLOORED,

        /** Up to third: used typed in full on this keyboard. */
        LIFTED,
        MINE,
        IMPORTED,
        DICTIONARY,

        PREDICTION,

        /** The rest of a whole phrase, committed whole [Why.uses] times. */
        PHRASE,

        UNKNOWN,
    }

    fun reason(why: Why?): Reason {
        val reason = why?.tier?.reason()
        return when {
            why == null -> Reason.UNKNOWN
            why.whole.isNotEmpty() -> Reason.PHRASE
            // a 联想's tier only tells what the user did after that word: the predictor's order is no dictionary's
            why.code?.kind == Kinds.PREDICTION -> reason?.takeIf { it in DONE_AFTER } ?: Reason.PREDICTION
            else -> reason ?: Reason.UNKNOWN
        }
    }

    private fun HabitRanker.Tier.reason() = when (this) {
        HabitRanker.Tier.PINNED -> Reason.PINNED
        HabitRanker.Tier.FIRST_HERE -> Reason.FIRST_HERE
        HabitRanker.Tier.ACROSS -> Reason.ACROSS
        HabitRanker.Tier.ALONE -> Reason.ALONE
        HabitRanker.Tier.USED -> Reason.USED
        HabitRanker.Tier.FLOORED -> Reason.FLOORED
        HabitRanker.Tier.LIFTED -> Reason.LIFTED
        HabitRanker.Tier.MINE -> Reason.MINE
        HabitRanker.Tier.IMPORTED -> Reason.IMPORTED
        HabitRanker.Tier.DICTIONARY -> Reason.DICTIONARY
    }

    private val DONE_AFTER = setOf(Reason.PINNED, Reason.FIRST_HERE, Reason.USED)
}
