/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker

/**
 * What the user asks of what was learned (PLAN §2.8, impl/streams/E-design.md): one call for them
 * all, `FcitxAPI.learning`, so the API and its fakes stay small. Answered by a [LearningResult].
 */
sealed class LearningOp {
    /**
     * A long press on the candidate at [index] of all those of the input method in use. [text] is
     * the candidate as the menu showed it: a list changed since names another one at [index], and
     * the op then does nothing ([LearningResult.Unavailable]).
     */
    sealed class OnCandidate : LearningOp() {
        abstract val index: Int
        abstract val text: String

        /** The same at another [index]. */
        abstract fun at(index: Int): OnCandidate
    }

    /**
     * [op] with its index counted from the first candidate of the page shown, as the floating list
     * of a hardware keyboard has it (paged), not from the first of all.
     */
    data class OnPage(val op: OnCandidate) : LearningOp()

    /** What the long press offers ([LearningResult.Menu]). */
    data class Menu(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int) = copy(index = index)
    }

    /** 删除 (the user's own words, U-82 D13) or 忘掉学到的 (the dictionary's): one act, everything learned of the text forgotten (U-84). */
    data class Delete(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int) = copy(index = index)
    }

    /** 屏蔽, after the confirmation the menu's [LearningResult.Menu.scope] words. */
    data class Block(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int) = copy(index = index)
    }

    /** 固定首位 under the keys typed (U-83), in place of what was pinned there. */
    data class Pin(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int) = copy(index = index)
    }

    /**
     * 取消固定; also 从自定义词组中删除 ([LearningMenu.Row.DELETE_PHRASE]): what the candidate is
     * (a pinned word, a custom phrase or a built-in date) tells the engine which is meant.
     */
    data class Unpin(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int) = copy(index = index)
    }

    /** 不再联想 (U-72): a prediction no longer offered after the word before it. */
    data class NoPredict(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int) = copy(index = index)
    }

    /** 复制给开发者 (U-81, D26): the candidates of these keys, their tiers and uses, none of the text before ([LearningResult.Text]). */
    data class Report(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int) = copy(index = index)
    }

    /** The act [token] named ([LearningResult.Done.undo]) taken back, as though never done: the chip's 撤销. */
    data class Undo(val token: Long) : LearningOp()

    /** U-93 「用我的输入习惯」 on or off: the sessions made anew. */
    data class UseHabits(val on: Boolean) : LearningOp()

    /** U-88 清掉最近: what was typed in the [span] up to now, as though it never was. */
    data class ClearRecent(val span: Span) : LearningOp()

    /** How far back [ClearRecent] goes: [minutes], or since local midnight for [TODAY]. */
    enum class Span(val minutes: Int) {
        TEN_MINUTES(10),
        HOUR(60),
        TODAY(-1),
    }

    /** U-86 清空学到的; [all] 「全部清空」: as installed, the words added and blocked too. */
    data class ClearLearned(val all: Boolean) : LearningOp()

    /** How much there is to keep or lose: the counts U-86's confirmation and the lexicon page show ([LearningResult.Overview]). */
    data object Overview : LearningOp()

    /** A list of the lexicon page ([LearningResult.Listed]): the pins, the predictions turned off, a table's blocks under a code. */
    data class ListItems(val what: Listed) : LearningOp()

    /**
     * [words] of the lexicon page taken off their lists together (U-80: 一起删掉), as the page's
     * delete did: added words deleted, learned ones forgotten, blocked ones shown again; undoable.
     */
    data class RemoveWords(val words: List<UserWord>) : LearningOp()

    /** The English words [texts] corrected again (U-31 恢复), their uses kept: [org.fcitx.fcitx5.android.engine.host.Engines.restoreEnglishCorrection]. */
    data class RestoreCorrection(val texts: List<String>) : LearningOp()

    /** [items] of a [ListItems] taken off it: unpinned, predicted again, shown again under their code. */
    data class RemoveItems(val items: List<ListedItem>) : LearningOp()

    /** U-28: the order table [im] learned (调频) forgotten, its pins and blocks kept; in its settings. */
    data class ResetTableOrder(val im: String) : LearningOp()

    enum class Listed { PINS, NO_PREDICT, TABLE_BLOCKS }
}

/** What a [LearningOp] did, or tells. */
sealed class LearningResult {
    /** Nothing done: no engine yet, no such candidate (the list changed), nothing learned there, or nowhere to keep it (direct boot). */
    data object Unavailable : LearningResult()

    /** Done; [undo] names it for [LearningOp.Undo], [NO_UNDO] for none. */
    data class Done(val undo: Long = NO_UNDO) : LearningResult()

    /**
     * A long press's menu (U-82): the candidate's [text], what [kind] of word it is (which names
     * the delete: 删除 or 忘掉学到的), the [items] offered in the menu's order, [why] it is where it
     * is (U-81), and where a block reaches ([scope], for the confirmation).
     */
    data class Menu(val text: String, val kind: WordKind, val items: List<MenuItem>, val why: Why?, val scope: BlockScope) : LearningResult()

    data class Text(val text: String) : LearningResult()

    /** Asked, and it could have been, but the disk failed: nothing done (a clear that did not clear says so). */
    data object Failed : LearningResult()

    /** What was learned since [fromMinute] (epoch minutes; [ALL] for everything) is gone. */
    data class Cleared(val fromMinute: Int) : LearningResult()

    /** How many of each the user has: their own words, the words added by hand, pinned, blocked, predictions turned off; and whether habits are used (U-93). */
    data class Overview(val mine: Int, val added: Int, val pinned: Int, val blocked: Int, val noPredict: Int, val habits: Boolean) : LearningResult()

    data class Listed(val items: List<ListedItem>) : LearningResult()

    companion object {
        const val NO_UNDO = 0L
        const val ALL = Int.MIN_VALUE
    }
}

/** What a word is to the user, which names what deleting it does (U-82). */
enum class WordKind {
    /** The dictionary's: 忘掉学到的. */
    DICTIONARY,

    /** The user's own, formed as they typed: 删除. */
    MINE,

    /** Added by hand: 删除. */
    ADDED,

    /** Only an import has it: 删除. */
    IMPORTED,
}

/** A long press's items, in the menu's order. */
enum class MenuItem { DELETE, BLOCK, PIN, UNPIN, DELETE_PHRASE, NO_PREDICT, WHY }

/** Where a block reaches, for its confirmation (U-82, C12). */
enum class BlockScope {
    /** Typing, handwriting, voice, predictions: everywhere. */
    EVERYWHERE,

    /** A table's candidate: under the code typed alone, everywhere else as it was (U-28). */
    UNDER_CODE,
}

/**
 * Why a candidate is where it is (U-81): the [tier] ranking put it in ([HabitRanker.Tier]; null
 * where the session does not say: shown as not known), its [uses] under [code] in the last 30
 * typing days, and the [code] it was ranked under.
 */
data class Why(val tier: HabitRanker.Tier?, val uses: Int = 0, val code: Code? = null)

/**
 * A row of a [LearningOp.ListItems]: a pin's [code] and word, a word [blocked] under a table's
 * [code] (U-28), or a prediction turned off after [prev].
 */
data class ListedItem(val code: Code?, val prev: String, val text: String, val reading: String = "", val blocked: Boolean = false)
