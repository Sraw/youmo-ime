/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.SlipCost
import org.fcitx.fcitx5.android.engine.pinyin.Touch

/**
 * A table key tapped off-centre is read as a neighbour only where the typed key would lead nowhere (空码, or 顶字
 * committing the first shown) and the neighbour leads on: the nearest, then the one whose first candidate the word
 * model weighs more. A code with a key so read never commits itself as typed ([marks]). Never for the wildcard, the
 * pinyin key or a selection key. Where the key would commit something only a tap nearer the edge
 * ([Costs.toppingFloor]) is read, so on-target typing changes no word. [TableSession] asks at the code's end only.
 */
class TableSlips internal constructor(
    private val costs: Costs,
    private val options: TableOptions,
    private val table: TableDictionary,
    private val language: TableLanguage?,
    private val isCode: (Char) -> Boolean,
    private val leadsAnywhere: (String) -> Boolean,
    private val topped: () -> String?,
) {

    /** What reading a tap as another key costs: [cost] where the key leads nowhere, [toppingFloor] where it would commit something. */
    data class Costs(val cost: SlipCost, val toppingFloor: Float)

    // a bit per code place read as a neighbour; reads happen short of the longest code, and a bit pushed past 64 is dropped
    private var at = 0L

    fun read(c: Char, touch: Touch, code: CharSequence): Char {
        val cost = costs.cost
        // a tap near no other key, most of them: nothing to look up (the lookups are what costs a key on target)
        if (!readable(c) || touch.near.none { it.char != c && !cost.of(touch, it, legal = false).isNaN() }) return c
        val typed = code.toString()
        if (leadsAnywhere(typed + c) || !leadsAnywhere(typed)) return c
        val whole = topped()
        val floor = if (whole != null) costs.toppingFloor else cost.floor
        var least: Float? = null
        var meant = c
        var best = Float.NEGATIVE_INFINITY
        var bestScore = Float.NEGATIVE_INFINITY
        for (near in touch.near) {
            val nearCost = costOf(c, touch, near)
            // NaN (too far, or no key to read) fails the comparison
            val first = if (nearCost >= maxOf(floor, best)) firstOf(typed + near.char, whole) else null
            val score = first?.let(::score) ?: continue
            if (least == null) least = whole?.let(::score) ?: Float.NEGATIVE_INFINITY
            if (score >= least && (nearCost > best || score > bestScore)) {
                meant = near.char
                best = nearCost
                bestScore = score
            }
        }
        if (meant != c && typed.length < Long.SIZE_BITS) at = at or (1L shl typed.length)
        return meant
    }

    fun marks() = at != 0L

    /** A key put in at [cursor]: those read after it move on (at the end, the key just read stays). */
    fun inserted(cursor: Int, length: Int) {
        if (cursor >= length || cursor >= Long.SIZE_BITS) return
        val before = (1L shl cursor) - 1
        at = (at and before) or ((at and before.inv()) shl 1)
    }

    fun deleted(cursor: Int) {
        if (cursor >= Long.SIZE_BITS) return
        val before = (1L shl cursor) - 1
        at = (at and before) or ((at ushr 1) and before.inv())
    }

    fun clear() {
        at = 0L
    }

    private fun costOf(c: Char, touch: Touch, near: Near): Float =
        if (near.char == c || !readable(near.char) || near.char in options.endKeys) Float.NaN else costs.cost.of(touch, near, legal = false)

    // the table's first text of [code] (not the session's order, which costs lookups a key on target would pay for);
    // none where it leads nowhere, to pinyin alone, or to [whole]. The session's hiding is not seen here (rare)
    private fun firstOf(code: String, whole: String?): String? {
        if (!leadsAnywhere(code)) return null
        val first = table.firstOf(code)
        return if (first < 0) null else table.text(first).takeUnless { it == whole }
    }

    private fun readable(c: Char) = isCode(c) && c != options.matchingKey && c != options.pinyinKey && c !in options.selectionKeys

    private fun score(text: String): Float = language?.words?.logProb(text) ?: 0f

    companion object {
        /**
         * Floor −1.5: a tap in a key's middle reads as typed (a QWERTY neighbour across costs −2.8 or less), and wrong
         * characters from 空码 slips stay at 1–2.5% (8–20% at −4). Where the key would commit, −1 is the loosest that
         * changes no word typed on target in simulation.
         */
        val SLIPS = Costs(SlipCost(floor = -1.5f), toppingFloor = -1f)
    }
}
