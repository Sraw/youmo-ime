/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * The 「最近」 of the emoji and the symbol panel (U-67): each item with when it was last used, so
 * that clearing the last few minutes or all that was learned (U-88, U-86) clears them too. While
 * the user's habits are not used (U-93's 「用我的输入习惯」 off, [habits] false) none is shown
 * and none kept, those from before shown again once it is back on. They are never words: nothing
 * of them reaches a lexicon.
 *
 * @param limit how many are kept, the oldest going first
 */
class RecentItems(private val limit: Int) {

    private class Item(val text: String, val at: Long)

    // oldest first, as the preferences have kept them
    private val items = ArrayList<Item>()

    /**
     * What was kept, oldest first: [stored], [at] when each was used. Without a time each (kept by an
     * older build, which also rewrites the items and not the times after a downgrade), all as of the
     * start of time: times shifted onto other items would have the wrong ones cleared.
     */
    fun load(stored: List<String>, at: List<Long>) {
        items.clear()
        val timed = at.size == stored.size
        stored.forEachIndexed { i, text -> items += Item(text, if (timed) at[i] else 0L) }
        dropOldest()
    }

    /** What to keep, oldest first, and when each was used. */
    val stored: List<String> get() = items.map { it.text }
    val storedTimes: List<Long> get() = items.map { it.at }

    /** What is shown, the newest first: nothing while the habits are off. */
    fun shown(habits: Boolean): List<String> = if (habits) items.asReversed().map { it.text } else emptyList()

    /** [text] used [at]: first of them, the oldest past [limit] gone. Whether anything changed: not while the habits are off. */
    fun use(text: String, at: Long, habits: Boolean): Boolean {
        if (!habits) return false
        items.removeAll { it.text == text }
        items += Item(text, at)
        dropOldest()
        return true
    }

    /** Forgets those used at [from] or later (U-88); [from] 0 forgets all (U-86). Whether any was. */
    fun clearSince(from: Long): Boolean = items.removeAll { it.at >= from }

    private fun dropOldest() {
        while (items.size > limit) items.removeAt(0)
    }
}
