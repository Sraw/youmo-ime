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
 * A Chinese input method's [session], written on over its keys (手写, the toolbar's: designs
 * 060-kbd-handwriting §5, 061-handwriting-toolbar), as [GlideSession][org.fcitx.fcitx5.android.engine.glide.GlideSession] glides on one: an
 * [Action.Ink] goes to a [HandwritingSession] of its own, made at the first ([handwriting]), as
 * handwriting's own is. While that shows anything (what is written, or what may follow it, 联想), every
 * action is its: the candidates, space, enter, backspace are handwriting's. A letter then puts the
 * best sentence in and is typed into [session], both in one snapshot; what was put in is told
 * [session] as what it goes on from.
 *
 * A first stroke while [session] has something typed (from before handwriting was on, a hardware key,
 * a swipe's command) puts that in first as space would, its first candidate, and is written then, both
 * in one snapshot. What [session] offers after a word (联想) goes as writing starts.
 */
class InkOver(
    val session: Session,
    private val makeHandwriting: () -> HandwritingSession,
) : Session by session {

    private var made: HandwritingSession? = null

    /** The handwriting written here, made now if it was not. */
    fun handwriting(): HandwritingSession = made ?: makeHandwriting().also {
        it.learning = session.learning
        flags?.let(it::fieldFlags)
        made = it
        // what it goes on from: the text before, as told [session]
        it.apply(Action.Context(written))
    }

    /** The handwriting written here, if any was. */
    val handwritingMade: HandwritingSession? get() = made

    // what each last showed
    private var inner: Snapshot? = null
    private var inked: Snapshot? = null

    private var flags: Int? = null

    // the text before the cursor as far as a context goes, for the handwriting made later and for [session] after it puts text in
    private var written = ""

    /** Whether the handwriting shows anything: then every action is its. */
    val inking: Boolean get() = inked?.let { it.preedit.isNotEmpty() || it.candidates.isNotEmpty() } == true

    // whether [session] has input typed, not what the handwriting shows: put in before a stroke is written
    private val typing: Boolean get() = !inking && innerTyped(inner)

    private fun innerTyped(s: Snapshot?) = s != null && s.preedit.isNotEmpty() && !s.predicting

    // [session] put text in or deleted since the handwriting last heard of the text before
    private var innerWrote = false

    // the last action was the handwriting's: what is read again is its, though it shows nothing now (its 联想
    // turned off, then taken back: offered again, as on 0.6.0's handwriting input method)
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

    // a reset drops what is written (清除, Escape, the backspace swipe), as pinyin's code
    private fun both(action: Action): Snapshot {
        written = if (action is Action.Context) action.before.takeLast(Action.Context.HOST_CHARS) else ""
        made?.apply(action)
        inked = null
        return innerApply(action).also { innerWrote = false }
    }

    /**
     * The input ended (the field left, the keyboard hidden or made anew, the cursor moved): what is written
     * kept, its sentence put in as it shows, as the handwriting input method's composing text was (DECISIONS
     * A2, 061-handwriting-toolbar LC-35); then a reset, pinyin's code going as ever.
     */
    fun endInput(): Snapshot {
        val put = if (inking) inkApply(Action.CommitRaw) else null
        val after = both(Action.Reset)
        return put?.let { then(it, after) } ?: after
    }

    private fun ink(action: Action.Ink): Snapshot {
        // what [session] has typed put in first, as space would: the stroke is the next character's
        val put = if (typing) putTyped() else null
        if (!inking) {
            // what [session] offered goes: told the text before again, its input none
            if (inner?.predicting == true) innerApply(Action.Context(written))
            // a new sentence goes on from the text before the cursor, as [session] left it (design §5)
            if (innerWrote) made?.apply(Action.Context(written))
            innerWrote = false
        }
        val written = inkApply(action)
        return put?.let { then(it, written) } ?: written
    }

    // a letter typed while the handwriting shows: its best sentence put in, then the letter [session]'s
    private fun letter(action: Action): Snapshot {
        val put = inkApply(Action.CommitRaw)
        return then(put, innerApply(action))
    }

    // [session]'s input put in as space would, its first candidate each time (a pinyin's may take part of
    // it), as typed if one leaves it as it was (no candidate)
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

    // [first]'s and [after]'s writes as one, [after]'s shown: what [after] deletes comes off what [first] put in
    // first, then off the text before it
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
        // what [session] put in told the handwriting at once: its journal then knows the text after its
        // sentence is not its own, and a backspace deletes that first (U-03)
        if (it.commit.isNotEmpty()) made?.apply(Action.Context(written))?.let { h -> inked = h }
        // a backspace the app does (nothing typed): the handwriting hears of it too, to take back a
        // sentence it put in and that is deleted at once (U-03, its FieldJournal)
        if (action == Action.Backspace && !it.handled) made?.apply(action)?.let { h -> inked = h }
    }

    private fun inkApply(action: Action): Snapshot {
        val h = handwriting()
        val s = h.apply(action)
        inked = s
        lastInk = true
        wrote(s)
        // what it put in told [session], as the text it goes on from
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

    // what shows asked alone: the handwriting's nothing to read again is not [session]'s snapshot, which does not show
    override fun reread(): Snapshot? = if (inking || lastInk) made?.reread()?.also { inked = it } else session.reread()

    override val rereads: Boolean get() = if (inking || lastInk) true else session.rereads

    override val takesEveryCharacter: Boolean get() = !inking && session.takesEveryCharacter

    override val picksByDigit: Boolean get() = !inking && session.picksByDigit

    override fun fieldFlags(flags: Int) {
        this.flags = flags
        session.fieldFlags(flags)
        made?.fieldFlags(flags)
    }

    // both hear of a key the app took: the handwriting's journal and 联想 as on its own keyboard
    override fun passedOn() {
        if (!inking) session.passedOn()
        made?.passedOn()
    }

    private companion object {
        // a pinyin's input picked a word at a time: more than its longest is typed as it is
        const val MAX_PICKS = 16
    }
}
