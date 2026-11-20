/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/** Offered while the cursor stays at [at], the end of the sum's `=`. */
class CalculationOffer(val worked: Calculation.Worked, private val at: Int) {

    // the editor reported the cursor at the `=`: reports of the keys before it may still come after the offer
    private var reached = false

    fun keeps(start: Int, end: Int): Boolean = when {
        start == at && end == start -> {
            reached = true
            true
        }
        // a late report of a key typed before the `=`
        !reached && end == start && start < at -> true
        else -> false
    }

    /** The `=` deleted before its report, or a move no report told apart from a late one: the result would land wrongly. */
    fun stillThere(before: CharSequence?): Boolean = before?.toString() == worked.sum

    companion object {
        /** Nothing read where [sensitive] or not a text field; a read filling [chars] may have cut the first number. */
        fun read(
            sensitive: Boolean,
            notText: Boolean,
            chars: Int,
            textBefore: (Int) -> CharSequence?,
        ): Calculation.Worked? {
            if (sensitive || notText) return null
            val before = textBefore(chars) ?: return null
            return Calculation.of(before, cut = before.length == chars)
        }
    }
}
