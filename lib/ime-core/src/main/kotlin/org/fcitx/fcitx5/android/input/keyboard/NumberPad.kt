/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.editing.NumberKind

/**
 * The number keyboard's keys beside its digits, by field: [FULL] from the 123 key and for every number field, as what
 * a field declares often is not what it takes (an amount declared decimal that takes a minus) and a field drops what
 * it refuses; a phone's and a date's their own ([of]). "" is a blank key, kept so the digits stay put. [left] top to
 * bottom; [besideNine] right of 9 below the space; [bottom] left of the symbols key, right of 0, left of Return.
 */
enum class NumberPad(val left: List<String>, val besideNine: String, val bottom: List<String>) {
    FULL(listOf("+", "-", "*"), "/", listOf(",", "=", ".")),

    /** `,` is a dialler's pause. */
    PHONE(listOf("+", "*", "#"), "-", listOf("(", ")", ",")),

    /** `2026-10-31`, `31/10`, `13:45`, `31.10` */
    DATETIME(listOf("-", "/", ":"), "", listOf("", "", ".")),
    ;

    val layout: String get() = if (this == FULL) FULL_LAYOUT else "$FULL_LAYOUT.$name"

    /** As number.xml's `pad=` and a layout's `pads=` name it. */
    val id: String get() = name.lowercase().replace('_', '-')

    companion object {
        /** Every 123 key names it. */
        const val FULL_LAYOUT = "Number"

        /** Null for the letters. */
        fun of(kind: NumberKind?): NumberPad? = when (kind) {
            null -> null
            NumberKind.NUMBER -> FULL
            NumberKind.PHONE -> PHONE
            NumberKind.DATETIME -> DATETIME
        }

        val layouts: Set<String> = entries.mapTo(LinkedHashSet()) { it.layout }

        /** A user layout drawn there too; null for none of them. */
        fun shownBy(name: String): NumberPad? = KeyboardNames.base(name).let { base -> entries.firstOrNull { it.layout == base } }
    }
}

/** The switching knows the number keyboard as [NumberPad.FULL] alone: the field's pad is shown in its place, so a pad is never remembered. */
class FieldPad {

    var field: NumberPad? = null

    /** Null: the app's; a file replaced is a keyboard made anew ([KeyboardNames.of]). */
    var numbers: CustomChoice? = null

    fun known(current: String): String = if (NumberPad.shownBy(current) != null) NumberPad.FULL_LAYOUT else current

    /** The field's pad for the number keyboard, even where the switching saw it shown already (another pad, or the full one). */
    fun shown(target: LayoutSwitchPolicy.Target, current: String): LayoutSwitchPolicy.Target {
        val name = when (target) {
            is LayoutSwitchPolicy.Target.Keyboard -> target.name
            LayoutSwitchPolicy.Target.Unchanged -> known(current)
            LayoutSwitchPolicy.Target.SymbolPicker -> return target
        }
        val pad = field?.takeIf { name == NumberPad.FULL_LAYOUT } ?: NumberPad.shownBy(name)
        val layout = pad?.let { KeyboardNames.of(it.layout, numbers?.takeIf { n -> PanelLayouts.draws(n, it) }) } ?: name
        return if (layout == current) LayoutSwitchPolicy.Target.Unchanged else LayoutSwitchPolicy.Target.Keyboard(layout)
    }
}
