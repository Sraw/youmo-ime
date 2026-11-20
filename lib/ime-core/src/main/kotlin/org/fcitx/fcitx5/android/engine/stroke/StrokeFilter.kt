/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Pager
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * 笔画筛选, as 触宝 has it: while [session] shows something typed, each stroke drawn ([Action.Stroke]) keeps the
 * candidates whose first character starts so (the mainland order where [mainland] knows it, else
 * [Strokes.startsWith]), in [session]'s order. An emoji or mark goes as the candidate before it went; a Latin
 * word goes. The first [lookAhead] candidates are looked through, asked a CHUNK at a time.
 *
 * The first kept stays first and the first [singles] lone characters come right after it: a stroke is rarely drawn
 * for a word of two or more.
 *
 * Backspace takes the last stroke back; a pick, or a key ending the input, goes to [session] with the strokes'
 * first candidate; any other action drops the strokes and is [session]'s. [strokes] null: a stroke does nothing.
 */
class StrokeFilter(
    /** The input method's session, read through for what is not this one's (a glide's readings). */
    val session: Session,
    private val strokes: () -> Strokes?,
    pageSize: Int = StrokeLookup.DEFAULT_PAGE_SIZE,
    private val lookAhead: Int = LOOK_AHEAD,
    private val singles: Int = SINGLES,
    // null where the mainland order is not known
    private val mainland: (Int, CharSequence) -> Boolean? = { _, _ -> null },
) : Session by session {

    private val drawn = StringBuilder()

    // what [session] showed last: what is filtered, and shown again as the last stroke goes
    private var under: Snapshot? = null

    private val kept = ArrayList<Kept>()
    private var scanned = 0
    // the texts of [session]'s first [scanned]: a refine may reorder them past the page it shows
    private val seen = ArrayList<String>()
    private var done = false

    // a mark or an emoji goes as the candidate before it went
    private var lastKept = false
    private val pager = Pager(pageSize)

    private class Kept(val index: Int, val choice: Choice)

    // the order shown, as indices into [kept]: the first and its marks, the lone characters moved up, then the rest from [restFrom]
    private val order = ArrayList<Int>()
    private val moved = HashSet<Int>()
    private var restFrom = 0

    val filtering: Boolean get() = drawn.isNotEmpty()

    override fun apply(action: Action): Snapshot {
        if (action is Action.Stroke) return stroke(action.stroke)
        if (!filtering) return shown(session.apply(action))
        return when (action) {
            Action.Backspace -> erase()
            is Action.Select -> pager.at(action.index).let { if (it >= 0) pick(it) else snapshot() }
            is Action.Pick -> pick(action.index)
            is Action.Forget -> pressed(action.index) { action.copy(index = it) }
            is Action.Pin -> pressed(action.index) { action.copy(index = it) }
            is Action.Unpin -> pressed(action.index) { action.copy(index = it) }
            is Action.Block -> pressed(action.index) { action.copy(index = it) }
            Action.NextPage -> turn(pager.page + 1)
            Action.PreviousPage -> turn(pager.page - 1)
            Action.Refine -> again(session.apply(action))
            is Action.Key -> if (session.endsInput(action.char)) left(action) else passed(action)
            else -> passed(action)
        }
    }

    override fun candidates(from: Int, count: Int): List<Choice> =
        if (!filtering) session.candidates(from, count) else (from until from + count).mapNotNull { keptAt(it)?.choice }

    override fun offers(index: Int): Set<Offer> =
        if (!filtering) session.offers(index) else at(index)?.let { session.offers(it) }.orEmpty()

    override fun readingOf(index: Int): List<WordReading>? = if (!filtering) session.readingOf(index) else at(index)?.let { session.readingOf(it) }

    override fun why(index: Int): Why? = if (!filtering) session.why(index) else at(index)?.let { session.why(it) }

    override fun blockScope(index: Int): BlockScope? = if (!filtering) session.blockScope(index) else at(index)?.let { session.blockScope(it) }

    override fun lookedUp(index: Int): String? = if (!filtering) session.lookedUp(index) else at(index)?.let { session.lookedUp(it) }

    override fun reread(): Snapshot? {
        val s = session.reread() ?: return null
        return if (filtering) after(s) else shown(s)
    }

    override fun passedOn() {
        end()
        session.passedOn()
    }

    private fun stroke(c: Char): Snapshot {
        // nothing typed: no stroke to filter by, as the keyboard draws none then
        val typed = under?.let(::typing) == true
        if (!typed || c !in Strokes.KEYS || strokes() == null) return unchanged()
        drawn.append(c)
        return refilter(keepPage = false)
    }

    private fun erase(): Snapshot {
        drawn.setLength(drawn.length - 1)
        if (filtering) return refilter(keepPage = false)
        end()
        return unchanged()
    }

    private fun passed(action: Action): Snapshot {
        end()
        return shown(session.apply(action))
    }

    // a key ending [session]'s input commits the strokes' first candidate, then goes on; one reading only the start
    // leaves the rest typed and the key unsent, or [session] would commit its own guess at the rest
    private fun left(key: Action.Key): Snapshot {
        if (keptAt(0) == null) return passed(key)
        val picked = pick(0)
        if (typing(picked)) return picked.copy(handled = true)
        val next = shown(session.apply(key))
        return next.copy(commit = picked.commit + next.commit, deleteBefore = picked.deleteBefore + next.deleteBefore)
    }

    private fun pick(index: Int): Snapshot {
        val k = at(index) ?: return if (keptAt(index) != null) refilter(keepPage = true) else snapshot()
        end()
        return shown(session.apply(Action.Pick(k)))
    }

    private inline fun pressed(index: Int, action: (Int) -> Action): Snapshot {
        val k = at(index) ?: return snapshot()
        return after(session.apply(action(k)))
    }

    // where the [i]th shown is in [session]'s list now: the list may have moved under the strokes (a tap on 好看
    // once committed 估计, which the strokes do not keep)
    private fun at(i: Int): Int? {
        val k = keptAt(i) ?: return null
        if (session.candidates(k.index, 1).firstOrNull()?.text == k.choice.text) return k.index
        return session.candidates(0, scanned).indexOfFirst { it.text == k.choice.text }.takeIf { it >= 0 }
    }

    private fun after(s: Snapshot): Snapshot {
        shown(s)
        if (typing(s)) return refilter(keepPage = true)
        end()
        return s
    }

    // no page to turn to: the key is the app's where nothing is kept, as in PinyinSession
    private fun turn(to: Int): Snapshot =
        if (pager.turn(to) { keptAt(it) != null }) snapshot() else snapshot().copy(handled = keptAt(0) != null)

    // a refine reorders sentences possibly past the first page, so what was looked through is compared, not the page;
    // unchanged, nothing is looked through again. A reread is kept again whole
    private fun again(s: Snapshot): Snapshot {
        val before = under
        val same = before != null && s.preedit == before.preedit && s.candidates == before.candidates && s.total == before.total &&
            session.candidates(0, scanned).map { it.text } == seen
        if (!same || !typing(s)) return after(s)
        shown(s)
        return snapshot()
    }

    private fun refilter(keepPage: Boolean): Snapshot {
        val page = pager.page
        clearKept()
        lastKept = false
        if (keepPage) pager.keep(page) { keptAt(it) != null } else pager.reset()
        return snapshot()
    }

    private fun end() {
        drawn.setLength(0)
        clearKept()
        pager.reset()
    }

    private fun clearKept() {
        kept.clear()
        seen.clear()
        scanned = 0
        done = false
        order.clear()
        moved.clear()
        restFrom = 0
    }

    private fun keptAt(i: Int): Kept? {
        if (i < 0) return null
        if (singles <= 0) return inOrder(i)
        while (order.size <= i && extend()) Unit
        return order.getOrNull(i)?.let { kept[it] }
    }

    private fun extend(): Boolean {
        if (order.isEmpty()) {
            if (inOrder(0) == null) return false
            restFrom = group(0) { order += it }
            var at = restFrom
            var found = 0
            while (found < singles) {
                val k = inOrder(at) ?: break
                if (alone(k.choice.text)) {
                    group(at) { order += it; moved += it }
                    found++
                }
                at++
            }
            return true
        }
        while (true) {
            if (inOrder(restFrom) == null) return false
            val at = restFrom++
            if (at !in moved) {
                order += at
                return true
            }
        }
    }

    private inline fun group(at: Int, take: (Int) -> Unit): Int {
        take(at)
        var next = at + 1
        while (inOrder(next)?.let { mark(it.choice.text) } == true) take(next++)
        return next
    }

    private fun inOrder(i: Int): Kept? {
        if (i < 0) return null
        val table = strokes() ?: return null
        while (kept.size <= i && !done) {
            val asked = minOf(CHUNK, lookAhead - scanned)
            val chunk = if (asked <= 0) emptyList() else session.candidates(scanned, asked)
            for (choice in chunk) {
                val keep = keeps(choice.text, table)
                if (keep) kept += Kept(scanned, choice)
                seen += choice.text
                lastKept = keep
                scanned++
            }
            if (chunk.size < asked || asked <= 0) done = true
        }
        return kept.getOrNull(i)
    }

    private fun keeps(text: String, table: Strokes): Boolean {
        if (text.isEmpty()) return false
        val first = text.codePointAt(0)
        // an emoji, a mark: it follows the word it is offered after (〇 is no letter to Java, but written as one)
        if (mark(text)) return lastKept
        // the mainland's order alone where known: rime-stroke's others are mostly Taiwan's (艳, 小 start otherwise)
        mainland(first, drawn)?.let { return it }
        return first <= Char.MAX_VALUE.code && table.startsWith(first.toChar(), drawn)
    }

    private fun mark(text: String): Boolean {
        val first = text.codePointAt(0)
        return !Character.isLetterOrDigit(first) && !Character.isIdeographic(first)
    }

    private fun alone(text: String): Boolean = text.isNotEmpty() && Character.charCount(text.codePointAt(0)) == text.length &&
        Character.isIdeographic(text.codePointAt(0))

    private fun shown(s: Snapshot): Snapshot {
        under = s
        return s
    }

    private fun typing(s: Snapshot) = s.preedit.isNotEmpty() && !s.predicting

    private fun unchanged(): Snapshot = under?.copy(commit = "", deleteBefore = 0, handled = true) ?: NOTHING

    private fun snapshot(): Snapshot {
        val s = under ?: return NOTHING
        val shown = (pager.from until pager.from + pager.size).mapNotNull { keptAt(it) }
        val hints = shown.map { it.choice.hint }
        return Snapshot(
            commit = "",
            preedit = s.preedit + SEPARATOR + Strokes.shown(drawn),
            candidates = shown.map { it.choice.text },
            page = pager.page,
            hasPreviousPage = pager.hasPrevious,
            hasNextPage = keptAt(pager.from + pager.size) != null,
            handled = true,
            predicting = false,
            hints = if (hints.any(String::isNotEmpty)) hints else emptyList(),
            total = if (done) kept.size else -1,
            first = pager.from,
            actionable = s.actionable,
            syllables = s.syllables,
            syllablesId = s.syllablesId,
            refines = s.refines,
            cursor = s.cursor,
        )
    }

    companion object {
        const val LOOK_AHEAD = 2000

        /** How many lone characters come right after the first kept, unless set otherwise (首页单字, 0 to 5). */
        const val SINGLES = 2
        val SINGLES_RANGE = 0..5

        const val SEPARATOR = " "

        private const val CHUNK = 32

        private val NOTHING = Snapshot("", "", emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false)
    }
}
