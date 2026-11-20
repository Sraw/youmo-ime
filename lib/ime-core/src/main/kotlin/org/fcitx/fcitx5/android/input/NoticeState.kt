/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/** The keyboard's one notice, as UndoChip draws it: when typing puts it away and how long it stays; each [U] gets its undo or lapse once. */
class NoticeState<U : Any> {

    /** The replaced one's act, to lapse. */
    data class Shown<U>(val lapsed: U?, val ms: Long)

    private var pending: U? = null

    /** Its 撤销 still works. */
    val holdsUndo: Boolean get() = pending != null

    private var steady = false

    // on its way out: a key typed meanwhile lets it go on rather than start it again
    private var leaving = false

    // hidden under the edit menu ([cover], or [show] while it is up), to come back as it goes
    private var hidden = false

    /** What a menu opened where the notice is does to it. */
    enum class Cover {
        /** As a key typed would. */
        Away,

        /** A steady one, a failure not yet read: hidden, its time stopped, until [uncover]. */
        Hide,

        /** Going or hidden already: left alone. */
        None,
    }

    /**
     * One at a time. [steady]: a failure, said as a key fails; the key after it must not put it away unseen.
     * [hidden]: said while the edit menu is up: shown when it goes, its time not started. [ms] null: its kind's time.
     */
    fun show(undo: U?, steady: Boolean, hidden: Boolean = false, ms: Long? = null): Shown<U> {
        val lapsed = pending
        pending = undo
        this.steady = steady
        leaving = false
        this.hidden = hidden
        return Shown(lapsed, ms ?: if (undo != null) UNDO_MS else if (steady) STEADY_MS else SAID_MS)
    }

    /** Whether it puts the notice away, as a tap would. */
    fun typed(): Boolean = !steady && !leaving

    /** The act to lapse, if one is still pending. */
    fun dismiss(): U? {
        leaving = true
        hidden = false
        return pending.also { pending = null }
    }

    /** One typing puts away goes; a steady failure is hidden till the menu goes, not lost while a finger picks. */
    fun cover(): Cover = when {
        leaving || hidden -> Cover.None
        typed() -> Cover.Away
        else -> Cover.Hide.also { hidden = true }
    }

    /** Timed from then, for as long as at first. */
    fun uncover(): Boolean = (hidden && !leaving).also { hidden = false }

    /** The next [typed] has nothing on its way out to leave alone. */
    fun gone() {
        leaving = false
    }

    /** Whether a button of the notice shown takes a tap: not once it is going. */
    fun choose(): Boolean = !leaving

    /** One replaced, fading, has lapsed: its act stands. */
    fun undo(undo: U): Boolean {
        if (pending !== undo) return false
        pending = null
        return true
    }

    companion object {
        // one with a button (撤销, 记住): long enough to reach for it
        const val UNDO_MS = 3000L

        // a failure the engine reports: read before it goes, though typing goes on
        const val STEADY_MS = 2000L

        // a line said, with nothing to reach for
        const val SAID_MS = 1000L

        /** Words offered to remember: no time of their own; a key, another field or the keyboard going puts them away. */
        const val STAYS = Long.MAX_VALUE
    }
}
