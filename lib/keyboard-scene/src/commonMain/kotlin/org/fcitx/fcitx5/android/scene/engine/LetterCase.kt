/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

/**
 * 拼音键字母大小写: how a Chinese input method's letter keys show their letters ([Texts.letterCase]).
 * Only the faces and the press bubbles: what a key types, and Shift itself, are as they were.
 */
enum class LetterCase { Lower, Shift, Upper }

/** Whether a letter key shows its letters in capitals: the setting, the input method and Shift's state passed in. */
object LetterCaseRule {
    /**
     * The case [setting] gives the keys of an input method: English's always as Shift has it, its case
     * being what it types (the face and what is typed the same); a Chinese one's as the setting says.
     */
    fun forIme(setting: LetterCase, english: Boolean): LetterCase = if (english) LetterCase.Shift else setting

    /**
     * Capitals or not under [case] ([forIme]'s), [shift] Shift's state. Following Shift: capitals with it on,
     * or [keepUppercase] (the hidden 键盘字母保持大写), or on a keyboard whose letters show in capitals with no
     * Shift to follow ([shiftless]: 九键's and 14/18 键's pinyin, as they always have).
     */
    fun upper(case: LetterCase, shift: Shift, shiftless: Boolean = false, keepUppercase: Boolean = false): Boolean = when (case) {
        LetterCase.Lower -> false
        LetterCase.Upper -> true
        LetterCase.Shift -> shiftless || keepUppercase || shift != Shift.None
    }

    /** [letters] in the case [upper] says, `;` kept (its capital : is what Shift types, not a face). */
    fun shown(letters: String, upper: Boolean): String = if (upper) letters.uppercase() else letters.lowercase()
}
