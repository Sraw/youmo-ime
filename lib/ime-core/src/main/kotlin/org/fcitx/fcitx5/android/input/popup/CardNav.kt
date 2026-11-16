/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.engine.reading.CandidateCard

/**
 * Where a held candidate's menu head is (BaseInputView): its compact reading line ([Head.COMPACT]), tapped
 * open to the character's card ([Head.CHAR]) or the word's ([Head.WORD]), a word's character tapped to its
 * card ([Head.WORD_CHAR], the way back over it); a long candidate's line that opens nothing ([Head.LINE]),
 * a character looked up whose card is there at once ([Head.LOOKED]), no card at all ([Head.TITLE], the
 * candidate's text as before). The lines under it stay what they are whatever the head does.
 */
class CardNav(card: CandidateCard?) {

    enum class Head { TITLE, LOOKED, COMPACT, LINE, CHAR, WORD, WORD_CHAR }

    /** Where TalkBack's focus goes once the head changed: the new content's first node. */
    enum class Focus { NONE, CARD, WORD, BACK }

    private val word = card is CandidateCard.Word

    var head: Head = when (card) {
        null -> Head.TITLE
        is CandidateCard.Looked -> Head.LOOKED
        is CandidateCard.Line -> Head.LINE
        else -> Head.COMPACT
    }
        private set

    /** The word's character whose card is shown, [Head.WORD_CHAR]: its row. */
    var char: Int = -1
        private set

    /** Where the focus goes after the last step; [Focus.NONE] before any. */
    var focusTarget: Focus = Focus.NONE
        private set

    /**
     * Whether the window grows anew from the candidate pressed after the last step: the line opened to a card, a
     * wider window that may move to the other side of the candidate. A step within the card (a character, the way
     * back) keeps the window where it is, its height alone following the card: grown anew, it went blank for
     * two frames and slid in again from the candidate on every tap.
     */
    var regrows: Boolean = false
        private set

    /**
     * Whether the window grows anew from the candidate once shown after the last step: [regrows], or it went to the
     * candidate's other side, over it ([above]) where it was under it ([wasAbove]) or the other way: a word card that
     * fits under the candidate, its character's taller card that does not (a floating keyboard moved up, seen).
     * [wasAbove] null: not known, the side taken as kept.
     */
    fun growsAnew(wasAbove: Boolean?, above: Boolean): Boolean = regrows || (wasAbove != null && wasAbove != above)

    /** A card is shown, the menu as wide as one is ([CharCardLayout.width]); else as wide as the lines need. */
    val expanded: Boolean get() = head == Head.LOOKED || head == Head.CHAR || head == Head.WORD || head == Head.WORD_CHAR

    /** Whether the head is two columns ([CharCardLayout.twoColumns]): a card's, not a line's. */
    fun twoColumns(landscape: Boolean, floating: Boolean, paged: Boolean): Boolean =
        expanded && CharCardLayout.twoColumns(landscape, floating, paged)

    /** The compact line tapped: its card. False where there is none to open. */
    fun open(): Boolean {
        if (head != Head.COMPACT) return false
        head = if (word) Head.WORD else Head.CHAR
        focusTarget = if (word) Focus.WORD else Focus.CARD
        regrows = true
        return true
    }

    /** A word card's [row] tapped: that character's card. */
    fun pick(row: Int): Boolean {
        if (head != Head.WORD) return false
        head = Head.WORD_CHAR
        char = row
        focusTarget = Focus.BACK
        regrows = false
        return true
    }

    /** The way back tapped: the word's card again. */
    fun back(): Boolean {
        if (head != Head.WORD_CHAR) return false
        head = Head.WORD
        char = -1
        focusTarget = Focus.WORD
        regrows = false
        return true
    }
}
