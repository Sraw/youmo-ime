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
 * A settings page's swipe notes' scenes ([SwipeFates]): the page of keyboards made once the input methods on are known
 * ([imes], asked of fcitx), each asked set of slots made on one thread of its own (its measurer no other's), the result
 * told on the main thread. Its lifecycle the [fragment]'s: [releaseView] as its view goes, [shutdown] as it does.
 */
internal class SwipeFatesLoader(
    private val fragment: Fragment,
    private val imes: suspend () -> Pair<List<InputMethodEntry>, Map<String, String?>>,
) {
    // the keyboards the notes are of and what waits for them
    private var page: SwipeFates.Page? = null
    private var asked = false
    private val waiting = mutableListOf<(SwipeFates.Page) -> Unit>()

    private val thread = lazy { Executors.newSingleThreadExecutor() }
    private val mainHandler = Handler(Looper.getMainLooper())

    // the scenes being made and waiting to be: let go with the view, each queued one as well (a job checks its
    // generation first, so one already taken off the queue does no work for a view gone)
    private val running = mutableListOf<Future<*>>()
    private val generation = AtomicInteger()

    // each asker's ([request]'s `asker`: the page, the editor) last ask: an earlier one still queued is superseded and
    // skipped, its scenes no longer anyone's (a 多选 tap or typing in a slot's field rebuilds over and over)
    private val latest = HashMap<String, AtomicInteger>()

    private fun withPage(then: (SwipeFates.Page) -> Unit) {
        page?.let { return then(it) }
        // no view (an editor's late change after the page's view went): no page of keyboards for no one
        if (fragment.view == null) return
        waiting += then
        if (asked) return
        asked = true
        // the view's scope: a view made again before fcitx answers asks again, the first ask gone with its view
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
            // the view gone while fcitx answered: a page of keyboards for no one
            if (fragment.view == null) return@launch
            val made = SwipeFates.Page(ctx, on, labels)
            page = made
            waiting.toList().also { waiting.clear() }.forEach { it(made) }
        }
    }

    /** [slots]' scenes made on the notes' thread for [asker], [done] told on the main one unless [current] no longer. */
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

    /** Every scene asked so far made (tests: the notes then come with the main looper's next idle). */
    fun await() {
        if (thread.isInitialized()) thread.value.submit {}.get(AWAIT_SECONDS, TimeUnit.SECONDS)
    }

    /** The view gone: what is queued dropped, the keyboards (they listen to the settings while held) let go. */
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

    /** The fragment gone: the thread with it. */
    fun shutdown() {
        if (thread.isInitialized()) thread.value.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        // a test's wait for the thread, at most
        const val AWAIT_SECONDS = 10L
    }
}
