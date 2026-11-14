/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * The symbol panel, after Sogou's: the categories down its side and one shown at a time, so a
 * symbol is where it was the last time; a symbol picked takes the keyboard back, unless the
 * panel is locked for a run of them. The one list scrolled through all categories, with tabs
 * above, read worse: nothing stayed put, and three short rows were all one saw.
 *
 * @param categories how many, [RECENT] the first
 * @param first where the panel opens while none was picked and nothing was used, and where it goes
 * when the recently used it showed are gone: the common symbols
 * @param remembered the category picked last, as the app keeps it across restarts (user 2026-11-14): null
 * for none, as one no longer in the list (a category renamed or gone) is too
 */
class SymbolPanel(private val categories: Int, private val first: Int, remembered: Int? = null) {

    init {
        require(first in 0 until categories) { "first $first of $categories" }
    }

    private val kept = remembered?.takeIf { it in 0 until categories }

    var selected = kept ?: first
        private set

    // a category picked on the side: from then on the panel opens on the one shown last (user 2026-11-13)
    private var picked = kept != null

    /**
     * Opened again, on the category shown last: not the recently used, if there are none now. Till one is
     * picked, on the recently used once there are any, else on [first].
     */
    fun open(recentEmpty: Boolean) {
        if (!picked) selected = if (recentEmpty) first else RECENT
        if (!shows(selected, recentEmpty)) selected = first
    }

    /** Whether [category] is listed: the recently used, only once something was used. */
    fun shows(category: Int, recentEmpty: Boolean) = category != RECENT || !recentEmpty

    /** [category] picked on the side: what the app keeps as [remembered]; a reopen is no pick. */
    fun select(category: Int) {
        require(category in 0 until categories) { "category $category of $categories" }
        selected = category
        picked = true
    }

    /** Whether a symbol picked takes the keyboard back: one symbol is what is wanted most often. */
    fun returnsAfterPick(locked: Boolean) = !locked

    companion object {
        const val RECENT = 0
    }
}
