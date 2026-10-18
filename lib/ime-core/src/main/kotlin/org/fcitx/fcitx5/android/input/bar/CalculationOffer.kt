/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/**
 * A sum's result on the bar ([Calculation]): [worked], offered while the cursor stays at [at],
 * the end of the sum's `=`, and gone once it moves.
 */
class CalculationOffer(val worked: Calculation.Worked, private val at: Int) {

    // the editor reported the cursor at the `=`: reports of the keys before it may still come after the offer
    private var reached = false

    /** Whether the offer stands after the editor reports the selection [start]..[end]. */
    fun keeps(start: Int, end: Int): Boolean = when {
        start == at && end == start -> {
            reached = true
            true
        }
        // a late report of a key typed before the `=`
        !reached && end == start && start < at -> true
        else -> false
    }

    /**
     * Whether [before], the [Calculation.Worked.sum]'s length of text before the cursor, is still
     * the sum: the `=` deleted before its report came, or the cursor moved in a way no report
     * told apart from a late one, and the result would land in the wrong place.
     */
    fun stillThere(before: CharSequence?): Boolean = before?.toString() == worked.sum

    companion object {
        /**
         * The sum the text before the cursor ends with, read as [textBefore] gives up to [chars]
         * of it; nothing read at all in a field that may not be read ([sensitive], or not a text
         * field). A read that fills [chars] may have cut the sum's first number.
         */
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
