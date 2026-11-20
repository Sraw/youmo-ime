/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.cards

/** The key editor's folding cards: at most one open; a title tapped opens its card or folds its own; an error opens its card. */
class Accordion(val count: Int, open: Int?) {

    init {
        require(count > 0) { "count $count" }
    }

    var open: Int? = open?.let(::check)
        private set

    fun tap(card: Int) {
        open = if (open == check(card)) null else card
    }

    /** Open, whatever was. */
    fun showError(card: Int) {
        open = check(card)
    }

    private fun check(card: Int): Int = card.also { require(it in 0 until count) { "card $it of $count" } }

    companion object {
        /** × the font scale; with less, the whole content scrolls. */
        const val MIN_CONTENT_DP = 160

        /** Titles stay put and the open card scrolls inside if [minContentPx] is left; else the whole content scrolls, the card uncapped. */
        fun fixedTitles(availablePx: Int, titlesPx: Int, minContentPx: Int): Boolean = availablePx - titlesPx >= minContentPx

        /** The first while the titles stay put, none when the whole scrolls. */
        fun opening(fixedTitles: Boolean): Int? = if (fixedTitles) 0 else null
    }
}
