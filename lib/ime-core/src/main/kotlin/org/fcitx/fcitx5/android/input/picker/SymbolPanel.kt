/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * The symbol panel, after Sogou's: categories down the side, one shown at a time, so a symbol stays where it was; a
 * pick returns to the keyboard unless locked. [categories] counts [RECENT] first; [first] (the common symbols) is where
 * it opens with nothing picked or used; [remembered] the last pick across restarts, null for none or one gone.
 */
class SymbolPanel(private val categories: Int, private val first: Int, remembered: Int? = null) {

    init {
        require(first in 0 until categories) { "first $first of $categories" }
    }

    private val kept = remembered?.takeIf { it in 0 until categories }

    var selected = kept ?: first
        private set

    // a category picked: from then on the panel opens on the one shown last
    private var picked = kept != null

    /** On the category shown last, not the recently used if empty now; till a pick, on the recently used if any, else [first]. */
    fun open(recentEmpty: Boolean) {
        if (!picked) selected = if (recentEmpty) first else RECENT
        if (!shows(selected, recentEmpty)) selected = first
    }

    /** The recently used only once something was used. */
    fun shows(category: Int, recentEmpty: Boolean) = category != RECENT || !recentEmpty

    /** A reopen is no pick. */
    fun select(category: Int) {
        require(category in 0 until categories) { "category $category of $categories" }
        selected = category
        picked = true
    }

    /** One symbol is what is wanted most often. */
    fun returnsAfterPick(locked: Boolean) = !locked

    companion object {
        const val RECENT = 0
    }
}
