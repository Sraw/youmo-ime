/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.input.popup.PopupPreset

/**
 * The symbol panel's cells' long presses (060-keys-unify C1): built in, outside the key model, so no
 * setting reaches them. [PopupPreset]'s entries of no letter: the digits, the punctuation, the currency.
 */
object PickerLongPress {

    private val table: Map<String, Array<String>> = PopupPreset.filterKeys { label -> label.none(Char::isLetter) }

    /** What a long press on the cell of [symbol] offers; null for none. */
    operator fun get(symbol: String): Array<String>? = table[symbol]
}
