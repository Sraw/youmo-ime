/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast
import java.util.concurrent.ConcurrentHashMap

/** What the engine says, from any thread: on the keyboard if shown (Android 13 drops an IME's toasts), else a toast. The input method sets [sayer], so core does not reach into input. */
object EngineNotices {

    fun interface Sayer {
        /** False with no keyboard shown; the toast in its place is [post]'s call. */
        fun say(message: String, steady: Boolean): Boolean

        /** [ms] null is its kind's time: a sayer that times none says it as any. */
        fun say(message: String, steady: Boolean, ms: Long?): Boolean = say(message, steady)
    }

    @Volatile
    var sayer: Sayer? = null

    // said before any keyboard showed (several at host start): said there when it next shows. Main thread only.
    private val unseen = LinkedHashMap<Int, Unseen>()

    private class Unseen(val text: String, val steady: Boolean, val seen: () -> Unit, val ms: Long? = null)

    /** Not put away by the next key typed. */
    fun failed(@StringRes id: Int) = post(steady = true) { appContext.getString(id) }

    /** A failure seen even if no key is typed: if no keyboard shows at host start, said when one next does. */
    fun failedUntilSeen(@StringRes id: Int) = post(steady = true, unsaid = untilSeen(id, steady = true)) { appContext.getString(id) }

    /**
     * One-time news, not a failure: no toast, which would come before the keyboard it is about. [seen] runs on main
     * once said, so one never seen is told again; told again while on its way does nothing. [ms] null: NoticeState.SAID_MS.
     */
    fun toldUntilSeen(@StringRes id: Int, vararg args: Any, ms: Long? = null, seen: () -> Unit = {}) {
        if (!telling.add(id)) return
        val done = {
            telling.remove(id)
            seen()
        }
        post(steady = false, ms = ms, toast = { null }, said = done, unsaid = untilSeen(id, steady = false, done, ms)) { appContext.getString(id, *args) }
    }

    private val telling: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    private val saidOnce: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** [failedUntilSeen], once a process: for what several places may each find. */
    fun failedOnce(@StringRes id: Int) {
        if (saidOnce.add(id)) failedUntilSeen(id)
    }

    /** [toldUntilSeen], once a process: news that may be found again and again. */
    fun toldOnce(@StringRes id: Int) {
        if (saidOnce.add(id)) toldUntilSeen(id)
    }

    // a test's process outlives the run it stands for. Main thread.
    internal fun forgetToldOnce() {
        saidOnce.clear()
        telling.clear()
        unseen.clear()
    }

    fun keyboardShown() = ContextCompat.getMainExecutor(appContext).execute {
        if (unseen.isEmpty()) return@execute
        val pending = unseen.toMap()
        val steady = pending.values.any { it.steady }
        val ms = pending.values.mapNotNull { it.ms }.maxOrNull()
        unseen.clear()
        // one notice on the keyboard, so joined: said one after another, each would put the last away. Not said there,
        // each waits again as itself (joined again it would say each twice); the toast carries failures only.
        val failures = pending.values.filter { it.steady }.joinToString("\n") { it.text }
        post(steady, ms, toast = { failures.ifEmpty { null } }, said = { pending.values.forEach { it.seen() } }, unsaid = { pending.forEach { (id, u) -> unseen.putIfAbsent(id, u) } }) {
            pending.values.joinToString("\n") { it.text }
        }
    }

    /** fcitx addons' tips (showTip): 1 s, typed away. */
    fun tip(message: String) = post(steady = false) { message }

    private fun untilSeen(@StringRes id: Int, steady: Boolean, seen: () -> Unit = {}, ms: Long? = null): (String) -> Unit = { said ->
        unseen[id] = Unseen(said, unseen[id]?.steady == true || steady, seen, ms)
    }

    // looked up on the main thread as it is said
    private fun post(
        steady: Boolean,
        ms: Long? = null,
        toast: (String) -> String? = { it },
        said: () -> Unit = {},
        unsaid: (String) -> Unit = {},
        message: () -> String,
    ) = ContextCompat.getMainExecutor(appContext).execute {
        val text = message()
        if (sayer?.say(text, steady, ms) == true) return@execute said()
        toast(text)?.let { appContext.toast(it, if (steady) Toast.LENGTH_LONG else Toast.LENGTH_SHORT) }
        unsaid(text)
    }
}
