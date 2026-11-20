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

/** Built off the main thread (over a frame the first time); kept over a rotation, rebuilt for another language. */
class SettingsIndexModel : ViewModel() {

    private var built: Deferred<SettingsSearch<SettingsIndex.Target>>? = null

    // a rotation keeps it, a language changed meanwhile does not
    private var languages: String? = null

    /**
     * Off the main thread it reads resources and SharedPreferences (thread safe) and writes nothing: its preferences are
     * never attached, store through DiscardingDataStore, and Levels and Choice write nothing as they open. A
     * PreferenceGroup's main-looper Handler is safe from any thread.
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

    /** Tests read builds started, the last one's thread, the last asked for. */
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
