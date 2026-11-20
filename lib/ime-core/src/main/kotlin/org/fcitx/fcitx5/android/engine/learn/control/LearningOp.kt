/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker

/** What the user asks of the learning, through one call (`HostApi.learning`) so the API and its fakes stay small. */
sealed class LearningOp {
    /** A long press on candidate [index]; [text] as shown: if the list changed, the op does nothing ([LearningResult.Unavailable]). */
    sealed class OnCandidate : LearningOp() {
        abstract val index: Int
        abstract val text: String

        /** Another [index] or [text] (the engine's text of one the host shows converted: 繁體). */
        abstract fun at(index: Int, text: String = this.text): OnCandidate
    }

    /** [op] with its index counted from the shown page's first candidate (a hardware keyboard's paged list). */
    data class OnPage(val op: OnCandidate) : LearningOp()

    data class Menu(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int, text: String) = copy(index = index, text = text)
    }

    /** 删除 (the user's own words) or 忘掉学到的 (the dictionary's): forgets everything learned of the text. */
    data class Delete(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int, text: String) = copy(index = index, text = text)
    }

    /** 屏蔽, after confirming [LearningResult.Menu.scope]. */
    data class Block(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int, text: String) = copy(index = index, text = text)
    }

    /** 固定首位 under the typed keys, replacing any pin. */
    data class Pin(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int, text: String) = copy(index = index, text = text)
    }

    /** 取消固定, also 从自定义词组中删除: the candidate's kind tells the engine which. */
    data class Unpin(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int, text: String) = copy(index = index, text = text)
    }

    /** 不再联想. */
    data class NoPredict(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int, text: String) = copy(index = index, text = text)
    }

    /** 复制给开发者: the candidates of these keys with tiers and uses, no preceding text. */
    data class Report(override val index: Int, override val text: String) : OnCandidate() {
        override fun at(index: Int, text: String) = copy(index = index, text = text)
    }

    /** The chip's 撤销 of [LearningResult.Done.undo]. */
    data class Undo(val token: Long) : LearningOp()

    /** 「用我的输入习惯」 on or off: sessions are rebuilt. */
    data class UseHabits(val on: Boolean) : LearningOp()

    /** 清掉最近. */
    data class ClearRecent(val span: Span) : LearningOp()

    /** [minutes], or since local midnight for [TODAY]. */
    enum class Span(val minutes: Int) {
        TEN_MINUTES(10),
        HOUR(60),
        TODAY(-1),
    }

    /** 清空学到的; [all] 「全部清空」: back to as installed, added and blocked words too. */
    data class ClearLearned(val all: Boolean) : LearningOp()

    /** The counts the clear confirmation and lexicon page show. */
    data object Overview : LearningOp()

    /** Whether learning is saving to disk now (no counting), asked each time the ⋯ panel opens. */
    data object Saving : LearningOp()

    /** A lexicon page list: pins, predictions off, a table's per-code blocks. */
    data class ListItems(val what: Listed) : LearningOp()

    /** 一起删掉 on the lexicon page: added words deleted, learned ones forgotten, blocked ones shown again; undoable. */
    data class RemoveWords(val words: List<UserWord>) : LearningOp()

    /** 恢复 English correction of [texts], uses kept. */
    data class RestoreCorrection(val texts: List<String>) : LearningOp()

    /** Removes [items] from their [ListItems] list. */
    data class RemoveItems(val items: List<ListedItem>) : LearningOp()

    /** Forgets table [im]'s learned order (调频), keeping pins and blocks. */
    data class ResetTableOrder(val im: String) : LearningOp()

    enum class Listed { PINS, NO_PREDICT, TABLE_BLOCKS }
}

sealed class LearningResult {
    /** Nothing done: no engine yet, the list changed, nothing learned there, or nowhere to keep it (direct boot). */
    data object Unavailable : LearningResult()

    /** [undo] names it for [LearningOp.Undo]; [NO_UNDO] for none. */
    data class Done(val undo: Long = NO_UNDO) : LearningResult()

    /** A long-press menu: [kind] names the delete (删除 or 忘掉学到的), [items] in order, [why] the reason, [scope] where a block reaches. */
    data class Menu(val text: String, val kind: WordKind, val items: List<MenuItem>, val why: Why?, val scope: BlockScope) : LearningResult()

    data class Text(val text: String) : LearningResult()

    /** The disk failed: nothing done. */
    data object Failed : LearningResult()

    /** Learned since [fromMinute] (epoch minutes; [ALL] for everything) is gone. */
    data class Cleared(val fromMinute: Int) : LearningResult()

    data class Overview(
        val mine: Int,
        val added: Int,
        val pinned: Int,
        val blocked: Int,
        val noPredict: Int,
        val habits: Boolean,
        val saving: Boolean = true,
    ) : LearningResult()

    /** [rewrites] false: no rewrite (clear, restore, undo) possible till restart, so a failure says to restart only where that helps. */
    data class Saving(val saving: Boolean, val rewrites: Boolean = true) : LearningResult()

    data class Listed(val items: List<ListedItem>) : LearningResult()

    companion object {
        const val NO_UNDO = 0L
        const val ALL = Int.MIN_VALUE
    }
}

/** What a word is to the user, which names what deleting it does. */
enum class WordKind {
    DICTIONARY,

    MINE,

    ADDED,

    IMPORTED,
}

enum class MenuItem { DELETE, BLOCK, PIN, UNPIN, DELETE_PHRASE, DELETE_COMMON_WORD, NO_PREDICT, WHY }

enum class BlockScope {
    EVERYWHERE,

    /** A table's candidate: blocked under the typed code alone. */
    UNDER_CODE,
}

/** Why a candidate is where it is: its [tier] (null: unknown), [uses] under [code] in the last 30 typing days; for a whole phrase's rest, [whole] and its whole commits. */
data class Why(val tier: HabitRanker.Tier?, val uses: Int = 0, val code: Code? = null, val whole: String = "")

/** A pin ([code], word), a word [blocked] under a table's [code], or a prediction off after [prev]. */
data class ListedItem(val code: Code?, val prev: String, val text: String, val reading: String = "", val blocked: Boolean = false)
