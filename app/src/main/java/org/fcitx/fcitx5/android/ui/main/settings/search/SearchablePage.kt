/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.view.View
import androidx.annotation.StringRes

/** A hand-drawn page's searchable rows and controls, declared by its companion; SettingsPagesTest holds the page to it. */
interface SearchablePage {

    fun searchEntries(context: Context): List<PageEntry>

    /** Words on controls deliberately unsearched (a dialog's, a momentary state like 多选's bar, the page's own name). */
    val notSearched: Set<Int> get() = emptySet()
}

/** [id]: pref:<key> or view:<page>/<name> (the View's tag); [where]: said instead when there is no View (↶, ⋮'s items). */
data class PageEntry(val title: String, val id: String, @StringRes val where: Int = 0, val summary: String = "") {
    init {
        require(id.startsWith(SearchHighlight.PREF) || id.startsWith(SearchHighlight.VIEW)) { id }
    }
}

/**
 * Rows come after a read: a result's id is kept 30 s, not 5, and the page calls [SearchHighlight.ready] (a preference
 * page may instead set its screen anew: PaddingPreferenceFragment points it then).
 */
interface ReadyLater

/** A hand-drawn page whose controls (view:) a result can point at, once it says [SearchHighlight.ready]. */
interface SearchAnchors : ReadyLater {

    /** Into sight as a tap would (a card picked, the group cards shown again), given to [found] once laid out; nothing if absent. */
    fun reveal(id: String, found: (View) -> Unit)
}
