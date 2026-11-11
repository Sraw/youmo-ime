/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/** Characters by their parts' pinyin (Chaizi), as pinyin offers them after [Chaizi.KEY]. */
fun interface PartsLookup {
    /** The characters whose parts [letters] spells, each with its reading hint: one syllable, or a polyphone's several (`xing/hang`). */
    fun of(letters: String): List<Pair<String, String>>

    /**
     * The one reading, toneless, a polyphone [text] is shown in after [before], of those [hint] (the lookup's
     * `xing/hang`) lists (CandidateReadings.oneOf), and blocked in (AsideBlocks.shown); null where none is known.
     */
    fun shownIn(text: String, before: String, hint: String? = null): String? = null
}
