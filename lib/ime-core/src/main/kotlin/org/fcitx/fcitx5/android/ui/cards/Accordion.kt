/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.cards

/**
 * The key editor's folding cards (dev/design/061-settings-cards.md §5.1): [count] cards, at most one
 * open ([open], null for none). A title tapped opens its card and folds the open one, or folds its own
 * when it is the open one; a card with an error opens whatever was open.
 */
class Accordion(val count: Int, open: Int?) {

    init {
        require(count > 0) { "count $count" }
    }

    var open: Int? = open?.let(::check)
        private set

    /** [card]'s title tapped. */
    fun tap(card: Int) {
        open = if (open == check(card)) null else card
    }

    /** [card] holds an error the user must see: open, whatever was. */
    fun showError(card: Int) {
        open = check(card)
    }

    private fun check(card: Int): Int = card.also { require(it in 0 until count) { "card $it of $count" } }

    companion object {
        /** The least room an open card scrolls in, × the font scale: less than this, the whole content scrolls (§5.1). */
        const val MIN_CONTENT_DP = 160

        /**
         * Whether the titles stay put and the open card scrolls inside itself: [availablePx] high for the cards, of
         * which their titles (and gaps) take [titlesPx], at least [minContentPx] left for the open card's content.
         * Otherwise the whole content scrolls, one tree as ever, the open card uncapped.
         */
        fun fixedTitles(availablePx: Int, titlesPx: Int, minContentPx: Int): Boolean = availablePx - titlesPx >= minContentPx

        /** The card open when the editor opens: the first while the titles stay put, none when the whole scrolls. */
        fun opening(fixedTitles: Boolean): Int? = if (fixedTitles) 0 else null
    }
}
