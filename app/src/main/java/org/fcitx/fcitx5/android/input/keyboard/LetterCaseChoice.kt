/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.scene.engine.LetterCase

/** The 拼音键字母大小写 setting's three choices ([LetterCase]; LetterCaseRule decides by it), stored by name. */
enum class LetterCaseChoice(override val stringRes: Int, val case: LetterCase) : ManagedPreferenceEnum {
    Lower(R.string.letter_case_lower, LetterCase.Lower),
    Shift(R.string.letter_case_shift, LetterCase.Shift),
    Upper(R.string.letter_case_upper, LetterCase.Upper),
}
