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

/**
 * What the engine says to the user, from any thread: on the keyboard if it is shown (§3: one notice
 * there, and Android 13 drops an input method's toasts), a toast with none. The input method sets
 * [sayer] while it runs: core does not reach into input (F3539).
 */
object EngineNotices {

    /** [message] said on the main thread; [steady], a failure (NoticeState.show). */
    fun interface Sayer {
        /**
         * Whether it was said on the keyboard; with none shown nothing, as what is to be seen on the
         * keyboard waits for it: the toast in its place is [post]'s to say or not.
         */
        fun say(message: String, steady: Boolean): Boolean
    }

    @Volatile
    var sayer: Sayer? = null

    // the notices said but not on the keyboard, to be said there when it next shows (failedUntilSeen,
    // toldUntilSeen), each of them, with whether it is a failure: several are said as the host starts,
    // before any keyboard (the config and the data not settled). The main thread's
    private val unseen = LinkedHashMap<Int, Unseen>()

    // a notice's own text as it was said (not joined with the others it was said with), whether it is a
    // failure, and what is to be done once it is seen
    private class Unseen(val text: String, val steady: Boolean, val seen: () -> Unit)

    /** A failure, said as a key fails: not put away by the key typed after it, and long (rule 4). */
    fun failed(@StringRes id: Int) = post(steady = true) { appContext.getString(id) }

    /**
     * A failure the user must see though no key is typed (the settings could not be read, A4): said
     * as [failed] is, and if not on the keyboard (none shown as the host starts), again when it next
     * shows ([keyboardShown]).
     */
    fun failedUntilSeen(@StringRes id: Int) = post(steady = true, unsaid = untilSeen(id, steady = true)) { appContext.getString(id) }

    /**
     * News the user is to see once, no failure (0.4's 九键拼音 merged into pinyin, I1): said on the
     * keyboard as a [tip] is, and with none shown (as the host starts) when it next shows
     * ([keyboardShown]); no toast, which would say it before the keyboard it is about. [seen] runs on
     * the main thread once it is said there: what records it told, so one never seen is told again.
     * Told again before it is seen (the host reloaded first), nothing more: it is on its way.
     */
    fun toldUntilSeen(@StringRes id: Int, vararg args: Any, seen: () -> Unit = {}) {
        if (!telling.add(id)) return
        val done = {
            telling.remove(id)
            seen()
        }
        post(steady = false, toast = { null }, said = done, unsaid = untilSeen(id, steady = false, done)) { appContext.getString(id, *args) }
    }

    // what toldUntilSeen has on its way and not yet seen
    private val telling: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    // what failedOnce said this process
    private val toldOnce: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * [failedUntilSeen], once a process: what several places may each find (the user's added files not
     * read, A4), told once till the process restarts.
     */
    fun failedOnce(@StringRes id: Int) {
        if (toldOnce.add(id)) failedUntilSeen(id)
    }

    // a test's process outlives the run it stands for: what failedOnce said, and what a test with no
    // keyboard left waiting to be seen (else said with the next test's). On the main thread
    internal fun forgetToldOnce() {
        toldOnce.clear()
        telling.clear()
        unseen.clear()
    }

    /** The keyboard's window shown (the service's onWindowShown): a failure not yet seen on it said there. */
    fun keyboardShown() = ContextCompat.getMainExecutor(appContext).execute {
        if (unseen.isEmpty()) return@execute
        val pending = unseen.toMap()
        // a failure among them makes the notice one
        val steady = pending.values.any { it.steady }
        unseen.clear()
        // one notice on the keyboard, so one line each in it: said one after another, each would put the last away.
        // Not said there either (the window not yet on the screen): each waits again as itself, not as the
        // joined line, which joined again would say each twice
        // the toast, with no keyboard, the failures alone: news is not toasted (toldUntilSeen)
        val failures = pending.values.filter { it.steady }.joinToString("\n") { it.text }
        post(steady, toast = { failures.ifEmpty { null } }, said = { pending.values.forEach { it.seen() } }, unsaid = { pending.forEach { (id, u) -> unseen.putIfAbsent(id, u) } }) {
            pending.values.joinToString("\n") { it.text }
        }
    }

    /**
     * A plain tip, what fcitx's own addons say (androidnotification's showTip: punctuation, full
     * width, a group switched): said for 2 s and typed away, as any line said (rule 5, F3464).
     */
    fun tip(message: String) = post(steady = false) { message }

    // [id] said but not seen: to be said when the keyboard next shows
    private fun untilSeen(@StringRes id: Int, steady: Boolean, seen: () -> Unit = {}): (String) -> Unit = { said ->
        unseen[id] = Unseen(said, unseen[id]?.steady == true || steady, seen)
    }

    // looked up when it is said, on the main thread; on the keyboard [said] told, else [toast] of the text toasted (null none) and [unsaid] told
    private fun post(
        steady: Boolean,
        toast: (String) -> String? = { it },
        said: () -> Unit = {},
        unsaid: (String) -> Unit = {},
        message: () -> String,
    ) = ContextCompat.getMainExecutor(appContext).execute {
        val text = message()
        if (sayer?.say(text, steady) == true) return@execute said()
        toast(text)?.let { appContext.toast(it, if (steady) Toast.LENGTH_LONG else Toast.LENGTH_SHORT) }
        unsaid(text)
    }
}
