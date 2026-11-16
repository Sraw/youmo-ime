/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.view.View
import androidx.annotation.StringRes

/**
 * A page drawn by hand, as the settings search finds it (SettingsPages): its rows and controls, named as the
 * page names them, each by the id it is pointed at by. Said by the page's companion, beside the page's own
 * constants; SettingsPagesTest opens the page and holds what it shows to what it says (dev/design/061-settings-search.md §4.8 T3).
 */
interface SearchablePage {

    /** What is found on the page, in its order. */
    fun searchEntries(context: Context): List<PageEntry>

    /**
     * The words on its controls that are not searched, each for a reason the page gives beside it: a dialog's,
     * a state's of a moment (多选's bar), the page's own name.
     */
    val notSearched: Set<Int> get() = emptySet()
}

/**
 * A row or control of a page drawn by hand: [id], a preference's (pref:<key>) or a control's (view:<page>/<name>,
 * the View's tag); with [where], one that has no View to point at (the title bar's ↶, an item of ⋮), said instead.
 */
data class PageEntry(val title: String, val id: String, @StringRes val where: Int = 0, val summary: String = "") {
    init {
        require(id.startsWith(SearchHighlight.PREF) || id.startsWith(SearchHighlight.VIEW)) { id }
    }
}

/**
 * A page whose rows or controls come after a read (the engine's options or table, a list of files): a search result's
 * id is kept for it up to 30 s, not 5, and the page says when it has them, by [SearchHighlight.ready] (a page of
 * preferences may by setting its screen anew: PaddingPreferenceFragment points it then).
 */
interface ReadyLater

/** A page drawn by hand that a search result can point at a control of (view:): the page says when it can ([SearchHighlight.ready]). */
interface SearchAnchors : ReadyLater {

    /**
     * Brings the control [id] names into sight as a tap would (a keyboard's card picked, the group cards shown
     * again) and gives it to [found], at once or once it is laid out; nothing where there is none.
     */
    fun reveal(id: String, found: (View) -> Unit)
}
