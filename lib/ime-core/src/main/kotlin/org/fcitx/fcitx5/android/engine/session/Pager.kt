/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

/** The page of candidates a session shows, [size] to a page; whether a page exists is asked of the candidate at its start, as tables make theirs as they are read. */
internal class Pager(val size: Int) {
    init {
        require(size >= 1) { "page size $size" }
    }

    var page = 0
        private set

    val from: Int get() = page * size

    val hasPrevious: Boolean get() = page > 0

    fun reset() {
        page = 0
    }

    /** Where the candidate [onPage] on the page shown is among them all; -1 past the page. */
    fun at(onPage: Int): Int = if (onPage in 0 until size) from + onPage else -1

    /** Turns to page [to] if [has] a candidate at its start; whether it did. */
    fun turn(to: Int, has: (index: Int) -> Boolean): Boolean {
        if (to < 0 || !has(to * size)) return false
        page = to
        return true
    }

    fun turn(to: Int, count: Int): Boolean = turn(to) { it < count }

    /** Page [shown] again, the candidates made anew, if [has] still a candidate at its start; else the first. */
    fun keep(shown: Int, has: (index: Int) -> Boolean) {
        page = if (shown >= 0 && has(shown * size)) shown else 0
    }

    fun keep(shown: Int, count: Int) = keep(shown) { it < count }

    fun hasNext(count: Int): Boolean = from + size < count

    fun <T> of(list: List<T>): List<T> = list.subList(minOf(from, list.size), minOf(from + size, list.size))
}
