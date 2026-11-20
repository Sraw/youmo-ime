/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/** 「最近」 of the emoji and symbol panels, each with its time so clearing recent learning clears them too; nothing shown or kept while habits are off. Never words. */
class RecentItems(private val limit: Int) {

    private class Item(val text: String, val at: Long)

    // oldest first, as the preferences have kept them
    private val items = ArrayList<Item>()

    /** Without times (an older build's), all as of time zero: shifted times would clear the wrong items. */
    fun load(stored: List<String>, at: List<Long>) {
        items.clear()
        val timed = at.size == stored.size
        stored.forEachIndexed { i, text -> items += Item(text, if (timed) at[i] else 0L) }
        dropOldest()
    }

    /** Oldest first. */
    val stored: List<String> get() = items.map { it.text }
    val storedTimes: List<Long> get() = items.map { it.at }

    /** Newest first; nothing while the habits are off. */
    fun shown(habits: Boolean): List<String> = if (habits) items.asReversed().map { it.text } else emptyList()

    /** The oldest past [limit] gone. Whether anything changed: not while the habits are off. */
    fun use(text: String, at: Long, habits: Boolean): Boolean {
        if (!habits) return false
        items.removeAll { it.text == text }
        items += Item(text, at)
        dropOldest()
        return true
    }

    /** [from] 0 forgets all. */
    fun clearSince(from: Long): Boolean = items.removeAll { it.at >= from }

    private fun dropOldest() {
        while (items.size > limit) items.removeAt(0)
    }
}
