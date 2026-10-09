/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A search result whose setting its page did not have once flashed a setting of the same title,
 * or switched the theme page's tab, on the next page opened within a few seconds.
 */
@RunWith(RobolectricTestRunner::class)
class SearchHighlightTest {

    private lateinit var ctx: Context

    // a page's preferences without hosting its fragment: only its screen and where it scrolled are read
    private class Page(var screen: PreferenceScreen) : PreferenceFragmentCompat() {
        var scrolledTo: Preference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) = Unit

        override fun getPreferenceScreen(): PreferenceScreen = screen

        override fun scrollToPreference(preference: Preference) {
            scrolledTo = preference
        }
    }

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        SearchHighlight.point(null)
    }

    private fun screen(vararg titles: String) = PreferenceManager(ctx).createPreferenceScreen(ctx).apply {
        titles.forEach { t ->
            addPreference(Preference(ctx).apply {
                isPersistent = false
                title = t
            })
        }
    }

    @Test
    fun aPageOpenedAfterTheResultsOwnIsNotPointedAt() {
        SearchHighlight.point("Wanted")
        SearchHighlight.showIn(Page(screen("Other")))
        val next = Page(screen("Wanted"))
        SearchHighlight.showIn(next)
        assertNull(next.scrolledTo)
    }

    @Test
    fun theResultsPageFindsTheSettingOnceItsPreferencesArrive() {
        SearchHighlight.point("Wanted")
        val page = Page(screen())
        SearchHighlight.showIn(page)
        page.screen = screen("Other", "Wanted")
        SearchHighlight.showIn(page)
        assertEquals("Wanted", page.scrolledTo?.title?.toString())
    }

    @Test
    fun aSettingAPageHasLookedForIsNoLongerWaitedFor() {
        SearchHighlight.point("Wanted")
        assertTrue(SearchHighlight.isWaiting())
        SearchHighlight.showIn(Page(screen("Other")))
        assertFalse(SearchHighlight.isWaiting())
    }
}
