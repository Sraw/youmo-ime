/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * A talk's text handed over whole once the talk is over and the last of it in, for the engine to follow;
 * then what else goes in reported while the engine follows it. Main thread.
 */
class VoiceAsk {
    private val said = StringBuilder()
    private var talking = false

    private var posted = 0

    // a commit another talk posted (fcitx busy as the next began, a field left) lands uncounted: it took one off the
    // next's count, its text handed over before the last of it was in
    private var talk = 0

    // the field changed: the talk's text not all there
    private var dropped = false

    private var voicePutting = false
    private var gathering = false

    private var window = 0
    private var open = false

    /** The last talk's window is left open: a talk that puts nothing in leaves it as it was. */
    fun began() {
        said.setLength(0)
        talking = true
        talk++
        posted = 0
        dropped = false
    }

    fun ended(): String? {
        talking = false
        return whole()
    }

    fun posting(): Int {
        posted++
        return talk
    }

    fun landed(talk: Int, inField: Boolean): String? {
        if (talk != this.talk) return null
        if (posted > 0) posted--
        if (!inField) dropped = true
        return whole()
    }

    /** Gathered if that talk is the one now, never reported. */
    inline fun <T> voice(talk: Int, block: () -> T): T {
        voicing(true, talk)
        try {
            return block()
        } finally {
            voicing(false, talk)
        }
    }

    val talkNow: Int get() = talk

    @PublishedApi
    internal fun voicing(on: Boolean, talk: Int) {
        voicePutting = on
        gathering = on && talk == this.talk
    }

    enum class Put {
        NONE,

        REPORT,

        /** typed while a talk lasts */
        END,
    }

    fun putIn(text: String): Put {
        if (voicePutting) {
            if (gathering) said.append(text)
            return Put.NONE
        }
        if (!open) return Put.NONE
        if (!talking) return Put.REPORT
        open = false
        window++
        return Put.END
    }

    fun told(asks: Boolean): Int {
        open = asks
        return ++window
    }

    fun shut(window: Int) {
        if (window == this.window) open = false
    }

    val current: Int get() = window

    fun reset() {
        said.setLength(0)
        talking = false
        talk++
        posted = 0
        dropped = false
        open = false
        window++
    }

    private fun whole(): String? {
        if (talking || posted > 0) return null
        val text = said.toString().takeUnless { dropped || it.isEmpty() }
        said.setLength(0)
        dropped = false
        return text
    }
}
