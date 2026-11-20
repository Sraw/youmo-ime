/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

/** [pinyin] space-separated; English [neverCorrected]: added, or a correction taken back. */
data class UserWord(
    val text: String,
    val pinyin: String,
    val kind: Kind,
    val count: Float = 0f,
    val neverCorrected: Boolean = false,
    /** Local days since the epoch; [NEVER] if unknown. */
    val lastUsed: Int = NEVER,
    /** Typing days since last use, [NEVER] if unknown: what 「很久没用」 counts. */
    val idle: Int = NEVER,
    /** Larger is later: the page's 「最近学到」 order. */
    val order: Long = 0,
    /** An added word voice puts in wherever it reads alike, the word model not asked (「以后总是换」). */
    val voiceAlways: Boolean = false,
) {
    enum class Kind {
        /** Added by hand in the settings. */
        ADDED,

        /** Formed by the user from pieces while typing. */
        LEARNED,

        /** Never offered. */
        BLOCKED,
    }

    companion object {
        const val NEVER = Int.MIN_VALUE
    }
}
