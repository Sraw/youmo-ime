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

/** The key lives in the result's own back-stack entry for a few seconds at most, so no other page is pointed at it. */
object SearchHighlight {

    /** [via]: pointed at while [id] is hidden (a section only a keyboard choice shows). */
    fun point(entry: NavBackStackEntry, id: String?, via: String? = null) {
        if (id == null) return
        entry.savedStateHandle[KEY] = id
        entry.savedStateHandle[VIA] = via
        entry.savedStateHandle[AT] = SystemClock.uptimeMillis()
    }

    fun isWaiting(fragment: Fragment): Boolean =
        fragment.findNavController().currentBackStackEntry?.let { isWaiting(it) } == true

    internal fun isWaiting(entry: NavBackStackEntry): Boolean {
        val state = entry.savedStateHandle
        return state.contains(KEY) && isFresh(state)
    }

    fun waitingFor(fragment: Fragment): String? =
        fragment.findNavController().currentBackStackEntry?.savedStateHandle
            ?.takeIf { isFresh(it) }?.get<String>(KEY)?.takeIf { it.startsWith(VIEW) }

    fun ready(fragment: Fragment) {
        Hooks.readied = fragment
        if (!fragment.isAdded) return
        // the page's own entry, not whichever is on top: a page ready after another opened over it must not point that one
        fragment.findNavController().currentBackStackEntry?.takeIf { it.id == fragment.tag }?.let { ready(fragment, it) }
    }

    internal fun ready(fragment: Fragment, entry: NavBackStackEntry) {
        // a preference page whose rows come after a read (ReadyLater): its rows are there now
        if (fragment is PreferenceFragmentCompat) return showIn(fragment, entry)
        val state = entry.savedStateHandle
        val id = state.get<String>(KEY)?.takeIf { it.startsWith(VIEW) } ?: return
        val fresh = isFresh(state, late = true)
        forget(state)
        if (!fresh) return
        (fragment as? SearchAnchors)?.reveal(id) { flash(it, id) }
    }

    /** After [SETTLE]: a just-opened page fades in over 150 ms. Logged as pressed, with [id]: a device UAT shoots on that line. */
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
        // a view showing its parent's state (外观's card) is pressed through that parent
        val pressed = generateSequence(view) { v -> (v.parent as? View)?.takeIf { v.isDuplicateParentStateEnabled } }.last()
        pressed.isPressed = true
        Timber.i("search: flashed %s", id)
        // the main thread's, not the view's: a view's own post waits for attach, and a page left would stay pressed
        main.postDelayed({
            pressed.isPressed = false
            veil?.let(view.overlay::remove)
        }, FLASH)
    }

    /** Weak: written in release too, they would keep a left page (and its Activity) until the next. */
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

    internal fun pointedAt(entry: NavBackStackEntry): Pair<String?, String?> =
        entry.savedStateHandle.get<String>(KEY) to entry.savedStateHandle.get<String>(VIA)

    fun showIn(fragment: PreferenceFragmentCompat) {
        // posted from its view, so it may have left its FragmentManager, and with it the NavController
        if (!fragment.isAdded) return
        fragment.findNavController().currentBackStackEntry?.let { showIn(fragment, it) }
    }

    internal fun showIn(fragment: PreferenceFragmentCompat, entry: NavBackStackEntry) {
        val state = entry.savedStateHandle
        val id = state.get<String>(KEY) ?: return
        // a control's: the hand-drawn page around this one points at it
        if (id.startsWith(VIEW)) return
        val key = prefKey(id)
        if (!isFresh(state, late = fragment is ReadyLater) || key == null) {
            forget(state)
            return
        }
        val screen = fragment.preferenceScreen ?: return
        val wanted = screen.findPreference<Preference>(key) ?: return
        // hidden, it or a group it is in: nowhere to scroll
        val shown = generateSequence(wanted) { it.parent }.all { it.isVisible }
        val pref = if (shown) wanted else prefKey(state.get<String>(VIA))?.let { screen.findPreference<Preference>(it) } ?: wanted
        forget(state)
        val list = fragment.listView
        whenListed(list) {
            fragment.scrollToPreference(pref)
            // the flash counts from the scroll: an adapter late by a few tries had the page scrolled to a row that never flashed
            list ?: return@whenListed
            main.postDelayed({
                val position = (list.adapter as? PreferenceGroup.PreferencePositionCallback)?.getPreferenceAdapterPosition(pref) ?: return@postDelayed
                val row = list.findViewHolderForAdapterPosition(position)?.itemView ?: return@postDelayed
                row.isPressed = true
                Timber.i("search: flashed %s", PREF + pref.key)
                row.postDelayed({ row.isPressed = false }, FLASH)
            }, SETTLE)
        }
    }

    /** setPreferenceScreen posts the adapter bind, and scrollToPreference before it does nothing: retried for a second. */
    internal fun whenListed(list: RecyclerView?, tries: Int = TRIES, scroll: () -> Unit) {
        if (list == null || list.adapter != null) scroll()
        else if (tries > 0) main.postDelayed({ whenListed(list, tries - 1, scroll) }, RETRY)
    }

    // a view's own posts wait for it to be attached
    private val main by lazy { Handler(Looper.getMainLooper()) }

    // uptime goes on while the process is gone. A page that reads first gets longer: some take past 5 s on a cold start
    private fun isFresh(state: SavedStateHandle, late: Boolean = false): Boolean {
        val wait = if (late || state.get<String>(KEY)?.startsWith(VIEW) == true) LATE_WAIT else WAIT
        return SystemClock.uptimeMillis() - (state.get<Long>(AT) ?: 0L) <= wait
    }

    private fun forget(state: SavedStateHandle) {
        state.remove<String>(KEY)
        state.remove<String>(VIA)
        state.remove<Long>(AT)
    }

    const val PREF = "pref:"

    const val VIEW = "view:"

    private fun prefKey(id: String?): String? = id?.takeIf { it.startsWith(PREF) }?.substring(PREF.length)

    // a name no route has
    private const val KEY = "search_highlight_key"
    private const val VIA = "search_highlight_via"
    private const val AT = "search_highlight_at"

    private const val WAIT = 5000L
    private const val LATE_WAIT = 30_000L
    private const val VEIL = 31
    private const val SETTLE = 250L
    private const val FLASH = 600L
    private const val RETRY = 50L
    private const val TRIES = 20
}
