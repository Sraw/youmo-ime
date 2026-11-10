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
 * A Chinese input method's [session], written on over its keys (键盘手写, design 060-kbd-handwriting
 * §5), as [GlideSession][org.fcitx.fcitx5.android.engine.glide.GlideSession] glides on one: an
 * [Action.Ink] goes to a [HandwritingSession] of its own, made at the first ([handwriting]), as
 * handwriting's own is. While that shows anything (what is written, or what may follow it, 联想), every
 * action is its: the candidates, space, enter, backspace are handwriting's. A letter then puts the
 * best sentence in and is typed into [session], both in one snapshot; what was put in is told
 * [session] as what it goes on from.
 *
 * Nothing is written while [session] has something typed: an Ink then is dropped (the keyboard starts
 * none; its one exception, a letter tapped as a first dot, deletes the letter first). What [session]
 * offers after a word (联想) goes as writing starts.
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

    /**
     * Whether [session] has input typed, not what the handwriting shows: then nothing is written (the
     * keyboard starts no stroke, KeyInkGesture.mayWrite's typed; Engines.typedOnKeys).
     */
    val typing: Boolean get() = !inking && inner?.let { it.preedit.isNotEmpty() && !it.predicting } == true

    // [session] put text in or deleted since the handwriting last heard of the text before
    private var innerWrote = false

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

    private fun ink(action: Action.Ink): Snapshot {
        if (typing) {
            // dropped: taken, so not held for the next (Engines.inkSent), its piece ended, so the pad wipes it
            handwriting().drop(action)
            inner?.let { return it.copy(commit = "", deleteBefore = 0, handled = true) }
        }
        if (!inking) {
            // what [session] offered goes: told the text before again, its input none
            if (inner?.predicting == true) innerApply(Action.Context(written))
            // a new sentence goes on from the text before the cursor, as [session] left it (design §5)
            if (innerWrote) made?.apply(Action.Context(written))
            innerWrote = false
        }
        return inkApply(action)
    }

    // a letter typed while the handwriting shows: its best sentence put in, then the letter [session]'s
    private fun letter(action: Action): Snapshot {
        val put = inkApply(Action.CommitRaw)
        val typed = innerApply(action)
        return typed.copy(commit = put.commit + typed.commit, deleteBefore = put.deleteBefore)
    }

    private fun typesLetter(action: Action) = when (action) {
        is Action.Key -> session.reads(action.char)
        is Action.Letter, is Action.NineKey, is Action.Syllable -> true
        else -> false
    }

    private fun innerApply(action: Action): Snapshot = session.apply(action).also {
        if (it.commit.isNotEmpty() || it.deleteBefore > 0) innerWrote = true
        wrote(it)
        inner = it
        // a backspace the app does (nothing typed): the handwriting hears of it too, to take back a
        // sentence it put in and that is deleted at once (U-03, its FieldJournal)
        if (action == Action.Backspace && !it.handled) made?.apply(action)?.let { h -> inked = h }
    }

    private fun inkApply(action: Action): Snapshot {
        val h = handwriting()
        val s = h.apply(action)
        inked = s
        wrote(s)
        // what it put in told [session], as the text it goes on from
        if (s.commit.isNotEmpty()) inner = session.apply(Action.Context(written))
        return s
    }

    private fun wrote(s: Snapshot) {
        if (s.deleteBefore == 0 && s.commit.isEmpty()) return
        val kept = written.codePointCount(0, written.length) - minOf(s.deleteBefore, written.codePointCount(0, written.length))
        written = (written.substring(0, written.offsetByCodePoints(0, kept)) + s.commit).takeLast(Action.Context.HOST_CHARS)
    }

    private val shown: Session get() = made?.takeIf { inking } ?: session

    override fun candidates(from: Int, count: Int): List<Choice> = shown.candidates(from, count)

    override fun offers(index: Int): Set<Offer> = shown.offers(index)

    override fun why(index: Int): Why? = shown.why(index)

    override fun readingOf(index: Int): List<WordReading>? = shown.readingOf(index)

    override fun lookedUp(index: Int): String? = shown.lookedUp(index)

    override fun blockScope(index: Int): BlockScope? = shown.blockScope(index)

    override fun reread(): Snapshot? = made?.takeIf { inking }?.let { h -> h.reread()?.also { inked = it } } ?: session.reread()

    override val rereads: Boolean get() = if (inking) true else session.rereads

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
}
