/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * Where the code being typed shows (编码行): the row embedded above the candidates, as up to 0.5.0; a
 * strip floating over the app outside the keyboard's top-left corner while typing ([FloatingCodeStrip]);
 * or in the app's field as its composing text (输入框里, 实验性: dev/design/061-code-in-field.md), the
 * strip there for what does not go in (the syllables, a field that does not take it). Stored by name:
 * the order is the list's, as the user named them.
 */
enum class CodeRowStyle(override val stringRes: Int) : ManagedPreferenceEnum {
    Embedded(R.string.code_row_embedded),
    Floating(R.string.code_row_floating),
    InField(R.string.code_row_in_field),
}
