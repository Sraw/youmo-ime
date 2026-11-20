/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/** Half-typed input when a key does something other than typing (a symbol, quick phrase, unicode); fcitx is reset after either way. */
object PendingInputPolicy {

    enum class Resolution {
        /** As Space would. */
        SelectFirstCandidate,

        /** Exactly as typed. */
        FinishComposing,

        /** Its letters (Enter's), not a candidate. */
        CommitAsTyped,

        None,
    }

    /** Chinese takes the first candidate (no preedit: predictions, not taken); others keep the preedit. In an [address] field what is typed is meant (`xiaoming@`, not 小明@). */
    fun resolve(languageCode: String, hasPreedit: Boolean, address: Boolean = false): Resolution = when {
        !languageCode.startsWith("zh") -> Resolution.FinishComposing
        hasPreedit && address -> Resolution.CommitAsTyped
        hasPreedit -> Resolution.SelectFirstCandidate
        else -> Resolution.None
    }

    /** In an [address] field, as the address keys are (a digit would pick, `_` be ——); letters, space and keys the input method reads as its own go through. */
    fun typesAsIs(key: String, address: Boolean, reads: (Int) -> Boolean = { false }): Boolean {
        if (!address || key.codePointCount(0, key.length) != 1) return false
        val c = key.codePointAt(0)
        return when {
            Character.isLetter(c) || Character.isWhitespace(c) || Character.isISOControl(c) -> false
            Character.getType(c) == Character.PRIVATE_USE.toInt() -> false
            else -> !reads(c)
        }
    }
}
