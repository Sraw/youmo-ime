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

    // the heard text last told, and whether it was the raw text ([VoiceLive.Heard.fallback])
    private var last = ""
    private var lastRaw = false

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
        lastRaw = heard.fallback
        if (heard.fallback) halted = true
        val types = asItGoes && !halted && typing
        if (!types || !newlySettled(heard)) {
            return Step("", if (typed.isEmpty()) heard.text else rest(heard.whole, heard.fallback).orEmpty())
        }
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
     * What composed was left in the field as text (input restarted where the field could not say
     * where its cursor is): all heard so far counts as typed, the talk goes on after it.
     */
    fun kept() {
        typed = last
        if (last.isNotEmpty()) typedAny = true
    }

    /**
     * What composed was taken from the field by the app (a chat's text sent, the field cleared): all
     * heard so far went with it, not typed again; what is heard next is a new utterance
     * ([VoiceLive.restart]), typed from its start.
     */
    fun consumed() {
        if (last.isNotEmpty()) typedAny = true
        typed = ""
        last = ""
        lastRaw = false
        halted = false
    }

    /**
     * What is left to type of [whole], what was said all told (null: nothing came back); null if
     * nothing. [raw]: [whole] is the recognizer's text, the polish having failed, not in step with
     * the polished part typed (its marks, the 「嗯」 it took out): what was typed found in it as a
     * subsequence, marks aside.
     */
    fun rest(whole: String?, raw: Boolean = false): String? {
        if (whole == null) return null
        val from = (if (raw) afterTypedIn(whole) else null) ?: samePrefix(whole)
        return VoiceText.clean(whole.substring(from))
    }

    // the settled part changed after all (it should not): what differs is typed, not dropped
    private fun samePrefix(whole: String): Int {
        var same = 0
        val most = minOf(typed.length, whole.length)
        while (same < most && typed[same] == whole[same]) same++
        return same
    }

    // where what was typed ends in [raw]: its characters found there in order, its marks skipped (the
    // polish added them), a character of raw's not matched skipped (one the polish took out); null if
    // not all of it is there. The marks raw has after it go with it. A number the polish wrote in
    // digits (3, 50%, 8:05) is the run of number characters raw said it in (三, 百分之五十); a letter
    // the polish cased otherwise is the same letter
    private fun afterTypedIn(raw: String): Int? {
        var i = 0
        var j = 0
        while (j < typed.length) {
            when {
                !typed[j].isLetterOrDigit() -> j++
                i == raw.length -> return null
                typed[j] in '0'..'9' && (raw[i] in SAID_DIGITS || raw.startsWith(PERCENT, i)) -> {
                    val from = j
                    while (j < typed.length && (typed[j] in '0'..'9' || typed[j] in IN_NUMBERS)) j++
                    // 「5万」: the number's 万 written as a character, matched as one
                    i = afterSaidNumber(raw, i, clock = ':' in typed.substring(from, j), unitWritten = typed.getOrNull(j)?.let { it in UNITS } == true)
                }
                typed[j].equals(raw[i], ignoreCase = true) -> {
                    i++
                    j++
                }
                else -> i++
            }
        }
        while (i < raw.length && !raw[i].isLetterOrDigit()) i++
        return i
    }

    // where the number said at [from] in [raw] ends: 百分之 before it, 点 between its digits, a
    // [clock]'s 分 after it, its 万 and 亿 (三十三万六千零五, 336005) but one at its end
    // when [unitWritten] (五万, 5万)
    private fun afterSaidNumber(raw: String, from: Int, clock: Boolean, unitWritten: Boolean): Int {
        var i = if (raw.startsWith(PERCENT, from)) from + PERCENT.length else from
        while (i < raw.length && inSaidNumber(raw, i, unitWritten)) i++
        if (clock && raw.getOrNull(i) == '分') i++
        return i
    }

    /** What is left to type of what was heard last: the talk ended before the recognizer was done. */
    fun restNow(): String? = rest(last, lastRaw)

    // 点 between two of a number's digits
    private fun pointInNumber(raw: String, at: Int) = raw[at] == '点' && raw.getOrNull(at + 1)?.let { it in SAID_DIGITS } == true

    // a digit said, a 点 between digits, or 万 or 亿 inside a number (a digit said after it) or at its end when
    // the number was written without it
    private fun inSaidNumber(raw: String, at: Int, unitWritten: Boolean) = raw[at] in SAID_DIGITS || pointInNumber(raw, at) ||
        (raw[at] in UNITS && (!unitWritten || raw.getOrNull(at + 1)?.let { it in SAID_DIGITS } == true))

    private companion object {
        // what a number is said in, but its 万, 亿, 点 and 分之, which stay or are matched as characters
        const val SAID_DIGITS = "零〇一二两三四五六七八九十百千幺"

        // what the polish writes inside a number in digits
        const val IN_NUMBERS = ".:%/"

        const val PERCENT = "百分之"

        const val UNITS = "万亿"
    }
}
