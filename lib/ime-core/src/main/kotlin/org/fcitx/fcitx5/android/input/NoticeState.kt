/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * The life of the keyboard's one notice (dev/design/motion-notices-handle.md §3), what UndoChip
 * draws: which end of an act's 撤销 is reached, whether typing puts it away, how long it stays.
 * [U] is what 撤销 takes back; of each the chip calls its undo or its lapse, once, as said here.
 */
class NoticeState<U : Any> {

    /** What [show] leaves to the chip: the act of the one replaced, to lapse, and how long the new one stays. */
    data class Shown<U>(val lapsed: U?, val ms: Long)

    private var pending: U? = null

    // the one shown is not put away by typing
    private var steady = false

    // on its way out: a key typed meanwhile lets it go on rather than start it again
    private var leaving = false

    /**
     * A notice in the place of any shown (rule 5: one at a time), with [undo] to take back, or none.
     * [steady]: a failure, said once as a key fails; the key comes after it and must not put it away
     * unseen, and it stays as long as one with 撤销 (rule 4), a line said 2 s being gone before it is read.
     */
    fun show(undo: U?, steady: Boolean): Shown<U> {
        val lapsed = pending
        pending = undo
        this.steady = steady
        leaving = false
        return Shown(lapsed, if (undo != null || steady) UNDO_MS else SAID_MS)
    }

    /** A key typed, a word put in: whether it puts the notice away, as a tap would. */
    fun typed(): Boolean = !steady && !leaving

    /** Put away (tapped, timed out, typed on, the keyboard gone): the act to lapse, if one is still pending. */
    fun dismiss(): U? {
        leaving = true
        return pending.also { pending = null }
    }

    /** Gone from the screen: the next [typed] has nothing on its way out to leave alone. */
    fun gone() {
        leaving = false
    }

    /** 撤销 on [undo] tapped: whether it takes back. One replaced, fading, has lapsed: its act stands. */
    fun undo(undo: U): Boolean {
        if (pending !== undo) return false
        pending = null
        return true
    }

    companion object {
        // long enough to read a few words and reach for 撤销, short enough not to sit over the candidates
        const val UNDO_MS = 6000L

        // a line said, with nothing to reach for (the user, 2026-10-22)
        const val SAID_MS = 2000L
    }
}
