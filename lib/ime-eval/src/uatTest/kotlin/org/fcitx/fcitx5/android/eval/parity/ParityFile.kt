/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

// What is left of the parity files: the sequence and what the user sees after each step, run by ParityReplay.

/** What a user does at one step: [op] one of [OPS], [arg] its argument (the keys, a count, a name), if it takes one. */
data class Step(val op: String, val arg: String? = null) {
    init {
        require(op in OPS) { "no step $op" }
        require((arg != null) == (op in TAKES_ARG)) { "$op ${if (arg == null) "needs" else "takes no"} argument" }
    }

    override fun toString() = if (arg == null) op else "$op $arg"

    companion object {
        /**
         * type: screen keys; space, enter; bksp [arg] times; pick the [arg]th candidate; pickText; hw: a hardware key;
         * ime; field of kind [arg]; app: the other editor app; pause; toggle a status entry; quick, unicode.
         */
        val OPS = setOf("type", "space", "enter", "bksp", "pick", "pickText", "hw", "ime", "field", "app", "pause", "toggle", "quick", "unicode")
        private val TAKES_ARG = setOf("type", "bksp", "pick", "pickText", "hw", "ime", "field", "toggle")
        val FIELDS = listOf("text", "password", "url", "email")
    }
}

data class Sequence(val id: String, val im: String, val field: String, val steps: List<Step>) {
    init {
        require(field in Step.FIELDS) { "no field $field" }
    }
}

/** What the user sees after a step: the field's [text] and [sel], the [preedit], the bar's [cands]; a password's text masked. */
data class State(val text: String, val sel: Pair<Int, Int>, val preedit: String, val cands: List<String>) {
    companion object {
        /** [text] as a password field's state keeps it. */
        fun mask(text: String) = "•".repeat(text.codePointCount(0, text.length))
    }
}

/** What one [Sequence] gave: a [State] after each of its steps; [error] if the run could not go on (the rest left out). */
data class Output(val id: String, val states: List<State>, val error: String? = null)
