/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * The setting a search result opens its page at: the page's fragment, once its preferences are
 * there (an input method's come after fcitx answers), scrolls to it and flashes it. The setting's
 * key is kept in the saved state of the result's own entry on the back stack, for a few seconds at
 * most: no other page is pointed at it, and the result's page is not pointed at it long after.
 */
object SearchHighlight {

    /** Has [entry], the page a search result has just opened, point at its preference [key], if any. */
    fun point(entry: NavBackStackEntry, key: String?) {
        if (key == null) return
        entry.savedStateHandle[KEY] = key
        entry.savedStateHandle[AT] = SystemClock.uptimeMillis()
    }

    /** Whether [fragment]'s page is still to point at a setting a search result opened it at. */
    fun isWaiting(fragment: Fragment): Boolean =
        fragment.findNavController().currentBackStackEntry?.let { isWaiting(it) } == true

    internal fun isWaiting(entry: NavBackStackEntry): Boolean {
        val state = entry.savedStateHandle
        return state.contains(KEY) && isFresh(state)
    }

    /** Points [fragment] at the setting its page is to point at, if it has it. */
    fun showIn(fragment: PreferenceFragmentCompat) {
        // posted from its view, so it may have left its FragmentManager, and with it the NavController
        if (!fragment.isAdded) return
        fragment.findNavController().currentBackStackEntry?.let { showIn(fragment, it) }
    }

    internal fun showIn(fragment: PreferenceFragmentCompat, entry: NavBackStackEntry) {
        val state = entry.savedStateHandle
        val key = state.get<String>(KEY) ?: return
        if (!isFresh(state)) {
            forget(state)
            return
        }
        val screen = fragment.preferenceScreen ?: return
        val pref = screen.findPreference<Preference>(key) ?: return
        forget(state)
        val list = fragment.listView
        whenListed(list) {
            fragment.scrollToPreference(pref)
            // the flash counts from the scroll, not from here: an adapter late by a few tries had
            // the page scrolled to a row that never flashed
            list ?: return@whenListed
            main.postDelayed({
                val position = (list.adapter as? PreferenceGroup.PreferencePositionCallback)?.getPreferenceAdapterPosition(pref) ?: return@postDelayed
                val row = list.findViewHolderForAdapterPosition(position)?.itemView ?: return@postDelayed
                // the row's own ripple, as if touched: it is the theme's, and fades by itself
                row.isPressed = true
                row.postDelayed({ row.isPressed = false }, FLASH)
            }, SETTLE)
        }
    }

    /**
     * Runs [scroll] once [list] has its adapter, looking again every [RETRY] ms for a second: the
     * list gets the screen's adapter in a bind setPreferenceScreen posts, and scrollToPreference
     * before it returns having done nothing (a page opened from a search stayed at its top). With no
     * list yet, at once: scrollToPreference then waits for the list itself.
     */
    internal fun whenListed(list: RecyclerView?, tries: Int = TRIES, scroll: () -> Unit) {
        if (list == null || list.adapter != null) scroll()
        else if (tries > 0) main.postDelayed({ whenListed(list, tries - 1, scroll) }, RETRY)
    }

    // the main looper's, not the view's: a view's own posts wait for it to be attached
    private val main by lazy { Handler(Looper.getMainLooper()) }

    // uptime goes on while the process is gone: a page brought back long after points at nothing
    private fun isFresh(state: SavedStateHandle) = SystemClock.uptimeMillis() - (state.get<Long>(AT) ?: 0L) <= WAIT

    private fun forget(state: SavedStateHandle) {
        state.remove<String>(KEY)
        state.remove<Long>(AT)
    }

    // beside the route's own arguments in the entry's saved state: names no route has
    private const val KEY = "search_highlight_key"
    private const val AT = "search_highlight_at"

    private const val WAIT = 5000L
    private const val SETTLE = 250L
    private const val FLASH = 600L
    private const val RETRY = 50L
    private const val TRIES = 20
}
