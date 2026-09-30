/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * A common slip in typing a syllable, which [PinyinSegmenter] forgives when typos are on: the
 * slip reads as the syllable, flagged [SyllableMatches.TYPO].
 */
enum class Typo {
    /** gn for ng: zhagn, xign */
    GN,

    /** a dropped g after o: zhon, xion */
    ON,

    /** v for the ü these initials spell as u: jv, xve, qvan */
    V,
    ;

    /** The syllable of initial [init] and final [fin] as this slip types it, or null if it cannot. */
    fun of(init: String, fin: String): String? = when (this) {
        GN -> if (fin.endsWith("ng")) init + fin.dropLast(2) + "gn" else null
        ON -> if (fin.endsWith("ong")) init + fin.dropLast(1) else null
        V -> if (init in U_IS_V && fin.startsWith("u")) init + "v" + fin.substring(1) else null
    }

    private companion object {
        val U_IS_V = setOf("j", "q", "x", "y")
    }
}
