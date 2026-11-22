/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/** A glide reading: the [keys] it types, its likeliest [word] and score; [pinyin]: the word's syllables space-separated, for a pinyin glide. */
class GlideReading(val keys: String, val word: String, val score: Float, val pinyin: String = "")

/**
 * Glide on a Chinese input method's [session]: types the likeliest reading ([PinyinGlide] or [TableGlide]), or
 * takes it back if it shows no candidates (a table's 空码). A glide starting the input also offers the other
 * readings' words after the first candidate (a path fits several: ni-h-ao vs niao); picking one types and picks
 * its reading so it is learned like any pick. They appear on the first page only.
 */
class GlideSession(
    private val session: Session,
    private val between: Action,
    private val blocked: (String) -> Boolean = { false },
    private val typed: (Char) -> Action = { Action.Key(it, glided = true) },
    private val readings: Int = READINGS,
    private val heldBack: (GlideReading, first: () -> String) -> Unit = { _, _ -> },
    private val blockOther: ((GlideReading) -> (() -> Unit)?)? = null,
    private val read: (Glide, limit: Int) -> List<GlideReading>,
) : Session by session {

    private var others: List<GlideReading> = emptyList()

    // the input the glide typed: once it changes the others no longer apply
    private var glided = ""

    private var last: Snapshot? = null

    private var written = ""

    private val composing get() = last?.let(::typing) == true

    /** Reads [g] on the session's thread before the key is sent: a path the decoders fail on loses the glide alone, not the key. */
    fun read(g: Glide): Action.Glide = Action.Glide(g, read(g, readings))

    override fun apply(action: Action): Snapshot {
        val s = when (action) {
            is Action.Glide -> glided(action)
            is Action.Pick -> picked(action.index) { action.copy(index = it) }
            is Action.Select -> if (last?.first != 0) session.apply(action) else picked(action.index) { action.copy(index = it) }
            is Action.Forget -> pressed(action.index) { action.copy(index = it) }
            is Action.Pin -> pressed(action.index) { action.copy(index = it) }
            is Action.Unpin -> pressed(action.index) { action.copy(index = it) }
            is Action.Block -> others(action.index)?.let(::blockOtherReading) ?: pressed(action.index) { action.copy(index = it) }
            else -> session.apply(action)
        }
        wrote(action, s)
        return shown(settled(s))
    }

    private fun wrote(action: Action, s: Snapshot) {
        val before = when (action) {
            Action.Reset -> ""
            is Action.Context -> action.before
            // in code points, as Snapshot.deleteBefore (an emoji past the BMP)
            else -> written.substring(0, written.offsetByCodePoints(written.length, -minOf(s.deleteBefore, written.codePointCount(0, written.length))))
        }
        written = (before + s.commit).takeLast(Action.Context.HOST_CHARS)
    }

    private inline fun picked(index: Int, at: (Int) -> Action) = others(index)?.let(::pickOther) ?: session.apply(at(inner(index)))

    private inline fun pressed(index: Int, at: (Int) -> Action) = if (others(index) != null) unchanged() else session.apply(at(inner(index)))

    private fun settled(s: Snapshot): Snapshot {
        last = s
        if (s.preedit != glided || s.predicting || s.candidates.isEmpty()) others = emptyList()
        // the first page may now contain one: not offered twice
        if (s.first == 0 && others.isNotEmpty()) others = others.filter { it.word !in s.candidates }
        return s
    }

    private fun glided(action: Action.Glide): Snapshot {
        val readings = action.readings ?: action.glide?.let { read(it, this.readings) }.orEmpty()
        others = emptyList()
        val best = readings.firstOrNull() ?: return session.apply(action)
        var commit = ""
        // what shows no candidates is taken back: a table's 空码 would run on into the glide's code, and a lone u of
        // 笔画查字 would commit as typed
        val before = when {
            !composing -> null
            last?.candidates.isNullOrEmpty() -> takenBack(last)
            else -> session.apply(between).also { commit += it.commit }
        }
        val fresh = before == null || before.preedit.isEmpty() || before.predicting
        var s = before
        for (c in best.keys) s = session.apply(typed(c)).also { commit += it.commit }
        val shown = (s ?: return session.apply(action)).copy(commit = commit, handled = true)
        if (fresh) {
            glided = shown.preedit
            others = readings.drop(1).filter { !blocked(it.word) }.distinctBy { it.word }
        }
        return shown
    }

    private fun others(index: Int) = others.getOrNull(index - 1)?.takeIf { index >= 1 }

    private fun inner(index: Int) = if (index > others.size) index - others.size else index

    private fun pickOther(reading: GlideReading): Snapshot {
        others = emptyList()
        // the input taken back, not Reset: that drops what was committed before too, which the word follows
        takenBack(last)
        for (c in reading.keys) session.apply(typed(c))
        val at = session.candidates(0, SEARCHED).indexOfFirst { it.text == reading.word }
        if (at >= 0) return session.apply(Action.Pick(at))
        // held back by the session: committed as shown and told to the session as context; not learned, but recorded
        heldBack(reading) { session.candidates(0, 1).firstOrNull()?.text.orEmpty() }
        return session.apply(Action.Context(written + reading.word)).copy(commit = reading.word, handled = true)
    }

    private fun takenBack(s: Snapshot?): Snapshot? {
        var shown = s
        var left = MAX_BACKSPACES
        while (shown != null && typing(shown) && left-- > 0) shown = session.apply(Action.Backspace)
        return shown
    }

    private fun typing(s: Snapshot) = s.preedit.isNotEmpty() && !s.predicting

    private fun blockOtherReading(reading: GlideReading): Snapshot {
        if (!session.learning) return unchanged()
        val block = blockOther?.invoke(reading) ?: return unchanged()
        block()
        // gone for good from this glide's others: undoing the block shows it from the next glide
        others = others - reading
        return unchanged()
    }

    // only reached with others offered, so after a snapshot was settled
    private fun unchanged() = checkNotNull(last).copy(commit = "", deleteBefore = 0, handled = true)

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
            // a table's selection keys pick [session]'s own: none on the others
            labels = if (s.labels.isEmpty()) s.labels else s.labels.take(1) + " ".repeat(k) + s.labels.drop(1),
        )
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        if (others.isEmpty()) return session.candidates(from, count)
        val all = ArrayList<Choice>()
        val k = others.size
        if (from == 0 && count > 0) all += session.candidates(0, 1)
        for (i in maxOf(from, 1) until minOf(from + count, k + 1)) all += Choice(others[i - 1].word)
        val rest = maxOf(from, k + 1)
        if (from + count > rest) all += session.candidates(rest - k, from + count - rest)
        return all
    }

    override fun offers(index: Int): Set<Offer> {
        val other = others(index) ?: return session.offers(inner(index))
        return if (session.learning && blockOther?.invoke(other) != null) setOf(Offer.BLOCK) else emptySet()
    }

    override fun why(index: Int): Why? = if (others(index) != null) null else session.why(inner(index))

    override fun readingOf(index: Int): List<WordReading>? = if (others(index) != null) null else session.readingOf(inner(index))

    override fun blockScope(index: Int): BlockScope? = if (others(index) != null) null else session.blockScope(inner(index))

    override fun lookedUp(index: Int): String? = if (others(index) != null) null else session.lookedUp(inner(index))

    override fun reread(): Snapshot? = session.reread()?.let { shown(settled(it)) }

    companion object {
        const val READINGS = 3

        /** Nine keys read far more per path (3-4 letters a key); 你好 ranks about sixth. */
        const val NINE_KEYS_READINGS = 7

        private const val SEARCHED = 50

        private const val MAX_BACKSPACES = 64
    }
}
