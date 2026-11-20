/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/** A common syllable slip [PinyinSegmenter] forgives when typos are on, flagged [SyllableMatches.TYPO]. */
enum class Typo {
    /** gn for ng: zhagn, xign */
    GN,

    /** a dropped g after o: zhon, xion */
    ON,

    /** v for the ü these initials spell as u: jv, xve, qvan */
    V,
    ;

    /** Null if it cannot. */
    fun of(init: String, fin: String): String? = when (this) {
        GN -> if (fin.endsWith("ng")) init + fin.dropLast(2) + "gn" else null
        ON -> if (fin.endsWith("ong")) init + fin.dropLast(1) else null
        V -> if (init in SpellingIndex.U_IS_V && fin.startsWith("u")) init + "v" + fin.substring(1) else null
    }
}
