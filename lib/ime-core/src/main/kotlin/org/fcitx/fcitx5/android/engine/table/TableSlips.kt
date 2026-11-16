/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.SlipCost
import org.fcitx.fcitx5.android.engine.pinyin.Touch

/**
 * A table's key tapped off-centre (061-neighbour-table): read as a key around it where the key typed
 * would lead nowhere: an empty code (空码: no candidate's code starts so), or a commit of the first
 * candidate shown with the key starting the next code (顶字, [TableOptions.noMatchAutoSelectLength], or
 * a code read so whose one candidate waits for a key that cannot go on); and that key, as near to the tap as [Costs.cost]
 * allows, leads on: the nearest, then the one whose first candidate the word model weighs more. A code
 * with a key so read never commits itself as it is typed ([marks]). The key itself where it leads on (a
 * code with entries is never touched), on a code empty already, and for the wildcard, the pinyin key and
 * a selection key, nor is a key read as one of them. Where the key would commit something, a typist's
 * code (简码, or a part of one that shows the character first) then the next code's first key most often,
 * only a tap nearer the edge ([Costs.toppingFloor]) is read, as a key whose code's first is another text
 * the word model weighs as much as the one the key would commit: typed on target (TouchSim's taps, whole
 * codes and the shortest part that shows the character first), no word changes. [TableSession] asks at
 * the code's end only.
 */
class TableSlips internal constructor(
    private val costs: Costs,
    private val options: TableOptions,
    private val table: TableDictionary,
    private val language: TableLanguage?,
    private val isCode: (Char) -> Boolean,
    private val leadsAnywhere: (String) -> Boolean,
    // what a key leading nowhere would commit (the first candidate shown: 顶字, or a code read so waiting), else null
    private val topped: () -> String?,
) {

    /** What reading a tap as another key costs: [cost] (its floor) where the key would lead nowhere, [toppingFloor] where it would commit something. */
    data class Costs(val cost: SlipCost, val toppingFloor: Float)

    // the places in the code of the keys read as the one beside them, a bit each: such a code never commits
    // itself. A key is read short of the table's longest code only, so the first bits; one moved past the
    // last by keys put in before it is dropped, as a code of 64 keys has nothing to commit
    private var at = 0L

    /** [c] tapped at [touch] after [code]: the key read. */
    fun read(c: Char, touch: Touch, code: CharSequence): Char {
        val cost = costs.cost
        // a tap near no other key, most of them: nothing to look up (the lookups are what costs a key on target)
        if (!readable(c) || touch.near.none { it.char != c && !cost.of(touch, it, legal = false).isNaN() }) return c
        val typed = code.toString()
        if (leadsAnywhere(typed + c) || !leadsAnywhere(typed)) return c
        val whole = topped()
        val floor = if (whole != null) costs.toppingFloor else cost.floor
        // weighed once a neighbour is near enough: most taps on a topping key are not
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

    /** Whether the code has a key read as another: it then never commits itself as it is typed. */
    fun marks() = at != 0L

    /** A key put in at [cursor] of the code, [length] keys before it: those read after it move on (at the end, the key just read stays). */
    fun inserted(cursor: Int, length: Int) {
        if (cursor >= length || cursor >= Long.SIZE_BITS) return
        val before = (1L shl cursor) - 1
        at = (at and before) or ((at and before.inv()) shl 1)
    }

    /** The key at [cursor] deleted: if it was one read as another, that is gone; those after it move back. */
    fun deleted(cursor: Int) {
        if (cursor >= Long.SIZE_BITS) return
        val before = (1L shl cursor) - 1
        at = (at and before) or ((at ushr 1) and before.inv())
    }

    /** The code gone. */
    fun clear() {
        at = 0L
    }

    // what reading [c]'s tap at [touch] as [near] costs: NaN where that is no key to read it as
    private fun costOf(c: Char, touch: Touch, near: Near): Float =
        if (near.char == c || !readable(near.char) || near.char in options.endKeys) Float.NaN else costs.cost.of(touch, near, legal = false)

    // the table's first text of [code], read: none where it leads nowhere, to pinyin alone, or to the very text
    // the key would commit ([whole]: the key is the next code's, nothing gained). The table's first, not the
    // order the session would show: that is weighed for the code typed only, and its lookups are what a key on
    // target would pay for
    private fun firstOf(code: String, whole: String?): String? {
        if (!leadsAnywhere(code)) return null
        val first = table.firstOf(code)
        return if (first < 0) null else table.text(first).takeUnless { it == whole }
    }

    // a key a slip is read from and as: one of the codes, not the wildcard, the pinyin key nor a selection key
    private fun readable(c: Char) = isCode(c) && c != options.matchingKey && c != options.pinyinKey && c !in options.selectionKeys

    // how the word model weighs [text]: alike for all where there is no language (the table's order then)
    private fun score(text: String): Float = language?.words?.logProb(text) ?: 0f

    companion object {
        /**
         * A table's slips: read only where the key leads nowhere, never against a reading that has entries,
         * so with no legal cost. Floor −1.5 (061-neighbour-table-impl-report): a tap in a key's middle reads
         * as typed (a QWERTY neighbour across costs it −2.8 or less), and of the empty codes a slip made,
         * those read into a wrong character are 1% (仓颉) and 2.5% (二笔), against 8% and 20% at −4, for a few
         * more right ones below it. Where the key would commit what is shown (顶字, or a code that waits),
         * −0.1 with the word model's check: typed by the shortest part of a code that shows the word, then the
         * next word's first key, −0.5 changed words typed on target 3 times in 250 000 (覅 → 要饭, 低级 → 父母:
         * rare ones the model weighs below the phrase a neighbour leads to) and −0.15 still once; −0.1 none in
         * 570 000 (whole codes and parts, five tables, s0.2 and s0.3, seeds 1–3, both halves), reading a
         * third of what −0.5 read (五笔 238 of 801 slips into an empty code).
         */
        val SLIPS = Costs(SlipCost(floor = -1.5f), toppingFloor = -0.1f)
    }
}
