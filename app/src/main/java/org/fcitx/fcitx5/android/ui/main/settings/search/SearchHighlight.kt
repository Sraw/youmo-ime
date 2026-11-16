/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import org.fcitx.fcitx5.android.utils.styledColor
import timber.log.Timber

/**
 * The setting a search result opens its page at: the page's fragment, once its preferences are
 * there (an input method's come after fcitx answers), scrolls to it and flashes it. The setting's
 * key is kept in the saved state of the result's own entry on the back stack, for a few seconds at
 * most: no other page is pointed at it, and the result's page is not pointed at it long after.
 */
object SearchHighlight {

    /**
     * Has [entry], the page a search result has just opened, point at [id], if any: a preference's
     * (pref:<key>), at [via] instead while that one is hidden (a section only a keyboard choice shows); or a
     * control's of a page drawn by hand (view:), which that page reveals once it is ready ([ready]).
     */
    fun point(entry: NavBackStackEntry, id: String?, via: String? = null) {
        if (id == null) return
        entry.savedStateHandle[KEY] = id
        entry.savedStateHandle[VIA] = via
        entry.savedStateHandle[AT] = SystemClock.uptimeMillis()
    }

    /** Whether [fragment]'s page is still to point at a setting a search result opened it at. */
    fun isWaiting(fragment: Fragment): Boolean =
        fragment.findNavController().currentBackStackEntry?.let { isWaiting(it) } == true

    internal fun isWaiting(entry: NavBackStackEntry): Boolean {
        val state = entry.savedStateHandle
        return state.contains(KEY) && isFresh(state)
    }

    /** The control (view:) [fragment]'s page is still to point at, if any: a page with more than one view switches to it first. */
    fun waitingFor(fragment: Fragment): String? =
        fragment.findNavController().currentBackStackEntry?.savedStateHandle
            ?.takeIf { isFresh(it) }?.get<String>(KEY)?.takeIf { it.startsWith(VIEW) }

    /**
     * Said by a page drawn by hand ([SearchAnchors]) once what it shows is there (read, its cards made): the
     * control a search result opened it at, if any and not gone stale, revealed and flashed. Once: forgotten then.
     */
    fun ready(fragment: Fragment) {
        Hooks.readied = fragment
        if (!fragment.isAdded) return
        // the page's own entry, not whichever is on top: a page ready only after another was opened over it is not
        // to point that one at anything (FragmentNavigator tags a destination's fragment with its entry's id)
        fragment.findNavController().currentBackStackEntry?.takeIf { it.id == fragment.tag }?.let { ready(fragment, it) }
    }

    internal fun ready(fragment: Fragment, entry: NavBackStackEntry) {
        // a page of preferences whose rows come after a read (ReadyLater): its rows are there now
        if (fragment is PreferenceFragmentCompat) return showIn(fragment, entry)
        val state = entry.savedStateHandle
        val id = state.get<String>(KEY)?.takeIf { it.startsWith(VIEW) } ?: return
        val fresh = isFresh(state, late = true)
        forget(state)
        if (!fresh) return
        (fragment as? SearchAnchors)?.reveal(id) { flash(it, id) }
    }

    /**
     * [view] scrolled into sight and flashed: its own ripple as if touched, else (or disabled, when a ripple shows
     * nothing) a veil of the theme's touch colour. After [SETTLE], as a row is: a page just opened fades in over
     * 150 ms (nav_default_enter_anim), and a press at its first layout was over before it was seen (on a device,
     * 外观's Pixel). Logged as it is pressed, [id] with it: a device's UAT shoots the screen on that line.
     */
    internal fun flash(view: View, id: String? = null) = view.doOnLayout {
        it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), false)
        main.postDelayed({ press(it, id) }, SETTLE)
    }

    private fun press(view: View, id: String?) {
        Hooks.flashed = view
        val ripple = view.isEnabled && (view.background is RippleDrawable || view.foreground is RippleDrawable)
        val veil = if (ripple) null else ColorDrawable(ColorUtils.setAlphaComponent(view.context.styledColor(android.R.attr.colorControlHighlight), VEIL)).apply {
            setBounds(0, 0, view.width, view.height)
            view.overlay.add(this)
        }
        // a view that shows its parent's state (外观's card, its name under it) is pressed through that parent: its
        // own press is not drawn (the Pixel card's was logged and never seen)
        val pressed = generateSequence(view) { v -> (v.parent as? View)?.takeIf { v.isDuplicateParentStateEnabled } }.last()
        pressed.isPressed = true
        Timber.i("search: flashed %s", id)
        // the main thread's, not the view's: a view's own post waits for it to be attached, and a page left
        // within the moment would have kept it pressed
        main.postDelayed({
            pressed.isPressed = false
            veil?.let(view.overlay::remove)
        }, FLASH)
    }

    /**
     * What tests read: the last page that said it was ready, the last control flashed. Weakly: written in the
     * release build too, they would keep a page left (and its Activity) until the next one.
     */
    @VisibleForTesting
    internal object Hooks {
        @Volatile
        private var readiedRef: WeakReference<Fragment>? = null

        @Volatile
        private var flashedRef: WeakReference<View>? = null

        var readied: Fragment?
            get() = readiedRef?.get()
            set(value) {
                readiedRef = value?.let(::WeakReference)
            }

        var flashed: View?
            get() = flashedRef?.get()
            set(value) {
                flashedRef = value?.let(::WeakReference)
            }
    }

    /** The ids [entry]'s page is to point at and the one it goes through, as [point] left them. */
    internal fun pointedAt(entry: NavBackStackEntry): Pair<String?, String?> =
        entry.savedStateHandle.get<String>(KEY) to entry.savedStateHandle.get<String>(VIA)

    /** Points [fragment] at the setting its page is to point at, if it has it. */
    fun showIn(fragment: PreferenceFragmentCompat) {
        // posted from its view, so it may have left its FragmentManager, and with it the NavController
        if (!fragment.isAdded) return
        fragment.findNavController().currentBackStackEntry?.let { showIn(fragment, it) }
    }

    internal fun showIn(fragment: PreferenceFragmentCompat, entry: NavBackStackEntry) {
        val state = entry.savedStateHandle
        val id = state.get<String>(KEY) ?: return
        // a control's: the page drawn by hand around this one points at it ([ready]; 外观's tabs)
        if (id.startsWith(VIEW)) return
        val key = prefKey(id)
        // gone stale, or no preference's: nothing a preference page can point at
        if (!isFresh(state, late = fragment is ReadyLater) || key == null) {
            forget(state)
            return
        }
        val screen = fragment.preferenceScreen ?: return
        val wanted = screen.findPreference<Preference>(key) ?: return
        // hidden, it or a group it is in: nowhere to scroll to, the page left at its top
        val shown = generateSequence(wanted) { it.parent }.all { it.isVisible }
        val pref = if (shown) wanted else prefKey(state.get<String>(VIA))?.let { screen.findPreference<Preference>(it) } ?: wanted
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
                Timber.i("search: flashed %s", PREF + pref.key)
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

    // uptime goes on while the process is gone: a page brought back long after points at nothing. A page that reads
    // first ([late]: drawn by hand, or ReadyLater) longer: 生僻字屏蔽 reads the whole table from the engine, 词库与短语
    // counts every dictionary's words, past 5 s on a cold start
    private fun isFresh(state: SavedStateHandle, late: Boolean = false): Boolean {
        val wait = if (late || state.get<String>(KEY)?.startsWith(VIEW) == true) LATE_WAIT else WAIT
        return SystemClock.uptimeMillis() - (state.get<Long>(AT) ?: 0L) <= wait
    }

    private fun forget(state: SavedStateHandle) {
        state.remove<String>(KEY)
        state.remove<String>(VIA)
        state.remove<Long>(AT)
    }

    /** A preference's id's kind: what follows is its key. */
    const val PREF = "pref:"

    /** A control's id's kind, on a page drawn by hand: the View's tag. */
    const val VIEW = "view:"

    private fun prefKey(id: String?): String? = id?.takeIf { it.startsWith(PREF) }?.substring(PREF.length)

    // beside the route's own arguments in the entry's saved state: names no route has
    private const val KEY = "search_highlight_key"
    private const val VIA = "search_highlight_via"
    private const val AT = "search_highlight_at"

    private const val WAIT = 5000L
    private const val LATE_WAIT = 30_000L
    // 12%, as the design's veil over a control with no ripple of its own (dev/design/061-settings-search.md §4.7)
    private const val VEIL = 31
    private const val SETTLE = 250L
    private const val FLASH = 600L
    private const val RETRY = 50L
    private const val TRIES = 20
}
