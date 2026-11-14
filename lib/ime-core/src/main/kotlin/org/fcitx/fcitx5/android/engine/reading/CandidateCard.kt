/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

/**
 * What a held candidate's menu heads with (Engines.card), worked out in the same trip as its lines:
 * a character looked up its full card ([Looked]); any other candidate with a Han character in it a
 * compact line of its reading ([line], as 候选词注音 reads it), tapped open to its character's card
 * ([Char]) or its word's ([Word]), or, of more than [WordCard.MAX_CHARS] Han characters, the line alone
 * ([Line]). The line's measured cost: in Engines.card's KDoc.
 */
sealed interface CandidateCard {
    /** Handwriting's character, or one looked up by its parts or strokes: its card at once, as before. */
    data class Looked(val card: CharCard) : CandidateCard

    /** One Han character: [line] (háng / xíng…), its [card] a tap away, the reading it has in the candidate first. */
    data class Char(val line: String, val card: CharCard) : CandidateCard

    /** Two to [WordCard.MAX_CHARS] Han characters: [line] (yín háng), its [card] a tap away. */
    data class Word(val line: String, val card: WordCard) : CandidateCard

    /** More Han characters than that: [text] and its reading [line], wrapped, nothing to open. */
    data class Line(val text: String, val line: String) : CandidateCard
}
