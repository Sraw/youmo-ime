/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * 语音先问再记, the service's side (061-voice-learn-from-edits.md): a talk's text gathered as it goes
 * into the field, handed over whole once the talk is over and the last of it in ([ended], [landed]),
 * for the engine to follow ([org.fcitx.fcitx5.android.core.HostApi.voiceSaid]); then what else goes
 * in reported ([putIn]) while the engine follows it. On the main thread.
 */
class VoiceAsk {
    private val said = StringBuilder()
    private var talking = false

    // voice's commits posted through fcitx ([posting]), not in yet
    private var posted = 0

    // one of them did not go in (the field changed): the talk's text not all there
    private var dropped = false

    // voice's text going in now ([voice])
    private var voicePutting = false

    // a window the engine may have open, by the talk that made it: what goes in is reported
    private var window = 0
    private var open = false

    /**
     * A talk began: what was gathered of another, gone. The window of the last is left open: a talk that puts
     * nothing in (not heard, no permission, a failure) leaves it as it was.
     */
    fun began() {
        said.setLength(0)
        talking = true
        posted = 0
        dropped = false
    }

    /** The talk is over: its text, if all of it is in. */
    fun ended(): String? {
        talking = false
        return whole()
    }

    /** Voice's commit posted through fcitx (CommitAction's), to come in later. */
    fun posting() {
        posted++
    }

    /** That commit in ([inField]) or dropped: the talk's text, if it is over and all of it in. */
    fun landed(inField: Boolean): String? {
        if (posted > 0) posted--
        if (!inField) dropped = true
        return whole()
    }

    /** [block] puts voice's text in: gathered, not reported. */
    inline fun <T> voice(block: () -> T): T {
        voicing(true)
        try {
            return block()
        } finally {
            voicing(false)
        }
    }

    @PublishedApi
    internal fun voicing(on: Boolean) {
        voicePutting = on
    }

    /** What to do with text that went in ([putIn]). */
    enum class Put {
        /** nothing: voice's, gathered, or no window open */
        NONE,

        /** reported to the engine ([org.fcitx.fcitx5.android.core.HostApi.voicePutIn]) */
        REPORT,

        /** the window ended ([org.fcitx.fcitx5.android.core.HostApi.voiceEditsEnd]): typed while a talk lasts (VO-47) */
        END,
    }

    /** [text] into the field: voice's gathered; else reported while a window is open, the window shut if a talk goes on. */
    fun putIn(text: String): Put {
        if (voicePutting) {
            said.append(text)
            return Put.NONE
        }
        if (!open) return Put.NONE
        if (!talking) return Put.REPORT
        open = false
        window++
        return Put.END
    }

    /** The talk's text handed to the engine, [asks] or not: the window it may open, numbered. */
    fun told(asks: Boolean): Int {
        open = asks
        return ++window
    }

    /** The engine said [window] is shut (its report's answer): nothing reported till another opens. */
    fun shut(window: Int) {
        if (window == this.window) open = false
    }

    /** The window's number now, for [shut]. */
    val current: Int get() = window

    /** The field left, input finished: nothing gathered nor followed. */
    fun reset() {
        said.setLength(0)
        talking = false
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
