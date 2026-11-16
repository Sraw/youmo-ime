/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        SearchHighlight.point(result, "pref:wanted")
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
        SearchHighlight.point(open(RESULT), "pref:wanted")
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
        SearchHighlight.point(result, "pref:wanted")
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
        SearchHighlight.point(result, "pref:wanted")
        assertTrue(SearchHighlight.isWaiting(result))
        SearchHighlight.showIn(Page(screen("other")), result)
        assertTrue(SearchHighlight.isWaiting(result))
        SearchHighlight.showIn(Page(screen("wanted")), result)
        assertFalse(SearchHighlight.isWaiting(result))
    }

    // kept whole (061 §4.1): an id not a preference's, which no preference page has, pointed at nothing and let go;
    // a control's (view:) left for the page drawn by hand around the preferences (外观's tabs), which says when it is ready
    @Test
    fun theIdIsKeptWholeAndOnlyAPreferencesIsPointedAt() {
        val result = open(RESULT)
        SearchHighlight.point(result, "pref:wanted", "pref:keyboard")
        assertEquals("pref:wanted" to "pref:keyboard", SearchHighlight.pointedAt(result))
        SearchHighlight.point(result, "page:wanted")
        val page = Page(screen("wanted"))
        SearchHighlight.showIn(page, result)
        assertNull(page.scrolledTo)
        assertFalse(SearchHighlight.isWaiting(result))
        SearchHighlight.point(result, "view:wanted")
        SearchHighlight.showIn(page, result)
        assertNull(page.scrolledTo)
        assertTrue(SearchHighlight.isWaiting(result))
    }

    @Test
    fun aSettingNotFoundWithinAFewSecondsIsNoLongerWaitedFor() {
        val result = open(RESULT)
        SearchHighlight.point(result, "pref:wanted")
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

    // a setting in a hidden section (a keyboard not chosen): the row that shows it is pointed at, not the page's top
    @Test
    fun aHiddenSettingPointsAtTheRowThatShowsIt() {
        val result = open(RESULT)
        SearchHighlight.point(result, "pref:wanted", "pref:keyboard")
        val page = Page(screen("keyboard", "wanted").apply { findPreference<Preference>("wanted")!!.isVisible = false })
        SearchHighlight.showIn(page, result)
        assertEquals("keyboard", page.scrolledTo?.key)
        // as the pages hide one: the section's group hidden, the setting in it still visible itself
        SearchHighlight.point(result, "pref:wanted", "pref:keyboard")
        val grouped = Page(screen("keyboard").apply {
            val section = PreferenceCategory(ctx).apply { key = "section"; isPersistent = false }
            addPreference(section)
            section.addPreference(Preference(ctx).apply { isPersistent = false; key = "wanted"; title = "Title" })
            section.isVisible = false
        })
        SearchHighlight.showIn(grouped, result)
        assertEquals("keyboard", grouped.scrolledTo?.key)
    }

    // a page drawn by hand: what it was asked to reveal, and the control it gives, if any
    class Anchored(val control: View? = null) : Fragment(), SearchAnchors {
        val revealed = mutableListOf<String>()

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            View(inflater.context)

        override fun reveal(id: String, found: (View) -> Unit) {
            revealed += id
            control?.let(found)
        }
    }

    // dev/design/061-settings-search.md §4.7: asked once the page says it is ready, then forgotten
    @Test
    fun aControlIsRevealedOnceThePageIsReadyAndOnlyOnce() {
        val result = open(RESULT)
        val page = Anchored(null)
        SearchHighlight.point(result, "view:keys/flip")
        SearchHighlight.ready(page, result)
        assertEquals(listOf("view:keys/flip"), page.revealed)
        assertFalse(SearchHighlight.isWaiting(result))
        SearchHighlight.ready(page, result)
        assertEquals(1, page.revealed.size)
    }

    // a preference's is the preference page's to point at: a page drawn by hand leaves it
    @Test
    fun aPageDrawnByHandLeavesAPreferencesId() {
        val result = open(RESULT)
        val page = Anchored(null)
        SearchHighlight.point(result, "pref:wanted")
        SearchHighlight.ready(page, result)
        assertTrue(page.revealed.isEmpty())
        assertTrue(SearchHighlight.isWaiting(result))
    }

    // 生僻字屏蔽 reads the whole table from the engine first, cold more than the 5 s a preference is waited for
    @Test
    fun aControlIsWaitedForUpToThirtySeconds() {
        val result = open(RESULT)
        val page = Anchored(null)
        SearchHighlight.point(result, "view:rare/group/L3")
        ShadowSystemClock.advanceBy(20, TimeUnit.SECONDS)
        assertEquals("view:rare/group/L3", SearchHighlight.waitingFor(Hosted().also { host(it, result) }))
        SearchHighlight.ready(page, result)
        assertEquals(listOf("view:rare/group/L3"), page.revealed)
        SearchHighlight.point(result, "view:rare/group/L3")
        ShadowSystemClock.advanceBy(31, TimeUnit.SECONDS)
        SearchHighlight.ready(page, result)
        assertEquals(1, page.revealed.size)
        assertFalse(SearchHighlight.isWaiting(result))
    }

    // the control gone (a button moved into ⋮): nothing flashed, the page left at its top, no crash
    @Test
    fun aControlThePageNoLongerHasLeavesThePageAtItsTop() {
        val result = open(RESULT)
        val page = Anchored(null)
        SearchHighlight.point(result, "view:keys/gone")
        SearchHighlight.ready(page, result)
        assertEquals(listOf("view:keys/gone"), page.revealed)
        assertFalse(SearchHighlight.isWaiting(result))
    }

    // a control with no ripple of its own, or disabled (a ripple shows nothing then): a veil of the theme's touch colour
    // drawn over it while it is pressed, a moment after the page is there (it fades in), gone with the press
    @Test
    fun aControlFlashesUnderAVeilForAMoment() {
        fun drawn(view: View) = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }.getPixel(50, 20)
        val plain = View(ctx)
        val disabled = View(ctx).apply {
            foreground = RippleDrawable(ColorStateList.valueOf(Color.BLACK), null, null)
            isEnabled = false
        }
        for (control in listOf(plain, disabled)) {
            control.layout(0, 0, 100, 40)
            val before = drawn(control)
            val result = open(RESULT)
            SearchHighlight.point(result, "view:keys/flip")
            SearchHighlight.ready(Anchored(control), result)
            // pressed once the page has faded in
            assertFalse(control.isPressed)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertTrue(control.isPressed)
            assertNotEquals("$control: veiled", before, drawn(control))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(control.isPressed)
            assertEquals("$control: unveiled", before, drawn(control))
        }
    }

    // 外观's card shows its row's state (the name under it pressed with it): flashed through the row, its ripple pressed
    @Test
    fun aControlShowingItsParentsStateFlashesThroughIt() {
        val card = View(ctx).apply {
            foreground = RippleDrawable(ColorStateList.valueOf(Color.BLACK), null, ColorDrawable(Color.WHITE))
            isDuplicateParentStateEnabled = true
        }
        val row = FrameLayout(ctx).apply { addView(card, 100, 40); measure(100, 40); layout(0, 0, 100, 40) }
        val result = open(RESULT)
        SearchHighlight.point(result, "view:theme/pixel")
        SearchHighlight.ready(Anchored(card), result)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue(row.isPressed)
        assertTrue(android.R.attr.state_pressed in card.drawableState)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(row.isPressed)
        assertFalse(android.R.attr.state_pressed in card.drawableState)
    }

    // ready said by a page another was opened over (its read done late): the page on top is not pointed at, the page
    // itself is once it is back on top and says it again
    @Test
    fun aPageReadyUnderAnotherPointsAtNothingThere() {
        val result = open(RESULT)
        val control = View(ctx).apply { layout(0, 0, 100, 40) }
        val page = Anchored(control)
        SearchHighlight.point(result, "view:keys/flip")
        host(page, result, tag = result.id)
        val other = open(OTHER)
        SearchHighlight.point(other, "view:keys/multi")
        SearchHighlight.ready(page)
        assertEquals(emptyList<String>(), page.revealed)
        assertTrue(SearchHighlight.isWaiting(other))
        controller.popBackStack()
        SearchHighlight.ready(page)
        assertEquals(listOf("view:keys/flip"), page.revealed)
    }

    // the public overloads find the NavController by the fragment's view; tagged as FragmentNavigator tags a destination's
    private fun host(fragment: Fragment, entry: NavBackStackEntry, tag: String? = null) {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment, tag).commitNow()
        Navigation.setViewNavController(fragment.requireView(), controller)
        assertEquals(entry, controller.currentBackStackEntry)
    }
}
