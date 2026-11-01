/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.editing.NumberKind

/**
 * The number keyboard's keys beside its digits, as the field is: [FULL] by the 123 key in a text
 * field and by a plain number field (one that says no more is often a price's, a Compose or a
 * Flutter field that filters nothing), the others put up by a field that says what it takes ([of]):
 * a PIN's digits alone, a decimal's point, a signed one's minus, a phone's or a date's own.
 * Each pad is a layout of its own, [layout] its name; an empty string is a key left blank, kept so
 * the digits stay where they are on every pad.
 *
 * @param left the column left of the digits, top to bottom
 * @param corner the key right of 3, above the space
 * @param bottom the bottom row's: left of the symbols key, right of 0, and left of Return
 */
enum class NumberPad(val left: List<String>, val corner: String, val bottom: List<String>) {
    FULL(listOf("+", "-", "*"), "/", listOf(",", "=", ".")),

    /** A PIN's: the digits alone. */
    DIGITS(BLANK3, "", BLANK3),

    // the sign right of 0, the point in the full pad's place
    SIGNED(BLANK3, "", listOf("", "-", "")),
    DECIMAL(BLANK3, "", listOf("", "", ".")),
    SIGNED_DECIMAL(BLANK3, "", listOf("", "-", ".")),

    /** A dialler's: `,` is its pause */
    PHONE(listOf("+", "*", "#"), "-", listOf("(", ")", ",")),

    /** A date's, a time's, both's: `2026-10-31`, `31/10`, `13:45`, `31.10` */
    DATETIME(listOf("-", "/", ":"), "", listOf("", "", ".")),
    ;

    /** The name of this pad's layout; [FULL]'s is the number keyboard's. */
    val layout: String get() = if (this == FULL) FULL_LAYOUT else "$FULL_LAYOUT.$name"

    companion object {
        /** The number keyboard's layout name, which every 123 key names. */
        const val FULL_LAYOUT = "Number"

        /** The pad a field of [kind] gets, or null for one that is not a number's, phone's or date's: the letters. */
        fun of(kind: NumberKind?): NumberPad? = when (kind) {
            null -> null
            NumberKind.INTEGER -> FULL
            NumberKind.PIN -> DIGITS
            NumberKind.SIGNED -> SIGNED
            NumberKind.DECIMAL -> DECIMAL
            NumberKind.SIGNED_DECIMAL -> SIGNED_DECIMAL
            NumberKind.PHONE -> PHONE
            NumberKind.DATE, NumberKind.TIME, NumberKind.DATETIME -> DATETIME
        }

        /** Every pad's layout name. */
        val layouts: Set<String> = entries.mapTo(LinkedHashSet()) { it.layout }
    }
}

private val BLANK3 = listOf("", "", "")

/**
 * Which pad the number keyboard is in a field: [field], the field's own pad while one is focused
 * ([NumberPad.of]), or null. The layout switching ([LettersLayout]) knows the number keyboard as
 * [NumberPad.FULL]'s layout alone, which every 123 key names and the 123 key's memory keeps: this
 * shows the field's pad in its place, and tells the switching the pad shown as that layout, so a
 * pad is never remembered and the 123 key in a phone field, after ABC, brings its pad back.
 */
class FieldPad {

    var field: NumberPad? = null

    /** [current], the layout shown, as the switching knows it. */
    fun known(current: String): String = if (current in NumberPad.layouts) NumberPad.FULL_LAYOUT else current

    /**
     * What to show of [target], the switching's choice with [current] shown: the field's pad for
     * the number keyboard, even where the switching saw it shown already (another pad, the full
     * one the 123 key brought in a text field, as a number field starts).
     */
    fun shown(target: LayoutSwitchPolicy.Target, current: String): LayoutSwitchPolicy.Target {
        val name = when (target) {
            is LayoutSwitchPolicy.Target.Keyboard -> target.name
            LayoutSwitchPolicy.Target.Unchanged -> known(current)
            LayoutSwitchPolicy.Target.SymbolPicker -> return target
        }
        val layout = field?.takeIf { name == NumberPad.FULL_LAYOUT }?.layout ?: name
        return if (layout == current) LayoutSwitchPolicy.Target.Unchanged else LayoutSwitchPolicy.Target.Keyboard(layout)
    }
}
