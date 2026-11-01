/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

/**
 * The page of candidates a session shows, [size] to a page: one paging for pinyin, the tables,
 * English, handwriting and the stroke lookup, which each kept a page and its sums before (S11).
 *
 * Whether a page exists is asked of the candidate at its start ([turn], [keep]), as a list's
 * candidates are counted and a table's or the stroke lookup's are made as they are read. What a
 * turn to no page answers, handled or not, stays the session's.
 */
internal class Pager(val size: Int) {
    init {
        require(size >= 1) { "page size $size" }
    }

    /** The page shown, 0 the first. */
    var page = 0
        private set

    /** Where the page shown starts among the candidates. */
    val from: Int get() = page * size

    /** Whether a page comes before the one shown. */
    val hasPrevious: Boolean get() = page > 0

    /** Back to the first page. */
    fun reset() {
        page = 0
    }

    /** Where the candidate [onPage] on the page shown is among them all; -1 past the page (a key that is no label of it). */
    fun at(onPage: Int): Int = if (onPage in 0 until size) from + onPage else -1

    /** Turns to page [to] if [has] a candidate at its start; whether it did. */
    fun turn(to: Int, has: (index: Int) -> Boolean): Boolean {
        if (to < 0 || !has(to * size)) return false
        page = to
        return true
    }

    /** Turns to page [to] of [count] candidates, if it has one; whether it did. */
    fun turn(to: Int, count: Int): Boolean = turn(to) { it < count }

    /** Page [shown] again, the candidates made anew, if [has] still a candidate at its start; else the first. */
    fun keep(shown: Int, has: (index: Int) -> Boolean) {
        page = if (shown >= 0 && has(shown * size)) shown else 0
    }

    /** Page [shown] again of [count] candidates made anew, if it still has one; else the first. */
    fun keep(shown: Int, count: Int) = keep(shown) { it < count }

    /** Whether a page comes after the one shown, of [count] candidates. */
    fun hasNext(count: Int): Boolean = from + size < count

    /** The page shown of [list]. */
    fun <T> of(list: List<T>): List<T> = list.subList(minOf(from, list.size), minOf(from + size, list.size))
}
