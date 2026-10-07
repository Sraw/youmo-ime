/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.os.SystemClock
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup

/**
 * The setting a search result opens its page at: the page's fragment, once its preferences are
 * there (an input method's come after fcitx answers), scrolls to it and flashes it. Kept a few
 * seconds only, so a page opened later some other way is not taken for it.
 */
object SearchHighlight {

    private var title: String? = null
    private var at = 0L

    fun point(title: String?) {
        this.title = title
        at = SystemClock.uptimeMillis()
    }

    fun isWaiting(): Boolean = title != null && SystemClock.uptimeMillis() - at <= WAIT

    /** Points [fragment] at the setting, if it has it and one is waited for. */
    fun showIn(fragment: PreferenceFragmentCompat) {
        val wanted = title ?: return
        if (SystemClock.uptimeMillis() - at > WAIT) {
            title = null
            return
        }
        val screen = fragment.preferenceScreen ?: return
        val pref = find(screen, wanted) ?: return
        title = null
        fragment.scrollToPreference(pref)
        val list = fragment.listView ?: return
        list.postDelayed({
            val position = (list.adapter as? PreferenceGroup.PreferencePositionCallback)?.getPreferenceAdapterPosition(pref) ?: return@postDelayed
            val row = list.findViewHolderForAdapterPosition(position)?.itemView ?: return@postDelayed
            // the row's own ripple, as if touched: it is the theme's, and fades by itself
            row.isPressed = true
            row.postDelayed({ row.isPressed = false }, FLASH)
        }, SETTLE)
    }

    private fun find(group: PreferenceGroup, title: String): Preference? {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref.title?.toString() == title) return pref
            if (pref is PreferenceGroup) find(pref, title)?.let { return it }
        }
        return null
    }

    private const val WAIT = 5000L
    private const val SETTLE = 250L
    private const val FLASH = 600L
}
