/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/** Where the floating keyboard's handle ([FloatingHandle]) is: at the bottom, by the thumb, as Gboard's. */
enum class FloatingHandleSide(override val stringRes: Int) : ManagedPreferenceEnum {
    Bottom(R.string.floating_handle_bottom),
    Top(R.string.floating_handle_top),
}
