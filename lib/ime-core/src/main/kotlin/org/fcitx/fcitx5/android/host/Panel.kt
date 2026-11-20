/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText

/** fcitx's `CandidateWord`; named apart from the app's `core.CandidateWord`. */
interface PanelCandidate {
    val text: String
    val comment: String get() = ""

    /** One per code point of [text]; empty for none. */
    val readings: List<String> get() = emptyList()

    /** Never through the output filters (traditional): the user's own quick phrases. */
    val asIs: Boolean get() = false

    /** The list it is on may be replaced by what this does. */
    fun select(ic: InputContext)
}

/** fcitx's `CandidateAction`: [id] is what [CandidateList.triggerAction] gets back. */
data class CandidateAction(val id: Int, val text: String)

/** fcitx's `CandidateList` as the frontend reads it; an index out of range is null, where fcitx throws. */
interface CandidateList {
    val size: Int

    fun label(index: Int): String

    fun candidate(index: Int): PanelCandidate?

    /** What space would pick; -1 none. */
    val cursorIndex: Int

    val bulk: Boolean get() = false

    /** -1 while not counted ([bulk] lists). */
    val totalSize: Int get() = size

    /** [bulk] lists; else the page's. */
    fun candidateFromAll(index: Int): PanelCandidate? = candidate(index)

    /** 联想's words with nothing typed: the bar's × drops them. */
    val predicting: Boolean get() = false

    /** The one the bar draws as the first; -1 none. */
    val lead: Int get() = 0

    val pageable: Boolean get() = false

    /** What the paged window's orientation follows when automatic (Unicode's: vertical). */
    val layoutHint: LayoutHint get() = LayoutHint.NotSet

    val hasPrev: Boolean get() = false
    val hasNext: Boolean get() = false
    fun prev() {}
    fun next() {}

    fun actions(word: PanelCandidate): List<CandidateAction> = emptyList()

    fun triggerAction(word: PanelCandidate, id: Int) {}
}

/** As fcitx's `CommonCandidateList`: a pick is the candidate's own select; the temp modes and punctuation share it. */
internal open class TempModeCandidates(private val list: TempCandidateList) : CandidateList {
    private inner class Word(val candidate: TempCandidate) : PanelCandidate {
        override val text get() = candidate.text
        override val comment get() = candidate.comment
        override val asIs get() = candidate.asIs
        override fun select(ic: InputContext) {
            candidate.select?.invoke(ic)
        }
    }

    override val size get() = list.size
    override fun label(index: Int) = if (index in 0 until list.size) list.label(index) else ""
    override fun candidate(index: Int): PanelCandidate? = if (index in 0 until list.size) Word(list.candidate(index)) else null
    override val cursorIndex get() = list.cursorIndex
    override val bulk get() = true
    override val totalSize get() = list.totalSize
    override fun candidateFromAll(index: Int): PanelCandidate? = list.candidates.getOrNull(index)?.let(::Word)
    override val pageable get() = true
    override val hasPrev get() = list.hasPrev
    override val hasNext get() = list.hasNext
    override fun prev() = list.prev()
    override fun next() = list.next()
    override val layoutHint get() = list.layoutHint
}

/** fcitx's `InputPanel`; the overlay (the input method's name) shows while the rest is empty. */
class Panel {
    var preedit: FormattedText = FormattedText.Empty
    var clientPreedit: FormattedText = FormattedText.Empty
    var auxUp: FormattedText = FormattedText.Empty
    var auxDown: FormattedText = FormattedText.Empty
    var candidateList: CandidateList? = null
    var overlayMessage: String = ""

    var quickPhrase: Boolean = false

    /** [preedit] is a session code, not a temp mode's text: the app may write it into the field (编码行 输入框里). */
    var code: Boolean = false

    /** Everything, the overlay too. */
    fun reset() {
        preedit = FormattedText.Empty
        clientPreedit = FormattedText.Empty
        auxUp = FormattedText.Empty
        auxDown = FormattedText.Empty
        candidateList = null
        overlayMessage = ""
        quickPhrase = false
        code = false
    }

    /** The overlay does not count; text counts by characters, not fcitx's segments (no panel reaches the difference). */
    val empty: Boolean
        get() = preedit.isEmpty() && clientPreedit.isEmpty() && (candidateList?.size ?: 0) == 0 &&
            auxUp.isEmpty() && auxDown.isEmpty()
}
