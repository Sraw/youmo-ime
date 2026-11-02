/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * Where the code being typed shows (编码行): a strip floating over the app outside the keyboard's
 * top-left corner while typing ([FloatingCodeStrip]), or the row embedded above the candidates, as up to 0.5.0.
 */
enum class CodeRowStyle(override val stringRes: Int) : ManagedPreferenceEnum {
    Floating(R.string.code_row_floating),
    Embedded(R.string.code_row_embedded),
}
