/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.Navigation
import androidx.navigation.Navigator
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A search result whose setting its page did not have once flashed a setting of the same title,
 * or switched the theme page's tab, on the next page opened within a few seconds: the setting is
 * now named by its key, on the result's own entry on the back stack.
 */
@RunWith(RobolectricTestRunner::class)
class SearchHighlightTest {

    private lateinit var ctx: Context

    private lateinit var controller: NavHostController

    @Navigator.Name("test")
    private class TestNavigator : Navigator<NavDestination>() {
        override fun createDestination() = NavDestination(this)
    }

    // a page's preferences without hosting its fragment: only its screen and where it scrolled are read
    private class Page(var screen: PreferenceScreen) : PreferenceFragmentCompat() {
        var scrolledTo: Preference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) = Unit

        override fun getPreferenceScreen(): PreferenceScreen = screen

        override fun scrollToPreference(preference: Preference) {
            scrolledTo = preference
        }
    }

    // a page hosted for the public overloads, which find the NavController by its view; any fragment
    // will do for isWaiting, and a preference one would need androidx.preference's resources
    class Hosted : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            View(inflater.context)
    }

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        controller = NavHostController(ctx)
        // where an entry's saved state is kept: NavHostFragment gives it the activity's
        controller.setViewModelStore(ViewModelStore())
        val navigator = TestNavigator()
        controller.navigatorProvider.addNavigator(navigator)
        controller.setGraph(
            NavGraph(controller.navigatorProvider.getNavigator(NavGraphNavigator::class.java)).apply {
                id = GRAPH
                addDestination(navigator.createDestination().apply { id = SEARCH })
                addDestination(navigator.createDestination().apply { id = RESULT })
                addDestination(navigator.createDestination().apply { id = OTHER })
                setStartDestination(SEARCH)
            },
            null
        )
    }

    /** [page] opened, and its entry, as SettingsSearchFragment.open finds it after navigating. */
    private fun open(page: Int): NavBackStackEntry {
        controller.navigate(page)
        return controller.currentBackStackEntry!!
    }

    // one title for all: only the key tells them apart
    private fun screen(vararg keys: String) = PreferenceManager(ctx).createPreferenceScreen(ctx).apply {
        keys.forEach { k ->
            addPreference(Preference(ctx).apply {
                isPersistent = false
                key = k
                title = "Title"
            })
        }
    }

    @Test
    fun aPageOpenedAfterTheResultsOwnIsNotPointedAt() {
        val result = open(RESULT)
        SearchHighlight.point(result, "wanted")
        SearchHighlight.showIn(Page(screen("other")), result)
        val next = Page(screen("wanted"))
        SearchHighlight.showIn(next, open(OTHER))
        assertNull(next.scrolledTo)
    }

    @Test
    fun aHostedPageAsksTheEntryItsNavControllerIsAt() {
        val page = Hosted()
        Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            .supportFragmentManager.beginTransaction().add(android.R.id.content, page).commitNow()
        // as NavHostFragment would have it for findNavController
        Navigation.setViewNavController(page.requireView(), controller)
        SearchHighlight.point(open(RESULT), "wanted")
        assertTrue(SearchHighlight.isWaiting(page))
        // the result's entry still holds the key, under the page opened after it
        open(OTHER)
        assertFalse(SearchHighlight.isWaiting(page))
        controller.popBackStack()
        assertTrue(SearchHighlight.isWaiting(page))
        // posted from its view after it left: no NavController to ask, nothing pointed at
        val gone = Page(screen("wanted"))
        SearchHighlight.showIn(gone)
        assertNull(gone.scrolledTo)
    }

    @Test
    fun theResultsPageFindsTheSettingOnceItsPreferencesArrive() {
        val result = open(RESULT)
        SearchHighlight.point(result, "wanted")
        val page = Page(screen())
        SearchHighlight.showIn(page, result)
        page.screen = screen("other", "wanted")
        SearchHighlight.showIn(page, result)
        assertEquals("wanted", page.scrolledTo?.key)
    }

    @Test
    fun aSettingIsWaitedForTillItsPageFindsIt() {
        val result = open(RESULT)
        // a result that is a page, not a setting on one
        SearchHighlight.point(result, null)
        assertFalse(SearchHighlight.isWaiting(result))
        SearchHighlight.point(result, "wanted")
        assertTrue(SearchHighlight.isWaiting(result))
        SearchHighlight.showIn(Page(screen("other")), result)
        assertTrue(SearchHighlight.isWaiting(result))
        SearchHighlight.showIn(Page(screen("wanted")), result)
        assertFalse(SearchHighlight.isWaiting(result))
    }

    @Test
    fun aSettingNotFoundWithinAFewSecondsIsNoLongerWaitedFor() {
        val result = open(RESULT)
        SearchHighlight.point(result, "wanted")
        ShadowSystemClock.advanceBy(6, TimeUnit.SECONDS)
        assertFalse(SearchHighlight.isWaiting(result))
        val page = Page(screen("wanted"))
        SearchHighlight.showIn(page, result)
        assertNull(page.scrolledTo)
    }

    private companion object {
        const val GRAPH = 1
        const val SEARCH = 2
        const val RESULT = 3
        const val OTHER = 4
    }

    // a page opened from a search stayed at its top: its list had no adapter yet when it was scrolled
    @Test
    fun aListIsScrolledOnlyOnceItHasItsAdapter() {
        val list = RecyclerView(ctx)
        var scrolled = 0
        SearchHighlight.whenListed(list) { scrolled++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(0, scrolled)
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(View(ctx)) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            override fun getItemCount() = 0
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(1, scrolled)
        // one with no list yet goes at once (scrollToPreference waits for the list itself)
        SearchHighlight.whenListed(null) { scrolled++ }
        assertEquals(2, scrolled)
    }
}
