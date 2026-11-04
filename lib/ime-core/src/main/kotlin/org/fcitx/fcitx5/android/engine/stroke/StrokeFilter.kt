/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Pager
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * 笔画筛选, as 触宝 has it: while [session] shows something typed, a stroke drawn on the keyboard
 * ([Action.Stroke]) keeps those of its candidates whose first character is written starting so
 * ([Strokes.startsWith]: either order of one written two ways), in the order [session] gives them;
 * the next stroke is that character's next. A candidate whose first character is no letter (an
 * emoji, a mark) goes as the one before it went: 🐑 stays after 羊. A Latin word, a character the
 * strokes do not know, goes. The first [lookAhead] of [session]'s candidates are looked through,
 * as far as a page asks.
 *
 * The preedit is [session]'s, then the strokes ([Strokes.shown]). Backspace takes the last stroke
 * back, the pinyin once none is left; nothing found, the bar is empty and Backspace takes the
 * stroke back all the same. A candidate picked (space for the first, a digit, a tap) is [session]'s
 * at its own index, learned and followed as there, the strokes gone; one of part of the input
 * leaves the rest typed, with no strokes. A key that ends [session]'s input (a mark, the nine keys'
 * 0) commits the first candidate shown, then goes on as there; one of part of it leaves the rest typed. A long press's offers are of
 * [session]'s candidate, the strokes kept after it. Any other action (a key read, the cursor moved,
 * Enter) drops the strokes and is [session]'s, which still shows what it showed before the first stroke.
 *
 * [strokes] is asked for at the first stroke: null, there are none, and a stroke does nothing.
 */
class StrokeFilter(
    /** What is filtered: the input method's session as made, read through for what is not this one's (a glide's readings, Engines.glide). */
    val session: Session,
    private val strokes: () -> Strokes?,
    pageSize: Int = StrokeLookup.DEFAULT_PAGE_SIZE,
    private val lookAhead: Int = LOOK_AHEAD,
) : Session by session {

    // the strokes drawn, 横竖撇捺折 as Strokes.KEYS
    private val drawn = StringBuilder()

    // what [session] showed last: what is filtered, and shown again as the last stroke goes
    private var under: Snapshot? = null

    // [session]'s candidates kept so far, of the first [scanned] looked through; [done] once there are no more to look at
    private val kept = ArrayList<Kept>()
    private var scanned = 0
    private var done = false

    // whether the candidate before the next to look at was kept: a mark or an emoji goes as it went
    private var lastKept = false
    private val pager = Pager(pageSize)

    private class Kept(val index: Int, val choice: Choice)

    /** Whether strokes are drawn: the candidates shown are those they keep. */
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
        if (!filtering) session.offers(index) else keptAt(index)?.let { session.offers(it.index) }.orEmpty()

    override fun readingOf(index: Int): List<WordReading>? = if (!filtering) session.readingOf(index) else keptAt(index)?.let { session.readingOf(it.index) }

    override fun why(index: Int): Why? = if (!filtering) session.why(index) else keptAt(index)?.let { session.why(it.index) }

    override fun lookedUp(index: Int): String? = if (!filtering) session.lookedUp(index) else keptAt(index)?.let { session.lookedUp(it.index) }

    override fun reread(): Snapshot? {
        val s = session.reread() ?: return null
        return if (filtering) after(s) else shown(s)
    }

    override fun passedOn() {
        end()
        session.passedOn()
    }

    private fun stroke(c: Char): Snapshot {
        // nothing typed (联想 shown, or nothing at all): no stroke to filter by, as the keyboard draws none then
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

    // a key that ends [session]'s input (a mark, the nine keys' 0, a 双拼 `;` where no syllable
    // starts) ends it as there, but with the first candidate shown, the strokes', not [session]'s own
    // first: picked, then the key goes on. One that reads only the start leaves the rest typed and the
    // key unsent, as a pick of it does: sent, [session] would commit its own guess at the rest
    private fun left(key: Action.Key): Snapshot {
        if (keptAt(0) == null) return passed(key)
        val picked = pick(0)
        if (typing(picked)) return picked.copy(handled = true)
        val next = shown(session.apply(key))
        return next.copy(commit = picked.commit + next.commit, deleteBefore = picked.deleteBefore + next.deleteBefore)
    }

    private fun pick(index: Int): Snapshot {
        val k = keptAt(index) ?: return snapshot()
        end()
        return shown(session.apply(Action.Pick(k.index)))
    }

    private inline fun pressed(index: Int, at: (Int) -> Action): Snapshot {
        val k = keptAt(index) ?: return snapshot()
        return after(session.apply(at(k.index)))
    }

    // [s], what [session] shows after an action taken under the strokes: filtered again, or itself once nothing is typed
    private fun after(s: Snapshot): Snapshot {
        shown(s)
        if (typing(s)) return refilter(keepPage = true)
        end()
        return s
    }

    // no page to turn to: the key (a hardware - or =) is the app's where nothing is kept, as in PinyinSession
    private fun turn(to: Int): Snapshot =
        if (pager.turn(to) { keptAt(it) != null }) snapshot() else snapshot().copy(handled = keptAt(0) != null)

    // [s], what [session] shows refined under the strokes (the host sends Refine at each pause):
    // what was kept stays where it shows as before, a pause in the sentence models' work then no look
    // through as many as LOOK_AHEAD again; else kept again, the page as it was if it is still there.
    // A refine moves the sentence it picks first (PinyinSession.refine), so an order it changed shows
    // on the first page; a reread may change any, and is kept again whole
    private fun again(s: Snapshot): Snapshot {
        val before = under
        val same = before != null && s.preedit == before.preedit && s.candidates == before.candidates && s.total == before.total
        if (!same || !typing(s)) return after(s)
        shown(s)
        return snapshot()
    }

    private fun refilter(keepPage: Boolean): Snapshot {
        val page = pager.page
        kept.clear()
        scanned = 0
        done = false
        lastKept = false
        if (keepPage) pager.keep(page) { keptAt(it) != null } else pager.reset()
        return snapshot()
    }

    private fun end() {
        drawn.setLength(0)
        kept.clear()
        scanned = 0
        done = false
        pager.reset()
    }

    // the [i]th candidate kept, looking through [session]'s as far as needed
    private fun keptAt(i: Int): Kept? {
        if (i < 0) return null
        val table = strokes() ?: return null
        while (kept.size <= i && !done) {
            val asked = minOf(CHUNK, lookAhead - scanned)
            val chunk = if (asked <= 0) emptyList() else session.candidates(scanned, asked)
            for (choice in chunk) {
                val keep = keeps(choice.text, table)
                if (keep) kept += Kept(scanned, choice)
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
        // an emoji, a mark: no strokes of its own, it follows the word it is offered after
        // (〇 is no letter to Java, but written as one)
        if (!Character.isLetterOrDigit(first) && !Character.isIdeographic(first)) return lastKept
        return first <= Char.MAX_VALUE.code && table.startsWith(first.toChar(), drawn)
    }

    private fun shown(s: Snapshot): Snapshot {
        under = s
        return s
    }

    private fun typing(s: Snapshot) = s.preedit.isNotEmpty() && !s.predicting

    // what [session] showed, as it was: nothing committed again
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
        /** How many of the session's candidates are looked through for those the strokes keep. */
        const val LOOK_AHEAD = 2000

        // the pinyin, then the strokes
        const val SEPARATOR = " "

        // how many candidates are asked for at a time
        private const val CHUNK = 32

        private val NOTHING = Snapshot("", "", emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false)
    }
}
