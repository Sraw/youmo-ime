/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Swipe notes' scenes for a settings page, built once fcitx tells the enabled input methods, each on its own thread
 * (measurers are not shared), results posted to the main thread.
 */
internal class SwipeFatesLoader(
    private val fragment: Fragment,
    private val imes: suspend () -> Pair<List<InputMethodEntry>, Map<String, String?>>,
) {
    private var page: SwipeFates.Page? = null
    private var asked = false
    private val waiting = mutableListOf<(SwipeFates.Page) -> Unit>()

    private val thread = lazy { Executors.newSingleThreadExecutor() }
    private val mainHandler = Handler(Looper.getMainLooper())

    // let go with the view, queued ones too (a job checks its generation first)
    private val running = mutableListOf<Future<*>>()
    private val generation = AtomicInteger()

    // each asker's last request: an earlier queued one is skipped (a 多选 tap or typing rebuilds over and over)
    private val latest = HashMap<String, AtomicInteger>()

    private fun withPage(then: (SwipeFates.Page) -> Unit) {
        page?.let { return then(it) }
        // an editor's late change after the page's view went
        if (fragment.view == null) return
        waiting += then
        if (asked) return
        asked = true
        // a view recreated before fcitx answers asks again, the first ask gone with its view
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val (on, labels) = try {
                imes()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // no input methods known: the full keyboard's alone
                Timber.w(e, "swipe fates: the input methods on")
                emptyList<InputMethodEntry>() to emptyMap()
            }
            val ctx = fragment.context ?: return@launch
            if (fragment.view == null) return@launch
            val made = SwipeFates.Page(ctx, on, labels)
            page = made
            waiting.toList().also { waiting.clear() }.forEach { it(made) }
        }
    }

    fun request(asker: String, slots: SwipeSlots, current: () -> Boolean, done: (List<SwipeFates.Seen>) -> Unit) = withPage { page ->
        val jobs = page.jobs(slots)
        val gen = generation.get()
        val counter = latest.getOrPut(asker) { AtomicInteger() }
        val ask = counter.incrementAndGet()
        running.removeAll { it.isDone }
        running += thread.value.submit {
            if (gen != generation.get() || ask != counter.get()) return@submit
            val seen = jobs.map(SwipeFates::made)
            mainHandler.post { if (current() && fragment.view != null) done(seen) }
        }
    }

    /** Tests: the notes then come with the main looper's next idle. */
    fun await() {
        if (thread.isInitialized()) thread.value.submit {}.get(AWAIT_SECONDS, TimeUnit.SECONDS)
    }

    /** Queued work dropped; the keyboards (which listen to settings while held) let go. */
    fun releaseView() {
        generation.incrementAndGet()
        running.forEach { it.cancel(false) }
        running.clear()
        mainHandler.removeCallbacksAndMessages(null)
        page?.release()
        page = null
        asked = false
        waiting.clear()
    }

    fun shutdown() {
        if (thread.isInitialized()) thread.value.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val AWAIT_SECONDS = 10L
    }
}
