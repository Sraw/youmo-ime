/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText

/**
 * A candidate on a [CandidateList], fcitx's `CandidateWord`: its [text], the [comment] beside it, what
 * picking it does. Named apart from the app's `core.CandidateWord`, the [CandidateEntry] the host sends.
 */
interface PanelCandidate {
    val text: String
    val comment: String get() = ""

    /** 候选词注音's, one per code point of [text] ([org.fcitx.fcitx5.android.engine.session.Snapshot.readings]); empty for none. */
    val readings: List<String> get() = emptyList()

    /** Shown as it is put in, never through the output filters (traditional): the user's own quick phrases. */
    val asIs: Boolean get() = false

    /** Picked (a tap, a key): the list it is on may be replaced by what this does. */
    fun select(ic: InputContext)
}

/** One item of a candidate's long-press menu, fcitx's `CandidateAction`: [id] is what [CandidateList.triggerAction] is given back. */
data class CandidateAction(val id: Int, val text: String)

/**
 * The candidates on a [Panel], fcitx's `CandidateList` with the parts the frontend reads
 * (`androidfrontend.cpp:94-293`): a page of [size] with [label]s and a [cursorIndex]; a bulk list
 * ([bulk]) is also read as a whole ([candidateFromAll], [totalSize], -1 not yet counted); a
 * pageable one ([pageable]) turns its pages; an actionable one has a long-press menu.
 *
 * An index out of range is null here, where fcitx throws `std::invalid_argument`.
 */
interface CandidateList {
    /** How many the page shown has. */
    val size: Int

    fun label(index: Int): String

    /** The [index]th of the page shown. */
    fun candidate(index: Int): PanelCandidate?

    /** The highlighted one of the page, what space would pick; -1 none. */
    val cursorIndex: Int

    val bulk: Boolean get() = false

    /** All of them, -1 while not counted ([bulk] lists). */
    val totalSize: Int get() = size

    /** The [index]th of all ([bulk] lists); else the page's. */
    fun candidateFromAll(index: Int): PanelCandidate? = candidate(index)

    val pageable: Boolean get() = false

    /** `CandidateList::layoutHint`, what the paged window's orientation follows when automatic (Unicode's: vertical). */
    val layoutHint: LayoutHint get() = LayoutHint.NotSet

    val hasPrev: Boolean get() = false
    val hasNext: Boolean get() = false
    fun prev() {}
    fun next() {}

    /** The long-press menu of [word], one of this list's. */
    fun actions(word: PanelCandidate): List<CandidateAction> = emptyList()

    fun triggerAction(word: PanelCandidate, id: Int) {}
}

/**
 * A [TempCandidateList] as the panel's list (fcitx's `CommonCandidateList`, `fcitx/candidatelist.cpp:465-700`:
 * bulk, pageable, labelled): a pick is the candidate's own select. The temp modes' lists and
 * punctuation's marks (`androidengine.cpp:534-541`) are shown through it, one pager for both.
 */
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

/**
 * One context's input panel, fcitx's `InputPanel` (`fcitx/inputpanel.cpp`): the preedit above the
 * candidates, the client preedit shown in the app, the aux lines, the candidates, and the overlay
 * message (the input method's name) shown while the rest is empty.
 */
class Panel {
    var preedit: FormattedText = FormattedText.Empty
    var clientPreedit: FormattedText = FormattedText.Empty
    var auxUp: FormattedText = FormattedText.Empty
    var auxDown: FormattedText = FormattedText.Empty
    var candidateList: CandidateList? = null
    var overlayMessage: String = ""

    /** A quick input's panel ([TempPanel.quickPhrase]). */
    var quickPhrase: Boolean = false

    /** `InputPanel::reset` (`inputpanel.cpp:121-132`): everything, the overlay too. */
    fun reset() {
        preedit = FormattedText.Empty
        clientPreedit = FormattedText.Empty
        auxUp = FormattedText.Empty
        auxDown = FormattedText.Empty
        candidateList = null
        overlayMessage = ""
        quickPhrase = false
    }

    /**
     * `InputPanel::empty` (`inputpanel.cpp:134-139`): the overlay does not count. Text counts here by
     * its characters, fcitx's by its segments (one empty segment is not empty there, as Unicode's
     * preedit before anything is typed); no panel reaches the difference, as aux is set with it.
     */
    val empty: Boolean
        get() = preedit.isEmpty() && clientPreedit.isEmpty() && (candidateList?.size ?: 0) == 0 &&
            auxUp.isEmpty() && auxDown.isEmpty()
}
