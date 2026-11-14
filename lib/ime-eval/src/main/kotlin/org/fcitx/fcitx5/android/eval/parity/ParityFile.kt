/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

/*
 * What is left of the parity files (fcitx-replacement §7.2; specs/testing.md "Parity of the two hosts"):
 * the sequence and what the user sees after each step, as uatTest's ParityReplay runs them on the
 * Kotlin host. The device side, the files' JSON lines and their comparison were deleted in 0.6.1 (the
 * owner's choice of 2026-11-13: the native host's recordings can never be made again).
 */

/** What a user does at one step: [op] one of [OPS], [arg] its argument (the keys, a count, a name), if it takes one. */
data class Step(val op: String, val arg: String? = null) {
    init {
        require(op in OPS) { "no step $op" }
        require((arg != null) == (op in TAKES_ARG)) { "$op ${if (arg == null) "needs" else "takes no"} argument" }
    }

    override fun toString() = if (arg == null) op else "$op $arg"

    companion object {
        /**
         * type: keys tapped on the screen's keyboard (letters, the nine keys' digits, `,` `.`);
         * space, enter; bksp: the backspace key [arg] times; pick: the [arg]th (1-based) candidate
         * on the bar, nothing if there are fewer; pickText: the candidate reading [arg], nothing
         * if none does; hw: a hardware key by name (the ones ParityReplay has, its `HW`); ime: the
         * input method named [arg] (one of those the run enabled); field: a new field
         * of kind [arg] ([FIELDS]) in the same app; app: the other test editor app (another uid),
         * a text field; pause: nothing, the user waits; toggle: the status area's entry [arg]
         * tapped (a Chinese input method's chttrans, punctuation, fullwidth: 简繁, 标点, 全角); quick,
         * unicode: quick input and Unicode search opened (the 符 and 123 keys held).
         */
        val OPS = setOf("type", "space", "enter", "bksp", "pick", "pickText", "hw", "ime", "field", "app", "pause", "toggle", "quick", "unicode")
        private val TAKES_ARG = setOf("type", "bksp", "pick", "pickText", "hw", "ime", "field", "toggle")
        val FIELDS = listOf("text", "password", "url", "email")
    }
}

/** One sequence: its [id], the input method and field it starts in, its [steps]. */
data class Sequence(val id: String, val im: String, val field: String, val steps: List<Step>) {
    init {
        require(field in Step.FIELDS) { "no field $field" }
    }
}

/**
 * What the user sees after a step: the field's [text] and selection [sel], the code shown above
 * the candidates ([preedit]), the candidates on the bar ([cands], the whole page). A password field's
 * text is masked (`•` for each character).
 */
data class State(val text: String, val sel: Pair<Int, Int>, val preedit: String, val cands: List<String>) {
    companion object {
        /** [text] as a password field's state keeps it. */
        fun mask(text: String) = "•".repeat(text.codePointCount(0, text.length))
    }
}

/** What one [Sequence] gave: a [State] after each of its steps; [error] if the run could not go on (the rest left out). */
data class Output(val id: String, val states: List<State>, val error: String? = null)
