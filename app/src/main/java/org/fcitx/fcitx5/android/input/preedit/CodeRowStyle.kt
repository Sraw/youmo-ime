/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/** Stored by name: the order is the list's, as the user named them. */
enum class CodeRowStyle(override val stringRes: Int) : ManagedPreferenceEnum {
    Embedded(R.string.code_row_embedded),
    Floating(R.string.code_row_floating),
    InField(R.string.code_row_in_field),
}
