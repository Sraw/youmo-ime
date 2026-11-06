/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What one talk types, and when (060-voice-tap-to-talk.md, the user's decision 2): tap to talk
 * ([asItGoes]) types the settled part ([VoiceLive.Heard.settled]: the tagger's frozen text of the
 * closed segments, up to a mark) as it is said, so the composing text is only the tail still
 * changing, not a whole talk an app would take as plain text on a switch; the rest is typed when it
 * stops. Holding to talk types it all at the end. The end mark ([VoiceEnd]) is the caller's, on what
 * is typed last only.
 *
 * Each part is cleaned on its own ([VoiceText.clean]); a cut is after a mark, where the clean-up
 * joins nothing across it. A part cleaned alone can differ from the whole cleaned at once (a 「嗯」
 * or 「哦。」 alone between marks goes, where in the whole it may stay): accepted.
 */
class VoiceTalk(private val asItGoes: Boolean) {

    /** What to type now ([type], empty for nothing) and what composes after it ([composing]). */
    class Step(val type: String, val composing: String)

    // the start of the heard text (before the clean-up) typed, or taken by the app, so far
    private var typed = ""

    // the heard text last told
    private var last = ""

    // the polish failed once: its raw text is not in step with the polished part typed, so nothing
    // more is typed as it is said (it would type what was typed again)
    private var halted = false

    /** something was typed as it was said (or the app took it): the talk is not "nothing heard", whatever comes after */
    var typedAny = false
        private set

    /**
     * Told what is heard now: what of it to type now, and what still composes. [typing] false:
     * nothing typed now (the field composes the engine's text meanwhile), what settled is typed later.
     */
    fun heard(heard: VoiceLive.Heard, typing: Boolean = true): Step {
        last = heard.whole
        if (heard.fallback) halted = true
        val types = asItGoes && !halted && typing
        if (!types || !newlySettled(heard)) return Step("", if (typed.isEmpty()) heard.text else rest(heard.whole).orEmpty())
        val part = heard.whole.substring(typed.length, heard.settled)
        typed = heard.whole.substring(0, heard.settled)
        // a sound alone between two marks (「嗯，」) types nothing, as it would alone
        val type = VoiceText.clean(part).orEmpty()
        if (type.isNotEmpty()) typedAny = true
        return Step(type, rest(heard.whole).orEmpty())
    }

    // more settled than typed so far, after what was typed as it was (else nothing more is typed as it goes)
    private fun newlySettled(heard: VoiceLive.Heard) = heard.settled > typed.length && heard.whole.startsWith(typed)

    /**
     * What composed was taken from the field by the app (a chat's text sent, the field cleared): all
     * heard so far counts as typed, not typed again; the talk goes on after it.
     */
    fun consumed() {
        typed = last
        if (last.isNotEmpty()) typedAny = true
    }

    /** What is left to type of [whole], what was said all told (null: nothing came back); null if nothing. */
    fun rest(whole: String?): String? {
        if (whole == null) return null
        // the settled part changed after all (it should not): what differs is typed, not dropped
        var same = 0
        val most = minOf(typed.length, whole.length)
        while (same < most && typed[same] == whole[same]) same++
        return VoiceText.clean(whole.substring(same))
    }

    /** What is left to type of what was heard last: the talk ended before the recognizer was done. */
    fun restNow(): String? = rest(last)
}
