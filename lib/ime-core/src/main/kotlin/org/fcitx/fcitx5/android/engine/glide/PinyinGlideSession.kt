/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * Full pinyin's [pinyin] session, glided on ([PinyinGlide]): an [Action.Glide] types the likeliest
 * reading of the glide [offer]ed as keys, after a syllable boundary where something is typed
 * already. A glide that starts the input has the next readings' words offered too, after the
 * first candidate: a path fits more than one reading (ni-h-ao and niao differ by a bend), and the
 * one typed may be the wrong one. Picking such a word types its reading instead and picks it there,
 * so it is learned and followed (联想) as any word picked. None [blocked] is offered.
 */
class PinyinGlideSession(
    private val pinyin: Session,
    private val blocked: (String) -> Boolean = { false },
    private val glide: () -> PinyinGlide,
) : Session by pinyin {

    private var offered: List<PinyinGlide.Reading> = emptyList()

    // the other readings' words, shown after the first candidate while the input is what the glide typed
    private var others: List<PinyinGlide.Reading> = emptyList()

    // the input the glide typed: once it changes (a key, a candidate picked of part of it), the others are no longer its
    private var glided = ""

    // what [pinyin] last showed, before the others were put in
    private var last: Snapshot? = null

    private val composing get() = last?.let(::typing) == true

    /** The glide that the next [Action.Glide] types: [Glide.path] read now, on the thread the session is used on. */
    fun offer(g: Glide) {
        offered = glide().decode(g.path, g.layout, READINGS)
    }

    override fun apply(action: Action): Snapshot {
        val s = when (action) {
            Action.Glide -> glided()
            is Action.Pick -> others(action.index)?.let(::pickOther) ?: pinyin.apply(action.copy(index = inner(action.index)))
            // of the page shown: the others are on the first
            is Action.Select -> if (last?.first != 0) pinyin.apply(action) else others(action.index)?.let(::pickOther) ?: pinyin.apply(action.copy(index = inner(action.index)))
            is Action.Forget -> if (others(action.index) != null) unchanged() else pinyin.apply(action.copy(index = inner(action.index)))
            is Action.Pin -> if (others(action.index) != null) unchanged() else pinyin.apply(action.copy(index = inner(action.index)))
            is Action.Unpin -> if (others(action.index) != null) unchanged() else pinyin.apply(action.copy(index = inner(action.index)))
            is Action.Block -> if (others(action.index) != null) unchanged() else pinyin.apply(action.copy(index = inner(action.index)))
            else -> pinyin.apply(action)
        }
        return shown(settled(s))
    }

    // [s] as [pinyin] shows it, the others kept only while it shows the input the glide typed
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
        val best = readings.firstOrNull() ?: return pinyin.apply(Action.Glide)
        var commit = ""
        // after what is typed, a boundary; that may end it (笔画查字 leaves, its character committed)
        val before = if (composing) pinyin.apply(Action.Key(PinyinGlide.SEPARATOR)).also { commit += it.commit } else null
        val fresh = before == null || before.preedit.isEmpty() || before.predicting
        var s = before
        for (c in best.pinyin) s = pinyin.apply(Action.Key(c)).also { commit += it.commit }
        val shown = (s ?: return pinyin.apply(Action.Glide)).copy(commit = commit, handled = true)
        if (fresh) {
            glided = shown.preedit
            others = readings.drop(1).filter { !blocked(it.word) }.distinctBy { it.word }
        }
        return shown
    }

    // the reading whose word is at [index] (of all), if one is
    private fun others(index: Int) = others.getOrNull(index - 1)?.takeIf { index >= 1 }

    // [index] of all as [pinyin] has it: after the first, the others' places are not its
    private fun inner(index: Int) = if (index > others.size) index - others.size else index

    private fun pickOther(reading: PinyinGlide.Reading): Snapshot {
        others = emptyList()
        // the input taken back, not Reset: that drops what was committed before too, which the word follows
        takenBack(last)
        var s: Snapshot? = null
        for (c in reading.pinyin) s = pinyin.apply(Action.Key(c))
        val at = pinyin.candidates(0, SEARCHED).indexOfFirst { it.text == reading.word }
        if (at >= 0) return pinyin.apply(Action.Pick(at))
        // not among its candidates (the session holds it back): the word as shown, the input taken back
        return (takenBack(s) ?: pinyin.apply(Action.Refine)).copy(commit = reading.word, handled = true)
    }

    // [s]'s input deleted, a backspace a character: what the session shows then
    private fun takenBack(s: Snapshot?): Snapshot? {
        var shown = s
        var left = MAX_BACKSPACES
        while (shown != null && typing(shown) && left-- > 0) shown = pinyin.apply(Action.Backspace)
        return shown
    }

    private fun typing(s: Snapshot) = s.preedit.isNotEmpty() && !s.predicting

    // nothing to forget or pin of another reading's word: the input as it was
    private fun unchanged() = last?.copy(commit = "", deleteBefore = 0, handled = true) ?: pinyin.apply(Action.Refine)

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
        )
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        if (others.isEmpty()) return pinyin.candidates(from, count)
        val all = ArrayList<Choice>()
        val k = others.size
        // what is asked of the first and others together, then the rest from where [pinyin] has them
        if (from == 0 && count > 0) all += pinyin.candidates(0, 1)
        for (i in maxOf(from, 1) until minOf(from + count, k + 1)) all += Choice(others[i - 1].word)
        val rest = maxOf(from, k + 1)
        if (from + count > rest) all += pinyin.candidates(rest - k, from + count - rest)
        return all
    }

    override fun offers(index: Int): Set<Offer> = if (others(index) != null) emptySet() else pinyin.offers(inner(index))

    companion object {
        /** Readings a glide offers: the one typed and the others. */
        const val READINGS = 3

        // how far down its candidates another reading's word is looked for, once typed
        private const val SEARCHED = 50

        // the input a glide types is at most PinyinGlide.MAX_LETTERS and its boundaries
        private const val MAX_BACKSPACES = 64
    }
}
