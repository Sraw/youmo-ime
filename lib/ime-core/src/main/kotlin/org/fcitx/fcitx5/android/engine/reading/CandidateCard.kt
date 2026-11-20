/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

/** What a held candidate's menu heads with (Engines.card): a looked-up character's card, or a compact reading line that opens a char or word card; past [WordCard.MAX_CHARS] the line alone. */
sealed interface CandidateCard {
    /** Handwriting's character, or one looked up by parts or strokes: its card at once. */
    data class Looked(val card: CharCard) : CandidateCard

    /** One Han character: [line] (háng / xíng…), its [card] a tap away. */
    data class Char(val line: String, val card: CharCard) : CandidateCard

    /** Two to [WordCard.MAX_CHARS] Han characters: [line] (yín háng), its [card] a tap away. */
    data class Word(val line: String, val card: WordCard) : CandidateCard

    /** More Han characters than that: [line] alone, wrapped. */
    data class Line(val text: String, val line: String) : CandidateCard
}
