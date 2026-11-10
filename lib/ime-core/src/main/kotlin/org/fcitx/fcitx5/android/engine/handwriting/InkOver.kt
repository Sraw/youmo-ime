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
    val typing: Boolean get() = !inking && innerTyped(inner)

    private fun innerTyped(s: Snapshot?) = s != null && s.preedit.isNotEmpty() && !s.predicting

    // how many actions [session] was given since its input was last none: a take-back deletes one only
    private var typedActions = 0

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

    // [takeBack] apart from the action: the action itself is what the handwriting keeps as taken (its took, Engines.inkSent)
    private fun ink(action: Action.Ink, takeBack: Boolean = action.takesBack): Snapshot {
        // a tap taken back as the first dot (KeyInkGesture): decided here, where what was typed is known, so a
        // backspace deletes that tap's letter and nothing else (another key between, a pick, a long press: kept).
        // One action's input that one Backspace leaves typed (none known) loses that much and the Ink is dropped
        if (takeBack && typing && typedActions == 1) {
            val back = innerApply(Action.Backspace)
            if (!typing) return ink(action, takeBack = false).let { it.copy(deleteBefore = it.deleteBefore + back.deleteBefore) }
        }
        if (typing) {
            // dropped: taken, so not held for the next (Engines.inkSent), its piece ended, so the pad wipes it.
            // The handwriting is made for it if it was not: what it took and ended live there, read by Engines
            // (inkSent, inkEnded); once per session, so not worth a second home for them here
            handwriting().drop(action)
            // what [session] last showed, as it was; not what came with it then (a refine to run, a pick): not this Ink's
            inner?.let { return it.copy(commit = "", deleteBefore = 0, handled = true, refines = false, picked = false) }
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
        // what the letter's answer deletes comes off what was put in first, then off the text before it
        val n = put.commit.codePointCount(0, put.commit.length)
        val cut = minOf(typed.deleteBefore, n)
        val kept = put.commit.substring(0, put.commit.offsetByCodePoints(0, n - cut))
        return typed.copy(commit = kept + typed.commit, deleteBefore = put.deleteBefore + typed.deleteBefore - cut)
    }

    private fun typesLetter(action: Action) = when (action) {
        is Action.Key -> session.reads(action.char)
        is Action.Letter, is Action.NineKey, is Action.Syllable -> true
        else -> false
    }

    private fun innerApply(action: Action): Snapshot = session.apply(action).also {
        val was = innerTyped(inner)
        inner = it
        typedActions = when {
            !innerTyped(it) -> 0
            !was -> 1
            // the input as it was: its candidates told again, or reordered (a refine some 30 ms after a, o, e)
            action is Action.Context || action == Action.Refine -> typedActions
            else -> typedActions + 1
        }
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
    override fun reread(): Snapshot? = if (inking) made?.reread()?.also { inked = it } else session.reread()

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
