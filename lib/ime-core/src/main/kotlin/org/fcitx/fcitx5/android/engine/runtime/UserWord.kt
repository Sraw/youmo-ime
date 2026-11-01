/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

/**
 * A word of the user's, as the settings list them; [pinyin] its syllables apart by spaces. An
 * English one [neverCorrected]: added, or a correction of it taken back (U-31).
 */
data class UserWord(
    val text: String,
    val pinyin: String,
    val kind: Kind,
    val count: Float = 0f,
    val neverCorrected: Boolean = false,
    /** The calendar day (days since the epoch, local) it was last used, [NEVER] if not known (U-80). */
    val lastUsed: Int = NEVER,
    /** Typing days since it was last used, [NEVER] if not known: what 「很久没用」 counts (§1: 「天」 are typing days). */
    val idle: Int = NEVER,
    /** How recently it became the user's, larger later: the page's 「最近学到」 order. */
    val order: Long = 0,
) {
    enum class Kind {
        /** Added by hand, in the settings. */
        ADDED,

        /** Put together by the user from pieces as they typed. */
        LEARNED,

        /** Blocked, a long press on a candidate or in the settings: never offered. */
        BLOCKED,
    }

    companion object {
        const val NEVER = Int.MIN_VALUE
    }
}
