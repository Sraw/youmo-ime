/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.learn.rankView
import org.fcitx.fcitx5.android.engine.learn.control.Why

/**
 * A table's candidates reordered by the user's habits under the keys typed alone, no word before
 * (U-28, PLAN §2.7): with 调频 (orderByUse) all of them as U-02 has it, a word from outside the
 * table not used here after the table's own; without, the table's own where they are and those
 * from outside after them, among themselves 「首选以外」. The pin is put first after this.
 */
internal object TableHabitOrder {

    /**
     * [original] (positions into what was found, the table's order, and with 调频 the models'
     * weighing of its 重码) under [here], with what each was ranked by its text; null where
     * nothing is learned. Only the first [reach] are the 重码 it may move: none where the code is
     * kept in order (一二级简码, 原 TBL-13), never the longer codes after them. [own] tells a position
     * of the table's own entry from a word from outside it, [text] its text.
     */
    fun order(
        h: Habits,
        orderByUse: Boolean,
        here: Code,
        original: IntArray,
        reach: Int,
        own: (Int) -> Boolean,
        text: (Int) -> String,
    ): Pair<IntArray, Map<String, HabitRanker.Ranked<Int>>>? {
        if (!h.learns) return null
        fun item(at: Int) = text(at).let { t -> HabitRanker.Item(at, Words.of(t), single = t.codePointCount(0, t.length) == 1) }
        val ours = (0 until reach).map { original[it] }.filter(own)
        val others = HabitRanker.rest((0 until reach).map { original[it] }.filterNot(own).map(::item), here, view(h, mine = true))
        val all = if (orderByUse) {
            // the user's own words from pinyin are not first here by being theirs: by use here alone
            HabitRanker.rank(ours.map(::item) + others.map { it.item }, here, "", view(h, mine = false))
        } else {
            ours.map { HabitRanker.Ranked(item(it), HabitRanker.Tier.DICTIONARY, h.uses(here, Words.of(text(it)))) } + others
        }
        val placed = original.copyOf()
        if (orderByUse) {
            all.forEachIndexed { k, r -> placed[k] = r.value }
        } else {
            // the table's own where they were, those from outside in the places left
            val outside = others.iterator()
            for (k in 0 until reach) if (!own(original[k])) placed[k] = outside.next().value
        }
        return placed to all.associateBy { it.item.words.text }
    }

    /** What 为什么排在这 says of [text] under [code], [r] how it was ranked: the pin first, else its tier; nothing unranked. */
    fun why(h: Habits, code: Code, text: String, r: HabitRanker.Ranked<Int>?): Why? = when {
        h.pinned(code)?.text == text -> Why(HabitRanker.Tier.PINNED, r?.uses ?: 0, code)
        r != null -> Why(r.tier, r.uses, code)
        else -> null
    }

    /** [order] with the candidate of text [pinned] moved first, [text] telling each position's. */
    fun pinnedFirst(order: IntArray, pinned: String?, text: (Int) -> String): IntArray {
        if (pinned == null) return order
        val at = order.indexOfFirst { text(it) == pinned }
        if (at <= 0) return order
        val moved = order.copyOf()
        System.arraycopy(order, 0, moved, 1, at)
        moved[0] = order[at]
        return moved
    }

    // what ranking reads of [h]: the pin is put first apart (pinnedFirst), and, not [mine], no word is first for being the user's
    private fun view(h: Habits, mine: Boolean) = h.rankView(pins = false, mine = mine)
}
