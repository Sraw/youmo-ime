/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText

/** A candidate on a [CandidateList], fcitx's `CandidateWord`: its [text], the [comment] beside it, what picking it does. */
interface CandidateWord {
    val text: String
    val comment: String get() = ""

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
    fun candidate(index: Int): CandidateWord?

    /** The highlighted one of the page, what space would pick; -1 none. */
    val cursorIndex: Int

    val bulk: Boolean get() = false

    /** All of them, -1 while not counted ([bulk] lists). */
    val totalSize: Int get() = size

    /** The [index]th of all ([bulk] lists); else the page's. */
    fun candidateFromAll(index: Int): CandidateWord? = candidate(index)

    val pageable: Boolean get() = false

    /** `CandidateList::layoutHint`, what the paged window's orientation follows when automatic (Unicode's: vertical). */
    val layoutHint: FcitxEvent.PagedCandidateEvent.LayoutHint get() = FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet

    val hasPrev: Boolean get() = false
    val hasNext: Boolean get() = false
    fun prev() {}
    fun next() {}

    /** The long-press menu of [word], one of this list's. */
    fun actions(word: CandidateWord): List<CandidateAction> = emptyList()

    fun triggerAction(word: CandidateWord, id: Int) {}
}

/**
 * fcitx's `CommonCandidateList` (`fcitx/candidatelist.cpp:465-700`): a fixed list of words, paged
 * by [pageSize] (5 by default), bulk and pageable, labels empty unless given, a global cursor.
 * What punctuation's marks are offered in (`androidengine.cpp:534-541`); quick phrase and Unicode
 * may use it too.
 */
open class CommonCandidateList(words: List<CandidateWord> = emptyList()) : CandidateList {
    private val words = words.toMutableList()
    private var labels: List<String> = emptyList()
    private var page = 0

    var pageSize: Int = DEFAULT_PAGE_SIZE
        set(value) {
            require(value >= 1) { "invalid page size" }
            field = value
            page = 0
        }

    /** The cursor among all of them, -1 for none. */
    var globalCursorIndex: Int = -1
        set(value) {
            require(value < words.size) { "invalid index" }
            field = if (value < 0) -1 else value
        }

    fun append(word: CandidateWord) {
        words += word
    }

    fun setLabels(labels: List<String>) {
        this.labels = labels
    }

    override val size: Int get() = (words.size - page * pageSize).coerceIn(0, pageSize)

    override fun label(index: Int): String = if (index in 0 until size) labels.getOrElse(index) { "" } else ""

    override fun candidate(index: Int): CandidateWord? = if (index in 0 until size) words[page * pageSize + index] else null

    override val cursorIndex: Int
        get() = if (globalCursorIndex >= 0 && globalCursorIndex / pageSize == page) globalCursorIndex % pageSize else -1

    override val bulk get() = true
    override val totalSize get() = words.size
    override fun candidateFromAll(index: Int): CandidateWord? = words.getOrNull(index)

    override val pageable get() = true
    override val hasPrev get() = page > 0
    override val hasNext get() = page + 1 < (words.size + pageSize - 1) / pageSize

    override fun prev() {
        if (hasPrev) page--
    }

    override fun next() {
        if (hasNext) page++
    }

    companion object {
        /** `CommonCandidateListPrivate::pageSize_`, and fcitx's `DefaultPageSize` kept as a constant (design §1.4). */
        const val DEFAULT_PAGE_SIZE = 5
    }
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

    /** `InputPanel::reset` (`inputpanel.cpp:121-132`): everything, the overlay too. */
    fun reset() {
        preedit = FormattedText.Empty
        clientPreedit = FormattedText.Empty
        auxUp = FormattedText.Empty
        auxDown = FormattedText.Empty
        candidateList = null
        overlayMessage = ""
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
