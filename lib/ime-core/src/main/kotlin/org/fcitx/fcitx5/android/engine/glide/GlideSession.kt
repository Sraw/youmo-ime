/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * A reading of a glide: the [keys] it types, as its input method reads them, its likeliest [word]
 * and the score; [pinyin], the word's syllables apart by spaces, where a pinyin glide read it.
 */
class GlideReading(val keys: String, val word: String, val score: Float, val pinyin: String = "")

/**
 * A Chinese input method's [session] session, glided on: an [Action.Glide] types the likeliest
 * reading of the glide [offer]ed ([read]: [PinyinGlide]'s for the pinyins, [TableGlide]'s for a
 * table), each key as [typed] has it, after [between] where something is typed already (pinyin's
 * syllable boundary, a table's first candidate picked); what is typed and shows no candidates (a
 * table's 空码, a lone u of 笔画查字 or its parts that match nothing) is taken back instead. A glide that starts the input has the next
 * readings' words offered too, after the first candidate: a path fits more than one reading
 * (ni-h-ao and niao differ by a bend), and the one typed may be the wrong one. Picking such a word
 * types its reading instead and picks it there, so it is learned and followed (联想) as any word
 * picked. None [blocked] is offered; [readings] in all, the one typed among them. Such a word is
 * looked for on the first page only: one the session lists on a later page shows there again.
 */
class GlideSession(
    private val session: Session,
    private val between: Action,
    private val blocked: (String) -> Boolean = { false },
    private val typed: (Char) -> Action = { Action.Key(it, glided = true) },
    private val readings: Int = READINGS,
    /** A word of the other readings committed though [session] holds it back, and the first it showed (asked only if wanted): for learning to record. */
    private val heldBack: (GlideReading, first: () -> String) -> Unit = { _, _ -> },
    private val read: (Glide, limit: Int) -> List<GlideReading>,
) : Session by session {

    private var offered: List<GlideReading> = emptyList()

    // the other readings' words, shown after the first candidate while the input is what the glide typed
    private var others: List<GlideReading> = emptyList()

    // the input the glide typed: once it changes (a key, a candidate picked of part of it), the others are no longer its
    private var glided = ""

    // what [session] last showed, before the others were put in
    private var last: Snapshot? = null

    // the text committed through this, as far as a context goes: what a word committed here as told follows
    private var written = ""

    private val composing get() = last?.let(::typing) == true

    /** The glide that the next [Action.Glide] types: [Glide.path] read now, on the thread the session is used on. */
    fun offer(g: Glide) {
        offered = read(g, readings)
    }

    override fun apply(action: Action): Snapshot {
        val s = when (action) {
            Action.Glide -> glided()
            is Action.Pick -> picked(action.index) { action.copy(index = it) }
            // of the page shown: the others are on the first
            is Action.Select -> if (last?.first != 0) session.apply(action) else picked(action.index) { action.copy(index = it) }
            is Action.Forget -> pressed(action.index) { action.copy(index = it) }
            is Action.Pin -> pressed(action.index) { action.copy(index = it) }
            is Action.Unpin -> pressed(action.index) { action.copy(index = it) }
            is Action.Block -> pressed(action.index) { action.copy(index = it) }
            else -> session.apply(action)
        }
        wrote(action, s)
        return shown(settled(s))
    }

    private fun wrote(action: Action, s: Snapshot) {
        val before = when (action) {
            Action.Reset -> ""
            is Action.Context -> action.before
            // in code points, as Snapshot.deleteBefore is (an emoji in place of a word past the BMP)
            else -> written.substring(0, written.offsetByCodePoints(written.length, -minOf(s.deleteBefore, written.codePointCount(0, written.length))))
        }
        written = (before + s.commit).takeLast(Action.Context.HOST_CHARS)
    }

    // another reading's word at [index] typed and picked, else [at] its index as [session] has it
    private inline fun picked(index: Int, at: (Int) -> Action) = others(index)?.let(::pickOther) ?: session.apply(at(inner(index)))

    // a long press's offer: nothing to do of another reading's word, else [at] its index as [session] has it
    private inline fun pressed(index: Int, at: (Int) -> Action) = if (others(index) != null) unchanged() else session.apply(at(inner(index)))

    // [s] as [session] shows it, the others kept only while it shows the input the glide typed
    private fun settled(s: Snapshot): Snapshot {
        last = s
        if (s.preedit != glided || s.predicting || s.candidates.isEmpty()) others = emptyList()
        // the first page, refined or the like, may have one of them now: not offered twice
        if (s.first == 0 && others.isNotEmpty()) others = others.filter { it.word !in s.candidates }
        return s
    }

    private fun glided(): Snapshot {
        val readings = offered
        offered = emptyList()
        others = emptyList()
        val best = readings.firstOrNull() ?: return session.apply(Action.Glide)
        var commit = ""
        // after what is typed, a boundary; that may end it (笔画查字 leaves, its character committed).
        // What shows no candidates is taken back: a table's 空码 would run on into the glide's code, a
        // lone u of 笔画查字 or its parts that match nothing be committed as typed
        val before = when {
            !composing -> null
            last?.candidates.isNullOrEmpty() -> takenBack(last)
            else -> session.apply(between).also { commit += it.commit }
        }
        val fresh = before == null || before.preedit.isEmpty() || before.predicting
        var s = before
        for (c in best.keys) s = session.apply(typed(c)).also { commit += it.commit }
        val shown = (s ?: return session.apply(Action.Glide)).copy(commit = commit, handled = true)
        if (fresh) {
            glided = shown.preedit
            others = readings.drop(1).filter { !blocked(it.word) }.distinctBy { it.word }
        }
        return shown
    }

    // the reading whose word is at [index] (of all), if one is
    private fun others(index: Int) = others.getOrNull(index - 1)?.takeIf { index >= 1 }

    // [index] of all as [session] has it: after the first, the others' places are not its
    private fun inner(index: Int) = if (index > others.size) index - others.size else index

    private fun pickOther(reading: GlideReading): Snapshot {
        others = emptyList()
        // the input taken back, not Reset: that drops what was committed before too, which the word follows
        takenBack(last)
        for (c in reading.keys) session.apply(typed(c))
        val at = session.candidates(0, SEARCHED).indexOfFirst { it.text == reading.word }
        if (at >= 0) return session.apply(Action.Pick(at))
        // not among its candidates (the session holds it back): the word as shown, the input dropped
        // and the word told the session as what the next input follows, after what was committed before,
        // as if committed there; not learned by the user model, but recorded
        heldBack(reading) { session.candidates(0, 1).firstOrNull()?.text.orEmpty() }
        return session.apply(Action.Context(written + reading.word)).copy(commit = reading.word, handled = true)
    }

    // [s]'s input deleted, a backspace a character: what the session shows then
    private fun takenBack(s: Snapshot?): Snapshot? {
        var shown = s
        var left = MAX_BACKSPACES
        while (shown != null && typing(shown) && left-- > 0) shown = session.apply(Action.Backspace)
        return shown
    }

    private fun typing(s: Snapshot) = s.preedit.isNotEmpty() && !s.predicting

    // nothing to forget or pin of another reading's word: the input as it was
    private fun unchanged() = last?.copy(commit = "", deleteBefore = 0, handled = true) ?: session.apply(Action.Refine)

    // [s] with the others after its first candidate, on the first page
    private fun shown(s: Snapshot): Snapshot {
        if (others.isEmpty() || s.candidates.isEmpty()) return s
        val k = others.size
        val total = if (s.total < 0) s.total else s.total + k
        if (s.first > 0) return s.copy(first = s.first + k, total = total)
        val words = others.map { it.word }
        return s.copy(
            candidates = s.candidates.take(1) + words + s.candidates.drop(1),
            hints = if (s.hints.isEmpty()) s.hints else s.hints.take(1) + words.map { "" } + s.hints.drop(1),
            total = total,
            // a table's selection keys pick of [session]'s own: none on the others, each still on its candidate
            labels = if (s.labels.isEmpty()) s.labels else s.labels.take(1) + " ".repeat(k) + s.labels.drop(1),
        )
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        if (others.isEmpty()) return session.candidates(from, count)
        val all = ArrayList<Choice>()
        val k = others.size
        // what is asked of the first and others together, then the rest from where [session] has them
        if (from == 0 && count > 0) all += session.candidates(0, 1)
        for (i in maxOf(from, 1) until minOf(from + count, k + 1)) all += Choice(others[i - 1].word)
        val rest = maxOf(from, k + 1)
        if (from + count > rest) all += session.candidates(rest - k, from + count - rest)
        return all
    }

    override fun offers(index: Int): Set<Offer> = if (others(index) != null) emptySet() else session.offers(inner(index))

    // the others are no candidates [session] ranked; its own a place on for each (review-int-runtime S2)
    override fun why(index: Int): Why? = if (others(index) != null) null else session.why(inner(index))

    override fun reread(): Snapshot? = session.reread()?.let { shown(settled(it)) }

    companion object {
        /** Readings a glide offers: the one typed and the others. */
        const val READINGS = 3

        /**
         * The nine keys': a key three or four letters, and a key pressed again not on the path
         * (ni-h-ao and nian both 6-4-2-6), a path reads far more; 你好 sixth of that one.
         */
        const val NINE_KEYS_READINGS = 7

        // how far down its candidates another reading's word is looked for, once typed
        private const val SEARCHED = 50

        // the input a glide types is at most PinyinGlide.MAX_LETTERS and its boundaries
        private const val MAX_BACKSPACES = 64
    }
}
