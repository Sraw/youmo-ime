/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.editing.EditingSession

/** On the main thread: each judged at its time, what a field dropped said and written again where asked. */
class KeptCommits(
    private val context: Context,
    private val session: EditingSession,
    private val started: () -> Boolean,
    private val shown: (line: String?) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * What was committed and did not go into the field (a WebView whose view lost the focus as the screen
     * turned takes every write and drops it): kept till tapped ([write]), something else goes in, or the
     * field or keyboard is left. Null with nothing kept.
     */
    val line: String?
        get() {
            val kept = keptShown.ifEmpty { return null }
            var from = maxOf(0, kept.length - KEPT_SHOWN)
            if (from > 0 && Character.isLowSurrogate(kept[from])) from++
            return context.getString(R.string.commit_kept, if (from > 0) "…" + kept.substring(from) else kept)
        }

    private var keptShown = ""

    // one at a time, set again for the time a later commit has left
    private var judging = false

    private val judgeCommit = Runnable {
        judging = false
        val commits = session.commits
        // set for a commit before the one waiting now: not this one's time yet
        if (commits.waitLeft() == 0L) session.judgeCommit()
        changed()
    }

    private val writeAsked = Runnable {
        val commits = session.commits
        if (commits.toWrite && !commits.isWaiting) write()
    }

    /** Written first, synchronously: after it the order would be reversed, and that commit landing would not let it go. */
    fun writeBeforeCommit() {
        val commits = session.commits
        if (!commits.toWrite || commits.isWaiting) return
        handler.removeCallbacks(writeAsked)
        write()
    }

    fun changed() {
        val commits = session.commits
        // written once the session is done with what ended the wait (a cursor report, a deletion), not in the middle of it
        if (commits.toWrite && !commits.isWaiting) {
            handler.removeCallbacks(writeAsked)
            handler.post(writeAsked)
        }
        if (commits.isWaiting && !judging) {
            judging = true
            handler.postDelayed(judgeCommit, commits.waitLeft())
        }
        if (commits.kept == keptShown) return
        keptShown = commits.kept
        shown(line)
    }

    /** While a later commit waits, written once that one is judged: now, it would join it and be kept after it. */
    fun write() {
        val commits = session.commits
        // between onFinishInput and the next start the connection is a finished one, which takes the text and drops it
        if (!started()) return
        if (session.composing.isNotEmpty()) {
            // something composes by the time a wait ends: not written over it; the line stays, to tap again
            commits.toWrite = false
            return
        }
        if (commits.isWaiting) {
            commits.toWrite = true
            return
        }
        val text = commits.take()
        // the session's commit alone: the first try was already told as typed (the journal, the context)
        if (text.isNotEmpty()) session.commitText(text)
    }

    fun cancel() {
        handler.removeCallbacks(judgeCommit)
        handler.removeCallbacks(writeAsked)
    }

    private companion object {
        // code units, its end
        const val KEPT_SHOWN = 24
    }
}
