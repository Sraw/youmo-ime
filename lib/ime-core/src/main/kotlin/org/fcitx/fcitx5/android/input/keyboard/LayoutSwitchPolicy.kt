/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Where a layout switch key takes the keyboard, and what it remembers for next time.
 *
 * A switch key names a keyboard layout, or names nothing -- "wherever the user last went for
 * symbols" (the `?123` key). That memory is either the number keyboard or the symbol picker;
 * anything that is not a keyboard layout opens the symbol picker.
 *
 * @param keyboards the names of the keyboard layouts that exist
 * @param textKeyboard the letters layout, which is never remembered as a symbol destination
 * @param symbolPicker the name remembered when the symbol picker is opened
 */
class LayoutSwitchPolicy(
    private val keyboards: Set<String>,
    private val textKeyboard: String,
    private val symbolPicker: String,
) {

    sealed interface Target {
        /** Show this keyboard layout. */
        data class Keyboard(val name: String) : Target

        /** The requested layout is already showing. */
        data object Unchanged : Target

        /** Open the symbol picker instead of a keyboard layout. */
        data object SymbolPicker : Target
    }

    /** @param remember the new symbol destination to store, or null to keep the old one */
    data class Outcome(val target: Target, val remember: String?)

    /**
     * @param requested the key's target; empty for "the last symbol destination"
     * @param remember false for switches the user did not ask for (a number field starting),
     * which must not overwrite their choice
     */
    fun switchTo(requested: String, lastSymbol: String, current: String, remember: Boolean): Outcome {
        val target = requested.ifEmpty { lastSymbol }
        if (target !in keyboards) {
            return Outcome(Target.SymbolPicker, symbolPicker.takeIf { remember })
        }
        val memory = target.takeIf { remember && it != textKeyboard }
        return Outcome(if (target == current) Target.Unchanged else Target.Keyboard(target), memory)
    }
}
