/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What one talk types and when: tap to talk ([asItGoes]) types the settled part as it is said, so the composing
 * text is only the changing tail (an app would take a whole talk as plain text on a switch); holding types all at
 * the end. Each part is cleaned alone, cut after a mark; that can differ from cleaning the whole (accepted).
 */
class VoiceTalk(private val asItGoes: Boolean) {

    class Step(val type: String, val composing: String)

    // the start of the heard text (before the clean-up) typed, or taken by the app, so far
    private var typed = ""

    // the heard text last told, and whether it was the raw text ([VoiceLive.Heard.fallback])
    private var last = ""
    private var lastRaw = false

    // the polish failed once: its raw text is out of step with the polished part typed, so nothing more goes as said
    private var halted = false

    /** The talk is not "nothing heard", whatever comes after. */
    var typedAny = false
        private set

    /** [typing] false: nothing typed now (the field composes the engine's text), what settled is typed later. */
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

    private fun newlySettled(heard: VoiceLive.Heard) = heard.settled > typed.length && heard.whole.startsWith(typed)

    /** Left in the field as text (input restarted, the cursor unknown): all heard counts as typed. */
    fun kept() {
        typed = last
        if (last.isNotEmpty()) typedAny = true
    }

    /** Taken from the field by the app (a chat sent): not typed again; what comes next is a new utterance. */
    fun consumed() {
        if (last.isNotEmpty()) typedAny = true
        typed = ""
        last = ""
        lastRaw = false
        halted = false
    }

    /** [raw]: the recognizer's text (the polish failed): what was typed found in it as a subsequence, marks aside. */
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

    // what was typed found in [raw] in order, its marks skipped (the polish added them), raw's unmatched skipped;
    // a number written in digits (3, 50%, 8:05) matches the run raw said it in (三, 百分之五十)
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

    // the number said at [from]: 百分之 before, 点 between digits, a [clock]'s 分 after, 万/亿 but a final one when [unitWritten]
    private fun afterSaidNumber(raw: String, from: Int, clock: Boolean, unitWritten: Boolean): Int {
        var i = if (raw.startsWith(PERCENT, from)) from + PERCENT.length else from
        while (i < raw.length && inSaidNumber(raw, i, unitWritten)) i++
        if (clock && raw.getOrNull(i) == '分') i++
        return i
    }

    /** The talk ended before the recognizer was done. */
    fun restNow(): String? = rest(last, lastRaw)

    private fun pointInNumber(raw: String, at: Int) = raw[at] == '点' && raw.getOrNull(at + 1)?.let { it in SAID_DIGITS } == true

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
