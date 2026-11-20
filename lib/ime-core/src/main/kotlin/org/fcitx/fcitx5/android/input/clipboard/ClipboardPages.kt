/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

/** The clipboard panel's two pages: what was copied, and the copies the user keeps. */
enum class ClipboardPage { Recent, Favorites }

enum class ClipboardShows {
    List,

    /** Nothing to list: [FavoritesEmpty] says why on the favourites' page. */
    Empty,

    /** History off: the recent page asks for it on. */
    EnableListening
}

enum class FavoritesEmpty { LongPressARecent, TurnOnHistory, LimitIsZero }

enum class ClipboardAction { Favorite, Unfavorite, MoveFirst, Split, Edit, SaveQuickPhrase, Share, Delete }

object ClipboardPages {

    /**
     * [remembered] unless something was copied since the panel was left ([copiesAtLeave] counts copies taken; both
     * restart at 0 with the process), when the copy is wanted under the finger. Back from 切词: the page it left.
     */
    fun opening(remembered: ClipboardPage, copiesAtLeave: Long, copies: Long, listening: Boolean, backFrom: ClipboardPage? = null): ClipboardPage = when {
        backFrom != null -> backFrom
        listening && copies > copiesAtLeave -> ClipboardPage.Recent
        else -> remembered
    }

    // the favourites stay usable with history off: they are not history
    fun shows(page: ClipboardPage, listening: Boolean, empty: Boolean): ClipboardShows = when {
        page == ClipboardPage.Recent && !listening -> ClipboardShows.EnableListening
        empty -> ClipboardShows.Empty
        else -> ClipboardShows.List
    }

    // the way to a favourite is a long press in 最近: say how when that page cannot hold one
    fun favoritesEmpty(listening: Boolean, limit: Int): FavoritesEmpty = when {
        !listening -> FavoritesEmpty.TurnOnHistory
        limit == 0 -> FavoritesEmpty.LimitIsZero
        else -> FavoritesEmpty.LongPressARecent
    }

    /**
     * An entry's long-press menu. [masked]: shown as dots, its pieces would show it; [sensitive]: never quick input,
     * offered on every keyboard. A favourite goes by 取消收藏, not 删除.
     */
    fun actions(page: ClipboardPage, sensitive: Boolean, masked: Boolean, first: Boolean): List<ClipboardAction> = buildList {
        when (page) {
            ClipboardPage.Recent -> add(ClipboardAction.Favorite)
            ClipboardPage.Favorites -> {
                add(ClipboardAction.Unfavorite)
                if (!first) add(ClipboardAction.MoveFirst)
            }
        }
        if (!masked) add(ClipboardAction.Split)
        add(ClipboardAction.Edit)
        if (!sensitive) add(ClipboardAction.SaveQuickPhrase)
        add(ClipboardAction.Share)
        if (page == ClipboardPage.Recent) add(ClipboardAction.Delete)
    }
}
