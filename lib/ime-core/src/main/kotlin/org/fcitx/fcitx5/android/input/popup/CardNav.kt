/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.engine.reading.CandidateCard

/** A held candidate's menu head: compact line, char or word card, a word's char card, a plain line, or a title; the lines under it stay. */
class CardNav(card: CandidateCard?) {

    enum class Head { TITLE, LOOKED, COMPACT, LINE, CHAR, WORD, WORD_CHAR }

    /** TalkBack's focus after a change: the new content's first node. */
    enum class Focus { NONE, CARD, WORD, BACK }

    private val word = card is CandidateCard.Word

    var head: Head = when (card) {
        null -> Head.TITLE
        is CandidateCard.Looked -> Head.LOOKED
        is CandidateCard.Line -> Head.LINE
        else -> Head.COMPACT
    }
        private set

    /** [Head.WORD_CHAR]'s row. */
    var char: Int = -1
        private set

    var focusTarget: Focus = Focus.NONE
        private set

    /** The line opened to a card: a wider window, grown anew. A step within the card keeps it (grown anew it blanked two frames per tap). */
    var regrows: Boolean = false
        private set

    /** [regrows], or it changed sides of the candidate; [wasAbove] null: side kept. */
    fun growsAnew(wasAbove: Boolean?, above: Boolean): Boolean = regrows || (wasAbove != null && wasAbove != above)

    /** As wide as a card ([CharCardLayout.width]); else as the lines need. */
    val expanded: Boolean get() = head == Head.LOOKED || head == Head.CHAR || head == Head.WORD || head == Head.WORD_CHAR

    fun twoColumns(landscape: Boolean, floating: Boolean, paged: Boolean): Boolean =
        expanded && CharCardLayout.twoColumns(landscape, floating, paged)

    /** False where there is none to open. */
    fun open(): Boolean {
        if (head != Head.COMPACT) return false
        head = if (word) Head.WORD else Head.CHAR
        focusTarget = if (word) Focus.WORD else Focus.CARD
        regrows = true
        return true
    }

    fun pick(row: Int): Boolean {
        if (head != Head.WORD) return false
        head = Head.WORD_CHAR
        char = row
        focusTarget = Focus.BACK
        regrows = false
        return true
    }

    fun back(): Boolean {
        if (head != Head.WORD_CHAR) return false
        head = Head.WORD
        char = -1
        focusTarget = Focus.WORD
        regrows = false
        return true
    }
}
