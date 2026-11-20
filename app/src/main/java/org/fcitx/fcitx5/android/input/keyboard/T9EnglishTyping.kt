/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class T9EnglishTyping(override val stringRes: Int) : ManagedPreferenceEnum {
    Predictive(R.string.t9_english_predictive),
    MultiTap(R.string.t9_english_multi_tap),
}
