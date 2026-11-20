/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAction.Delete
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAction.Edit
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAction.Favorite
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAction.MoveFirst
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAction.SaveQuickPhrase
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAction.Share
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAction.Split
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAction.Unfavorite
import org.fcitx.fcitx5.android.input.clipboard.ClipboardPage.Favorites
import org.fcitx.fcitx5.android.input.clipboard.ClipboardPage.Recent
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardPagesTest {

    @Test
    fun thePanelOpensOnTheRememberedPageUnlessSomethingWasCopiedSince() = assertRows(
        "kept on the favourites: opens there" to { assertEquals(Favorites, ClipboardPages.opening(Favorites, 3, 3, listening = true)) },
        "a copy since it was left: the copy under the finger" to { assertEquals(Recent, ClipboardPages.opening(Favorites, 3, 4, listening = true)) },
        "an entry pasted and the panel closed moves no count: the remembered page" to { assertEquals(Recent, ClipboardPages.opening(Recent, 5, 5, listening = true)) },
        "a restarted process counts from 0 both: as not copied" to { assertEquals(Favorites, ClipboardPages.opening(Favorites, 0, 0, listening = true)) },
        "history off: nothing to show on 最近, the remembered page" to { assertEquals(Favorites, ClipboardPages.opening(Favorites, 1, 2, listening = false)) },
        "back from 切词: the page it was opened from, copies or not" to { assertEquals(Favorites, ClipboardPages.opening(Recent, 0, 9, listening = true, backFrom = Favorites)) },
        "back from 切词 on 最近" to { assertEquals(Recent, ClipboardPages.opening(Favorites, 0, 0, listening = true, backFrom = Recent)) },
    )

    @Test
    fun eachPageShowsItsListItsEmptyHintOrTheHistoryPrompt() = assertRows(
        "recent with entries lists them" to { assertEquals(ClipboardShows.List, ClipboardPages.shows(Recent, listening = true, empty = false)) },
        "recent empty asks for a copy" to { assertEquals(ClipboardShows.Empty, ClipboardPages.shows(Recent, listening = true, empty = true)) },
        "recent with history off asks for it on, entries or not" to { assertEquals(ClipboardShows.EnableListening, ClipboardPages.shows(Recent, listening = false, empty = false)) },
        "recent with history off and empty" to { assertEquals(ClipboardShows.EnableListening, ClipboardPages.shows(Recent, listening = false, empty = true)) },
        "favourites list with history off: they are not history" to { assertEquals(ClipboardShows.List, ClipboardPages.shows(Favorites, listening = false, empty = false)) },
        "favourites empty with history off: the empty hint, not the prompt" to { assertEquals(ClipboardShows.Empty, ClipboardPages.shows(Favorites, listening = false, empty = true)) },
        "favourites with history on" to { assertEquals(ClipboardShows.List, ClipboardPages.shows(Favorites, listening = true, empty = false)) },
    )

    @Test
    fun theEmptyFavouritesSayHowToGetOne() = assertRows(
        "history on with room: long-press one in 最近" to { assertEquals(FavoritesEmpty.LongPressARecent, ClipboardPages.favoritesEmpty(listening = true, limit = 10)) },
        "history off: turn it on first" to { assertEquals(FavoritesEmpty.TurnOnHistory, ClipboardPages.favoritesEmpty(listening = false, limit = 10)) },
        "history off and limit 0: history first, the limit then" to { assertEquals(FavoritesEmpty.TurnOnHistory, ClipboardPages.favoritesEmpty(listening = false, limit = 0)) },
        "limit 0 keeps no copy to long-press" to { assertEquals(FavoritesEmpty.LimitIsZero, ClipboardPages.favoritesEmpty(listening = true, limit = 0)) },
        "unlimited is room" to { assertEquals(FavoritesEmpty.LongPressARecent, ClipboardPages.favoritesEmpty(listening = true, limit = Int.MAX_VALUE)) },
    )

    @Test
    fun theMenuGoesByPageAndWhatTheEntryIs() = assertRows(
        "a recent plain entry" to {
            assertEquals(listOf(Favorite, Split, Edit, SaveQuickPhrase, Share, Delete), ClipboardPages.actions(Recent, sensitive = false, masked = false, first = true))
        },
        "a recent password shown as dots: no pieces, no quick input" to {
            assertEquals(listOf(Favorite, Edit, Share, Delete), ClipboardPages.actions(Recent, sensitive = true, masked = true, first = false))
        },
        "a recent password shown plain (masking off): pieces, still no quick input" to {
            assertEquals(listOf(Favorite, Split, Edit, Share, Delete), ClipboardPages.actions(Recent, sensitive = true, masked = false, first = false))
        },
        "a favourite not first: unfavourite and move first, no delete" to {
            assertEquals(listOf(Unfavorite, MoveFirst, Split, Edit, SaveQuickPhrase, Share), ClipboardPages.actions(Favorites, sensitive = false, masked = false, first = false))
        },
        "the first favourite has nowhere to move" to {
            assertEquals(listOf(Unfavorite, Split, Edit, SaveQuickPhrase, Share), ClipboardPages.actions(Favorites, sensitive = false, masked = false, first = true))
        },
        "a dotted favourite" to {
            assertEquals(listOf(Unfavorite, MoveFirst, Edit, Share), ClipboardPages.actions(Favorites, sensitive = true, masked = true, first = false))
        },
    )
}
