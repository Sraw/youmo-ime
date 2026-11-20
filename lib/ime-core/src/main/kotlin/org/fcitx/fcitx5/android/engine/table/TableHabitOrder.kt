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

/** A table's candidates reordered by habits under the keys alone: with 调频 all of them, else the table's own stay put and outside words go after; the pin goes first after this. */
internal object TableHabitOrder {

    /** [original] positions reordered under [here]; null where nothing is learned. Only the first [reach] (the 重码) may move: none where the code is kept in order (简码). */
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
            // the user's own words from pinyin are not first here for being theirs: by use here alone
            HabitRanker.rank(ours.map(::item) + others.map { it.item }, here, "", view(h, mine = false))
        } else {
            ours.map { HabitRanker.Ranked(item(it), HabitRanker.Tier.DICTIONARY, h.uses(here, Words.of(text(it)))) } + others
        }
        val placed = original.copyOf()
        if (orderByUse) {
            all.forEachIndexed { k, r -> placed[k] = r.value }
        } else {
            val outside = others.iterator()
            for (k in 0 until reach) if (!own(original[k])) placed[k] = outside.next().value
        }
        return placed to all.associateBy { it.item.words.text }
    }

    /** What 为什么排在这 says of [text]: the pin first, else its tier; nothing unranked. */
    fun why(h: Habits, code: Code, text: String, r: HabitRanker.Ranked<Int>?): Why? = when {
        h.pinned(code)?.text == text -> Why(HabitRanker.Tier.PINNED, r?.uses ?: 0, code)
        r != null -> Why(r.tier, r.uses, code)
        else -> null
    }

    fun pinnedFirst(order: IntArray, pinned: String?, text: (Int) -> String): IntArray {
        if (pinned == null) return order
        val at = order.indexOfFirst { text(it) == pinned }
        if (at <= 0) return order
        val moved = order.copyOf()
        System.arraycopy(order, 0, moved, 1, at)
        moved[0] = order[at]
        return moved
    }

    private fun view(h: Habits, mine: Boolean) = h.rankView(pins = false, mine = mine)
}
