/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Where a switch key goes: a named layout, or with none the last symbol destination (number keyboard or symbol picker). [textKeyboard] is never remembered so. */
class LayoutSwitchPolicy(
    private val keyboards: Set<String>,
    private val textKeyboard: String,
    private val symbolPicker: String,
) {

    sealed interface Target {
        data class Keyboard(val name: String) : Target

        data object Unchanged : Target

        data object SymbolPicker : Target
    }

    /** [remember] null keeps the old one. */
    data class Outcome(val target: Target, val remember: String?)

    /** [requested] empty: the last symbol destination; [remember] false for switches the user did not ask for (a number field starting). */
    fun switchTo(requested: String, lastSymbol: String, current: String, remember: Boolean): Outcome {
        val target = requested.ifEmpty { lastSymbol }
        if (target !in keyboards) {
            return Outcome(Target.SymbolPicker, symbolPicker.takeIf { remember })
        }
        val memory = target.takeIf { remember && it != textKeyboard }
        return Outcome(if (target == current) Target.Unchanged else Target.Keyboard(target), memory)
    }
}
