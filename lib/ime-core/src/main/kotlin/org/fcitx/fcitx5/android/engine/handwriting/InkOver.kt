/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * A Chinese input method's [session] written on over its keys (the toolbar's 手写): [Action.Ink] goes to its own
 * [HandwritingSession], which takes every action while it shows anything. A letter puts its best sentence in and
 * types into [session]; a first stroke while [session] has input puts that in first, as space would.
 */
class InkOver(
    val session: Session,
    private val makeHandwriting: () -> HandwritingSession,
) : Session by session {

    private var made: HandwritingSession? = null

    fun handwriting(): HandwritingSession = made ?: makeHandwriting().also {
        it.learning = session.learning
        flags?.let(it::fieldFlags)
        made = it
        it.apply(Action.Context(written))
    }

    val handwritingMade: HandwritingSession? get() = made

    private var inner: Snapshot? = null
    private var inked: Snapshot? = null

    private var flags: Int? = null

    private var written = ""

    val inking: Boolean get() = inked?.let { it.preedit.isNotEmpty() || it.candidates.isNotEmpty() } == true

    private val typing: Boolean get() = !inking && innerTyped(inner)

    private fun innerTyped(s: Snapshot?) = s != null && s.preedit.isNotEmpty() && !s.predicting

    private var innerWrote = false

    // the last action was the handwriting's: a reread is its, though it shows nothing now
    private var lastInk = false

    override var learning: Boolean
        get() = session.learning
        set(value) {
            session.learning = value
            made?.learning = value
        }

    override fun apply(action: Action): Snapshot = when {
        action is Action.Context || action == Action.Reset -> both(action)
        action is Action.Ink -> ink(action)
        !inking -> innerApply(action)
        typesLetter(action) -> letter(action)
        else -> inkApply(action)
    }

    private fun both(action: Action): Snapshot {
        written = if (action is Action.Context) action.before.takeLast(Action.Context.HOST_CHARS) else ""
        made?.apply(action)
        inked = null
        return innerApply(action).also { innerWrote = false }
    }

    /** The input ended: what is written is put in as shown, then a reset for [session]. */
    fun endInput(): Snapshot {
        val put = if (inking) inkApply(Action.CommitRaw) else null
        val after = both(Action.Reset)
        return put?.let { then(it, after) } ?: after
    }

    private fun ink(action: Action.Ink): Snapshot {
        // a reading of a piece already ended (the pad's word came late) is dropped: [session] keeps its input, else 中's
        // late reading would put a hardware n's first candidate in
        if (!inking && action.piece != HandwritingSession.NO_PIECE && action.piece <= handwriting().ended) {
            return still(handwriting().apply(action))
        }
        val put = if (typing) putTyped() else null
        if (!inking) {
            if (inner?.predicting == true) innerApply(Action.Context(written))
            if (innerWrote) made?.apply(Action.Context(written))
            innerWrote = false
        }
        val written = inkApply(action)
        return put?.let { then(it, written) } ?: written
    }

    private fun still(dropped: Snapshot): Snapshot = inner?.copy(commit = "", deleteBefore = 0, picked = false) ?: dropped

    private fun letter(action: Action): Snapshot {
        val put = inkApply(Action.CommitRaw)
        return then(put, innerApply(action))
    }

    private fun putTyped(): Snapshot {
        var put = innerApply(Action.Select(0))
        var picks = 1
        while (typing && picks < MAX_PICKS) {
            val before = inner?.preedit
            put = then(put, innerApply(Action.Select(0)))
            picks++
            if (inner?.preedit == before) break
        }
        return if (typing) then(put, innerApply(Action.CommitRaw)) else put
    }

    // [after]'s deletes come off [first]'s text first, then off the text before it
    private fun then(first: Snapshot, after: Snapshot): Snapshot {
        val n = first.commit.codePointCount(0, first.commit.length)
        val cut = minOf(after.deleteBefore, n)
        val kept = first.commit.substring(0, first.commit.offsetByCodePoints(0, n - cut))
        return after.copy(commit = kept + after.commit, deleteBefore = first.deleteBefore + after.deleteBefore - cut)
    }

    private fun typesLetter(action: Action) = when (action) {
        is Action.Key -> session.reads(action.char)
        is Action.Letter, is Action.NineKey, is Action.Syllable -> true
        else -> false
    }

    private fun innerApply(action: Action): Snapshot = session.apply(action).also {
        lastInk = false
        inner = it
        val changed = it.commit.isNotEmpty() || it.deleteBefore > 0
        if (changed) innerWrote = true
        wrote(it)
        // tell the handwriting at once, so its journal knows the text after its sentence is not its own
        if (it.commit.isNotEmpty()) made?.apply(Action.Context(written))?.let { h -> inked = h }
        // the handwriting hears an app backspace too, to take back a sentence it put in (FieldJournal)
        if (action == Action.Backspace && !it.handled) made?.apply(action)?.let { h -> inked = h }
    }

    private fun inkApply(action: Action): Snapshot {
        val h = handwriting()
        val s = h.apply(action)
        inked = s
        lastInk = true
        wrote(s)
        if (s.commit.isNotEmpty()) inner = session.apply(Action.Context(written))
        return s
    }

    private fun wrote(s: Snapshot) {
        if (s.deleteBefore == 0 && s.commit.isEmpty()) return
        val n = written.codePointCount(0, written.length)
        val kept = n - minOf(s.deleteBefore, n)
        written = (written.substring(0, written.offsetByCodePoints(0, kept)) + s.commit).takeLast(Action.Context.HOST_CHARS)
    }

    private val shown: Session get() = made?.takeIf { inking } ?: session

    override fun candidates(from: Int, count: Int): List<Choice> = shown.candidates(from, count)

    override fun offers(index: Int): Set<Offer> = shown.offers(index)

    override fun why(index: Int): Why? = shown.why(index)

    override fun readingOf(index: Int): List<WordReading>? = shown.readingOf(index)

    override fun lookedUp(index: Int): String? = shown.lookedUp(index)

    override fun blockScope(index: Int): BlockScope? = shown.blockScope(index)

    override fun reread(): Snapshot? = if (inking || lastInk) made?.reread()?.also { inked = it } else session.reread()

    override val rereads: Boolean get() = if (inking || lastInk) true else session.rereads

    override val takesEveryCharacter: Boolean get() = !inking && session.takesEveryCharacter

    override val picksByDigit: Boolean get() = !inking && session.picksByDigit

    override fun fieldFlags(flags: Int) {
        this.flags = flags
        session.fieldFlags(flags)
        made?.fieldFlags(flags)
    }

    override fun passedOn() {
        if (!inking) session.passedOn()
        made?.passedOn()
    }

    private companion object {
        const val MAX_PICKS = 16
    }
}
