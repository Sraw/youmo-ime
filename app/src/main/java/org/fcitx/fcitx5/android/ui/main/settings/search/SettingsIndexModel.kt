/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import org.fcitx.fcitx5.android.ui.search.SettingsSearch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * The search page's index, built off the main thread: 84 ms the first time on the 6T, over a frame
 * (061-settings-search.md §4.11). Kept over a rotation, built again for another language; dropped,
 * and a build under way cancelled, with the page.
 */
class SettingsIndexModel : ViewModel() {

    private var built: Deferred<SettingsSearch<SettingsIndex.Target>>? = null

    // the languages it was built in: a rotation keeps it, a language changed meanwhile does not
    private var languages: String? = null

    /**
     * The index for [context]'s language: the one built or being built, or a build started now. Off the main
     * thread it reads resources and SharedPreferences (both thread safe; some lists show what is stored, the
     * keyboard style's also the window's size) and writes nothing: the preferences it makes are never attached
     * to a page, store through DiscardingDataStore, and Levels and Choice write nothing as they open. Of View
     * only the main looper's Handler a PreferenceGroup makes, which is safe from any thread.
     */
    fun index(context: Context): Deferred<SettingsSearch<SettingsIndex.Target>> {
        val tags = context.resources.configuration.locales.toLanguageTags()
        built?.takeIf { tags == languages && !it.isCancelled }?.let { return it }
        built?.cancel()
        languages = tags
        return viewModelScope.async(Hooks.dispatcher) {
            val start = SystemClock.elapsedRealtime()
            Hooks.builds.incrementAndGet()
            Hooks.builtOn = Thread.currentThread()
            SettingsSearch(SettingsIndex.build(context) { ensureActive() }).also {
                Timber.d("settings index built in %d ms on %s", SystemClock.elapsedRealtime() - start, Thread.currentThread().name)
            }
        }.also {
            built = it
            Hooks.latest = it
        }
    }

    override fun onCleared() {
        // not kept for a test past the page
        if (Hooks.latest === built) Hooks.latest = null
    }

    /** Where the index is built, and what tests read of it: builds started, the last one's thread, the last asked for. */
    @VisibleForTesting
    internal object Hooks {
        var dispatcher: CoroutineDispatcher = Dispatchers.Default

        val builds = AtomicInteger()

        @Volatile
        var builtOn: Thread? = null

        @Volatile
        var latest: Deferred<*>? = null
    }
}
