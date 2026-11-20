/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.HandedBack
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Pager
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * Pinyin with a character looked up by strokes, as 搜狗 has it: [KEY] with nothing typed starts it (no pinyin
 * starts with u), then `h s p n z` are strokes; each candidate shows its pinyin. A pick commits and pinyin goes
 * on after it. Any other letter ends the lookup and is typed into pinyin with its keys (`usb`, `uan`); any other
 * key commits the first candidate and is the app's. The strokes keep a cursor as pinyin does.
 *
 * [strokes] null: [KEY] is [pinyin]'s. A pick goes to [follow] and a letter-ended lookup's keys to [retype].
 * A character found may be blocked under the strokes alone ([blockUnder], under [BLOCK_TABLE]).
 */
class StrokeLookup(
    private val pinyin: Session,
    private val strokes: () -> Strokes?,
    private val reading: (String) -> String = { "" },
    pageSize: Int = DEFAULT_PAGE_SIZE,
    private val blocked: (String) -> Boolean = { false },
    private val follow: (String) -> Snapshot = { pinyin.apply(Action.Context(it)) },
    private val retype: (String) -> Snapshot = { keys ->
        val typed = keys.map { pinyin.apply(Action.Key(it)) }
        typed.last().copy(commit = typed.joinToString("") { it.commit })
    },
    private val blockedUnder: (strokes: String) -> Set<String> = { emptySet() },
    private val blockUnder: ((strokes: String, text: String) -> Unit)? = null,
) : Session {

    private var looking = false
    private val input = StringBuilder()
    private var cursor = 0
    private var found = Found.none()
    private val pager = Pager(pageSize)

    // only with pinyin showing nothing typed does the key start a lookup
    private var idle = true

    override var learning: Boolean
        get() = pinyin.learning
        set(value) {
            pinyin.learning = value
        }

    override fun reads(c: Char): Boolean = if (looking) c in 'a'..'z' else (c == KEY && idle) || pinyin.reads(c)

    // pinyin's own answer while it has the keys (双拼's ; is read only where it stands)
    override fun endsInput(c: Char): Boolean = if (looking) !reads(c) else !(c == KEY && idle) && pinyin.endsInput(c)

    override fun apply(action: Action): Snapshot {
        if (!looking) {
            val starts = action is Action.Key && action.char == KEY
            if (starts && idle && strokes() != null) {
                looking = true
                return lookUp()
            }
            return pinyin.apply(action).also { idle = it.preedit.isEmpty() }
        }
        return whileLooking(action)
    }

    private fun whileLooking(action: Action): Snapshot =
        when (action) {
            is Action.Key -> type(action.char)
            Action.Backspace -> when {
                input.isEmpty() -> end()
                cursor == 0 -> snapshot()
                else -> lookUp { input.deleteCharAt(--cursor) }
            }
            is Action.Select -> pager.at(action.index).let { if (it >= 0) pick(it) else snapshot() }
            is Action.Pick -> pick(action.index)
            Action.NextPage -> turn(pager.page + 1)
            Action.PreviousPage -> turn(pager.page - 1)
            Action.CommitRaw -> leave("$KEY$input")
            Action.Reset, is Action.Context -> {
                end()
                pinyin.apply(action).also { idle = it.preedit.isEmpty() }
            }
            // the preedit is the key, then a stroke a character
            is Action.Cursor -> moved(action.at.coerceAtLeast(1) - 1)
            is Action.Step -> moved(action.applyTo(cursor, input.length))
            is Action.Block -> block(action.index)
            else -> snapshot()
        }

    override fun candidates(from: Int, count: Int): List<Choice> =
        if (!looking) pinyin.candidates(from, count)
        else found.page(from, count).map { Choice(it, reading(it)) }

    override fun offers(index: Int): Set<Offer> = if (!looking) pinyin.offers(index) else if (blockable(index)) setOf(Offer.BLOCK) else emptySet()

    private fun blockable(index: Int) = learning && blockUnder != null && input.isNotEmpty() && found[index] != null

    private fun block(index: Int): Snapshot {
        if (!blockable(index)) return snapshot()
        checkNotNull(blockUnder).invoke(input.toString(), checkNotNull(found[index]))
        return lookUp()
    }

    override fun why(index: Int): Why? = if (looking) null else pinyin.why(index)

    override fun readingOf(index: Int): List<WordReading>? = if (looking) null else pinyin.readingOf(index)

    override fun blockScope(index: Int): BlockScope? = if (!looking) pinyin.blockScope(index) else if (found[index] != null) BlockScope.UNDER_CODE else null

    override fun lookedUp(index: Int): String? = if (looking) found[index]?.let { "" } else pinyin.lookedUp(index)

    override fun reread(): Snapshot? = if (looking) null else pinyin.reread()

    override val rereads get() = !looking && pinyin.rereads

    override fun fieldFlags(flags: Int) = pinyin.fieldFlags(flags)

    // the members below must be forwarded: Session's empty defaults dropped pinyin's passedOn, so text before Enter stayed context
    override fun passedOn() {
        if (looking) end()
        idle = true
        pinyin.passedOn()
    }

    override val lastCommit: Commit? get() = pinyin.lastCommit

    override fun typed(text: String) = pinyin.typed(text)

    // a lookup's keys as typed; else pinyin's, after which the key starts a lookup again
    override fun handBack(): HandedBack = if (looking) super.handBack() else pinyin.handBack().also { idle = true }

    private fun type(c: Char): Snapshot = when {
        c in Strokes.KEYS -> lookUp { input.insert(cursor++, c) }
        c in 'a'..'z' -> toPinyin(c)
        else -> leave(found[0] ?: "$KEY$input", handled = false)
    }

    private fun toPinyin(c: Char): Snapshot {
        val after = input.length - cursor
        val keys = "$KEY${input.substring(0, cursor)}$c${input.substring(cursor)}"
        end()
        val typed = retype(keys)
        // the pinyin's cursor right after the letter, before the strokes that were after the lookup's
        val s = if (after > 0 && typed.preedit.isNotEmpty()) pinyin.apply(Action.Step(-after)).copy(commit = typed.commit) else typed
        return s.also { idle = it.preedit.isEmpty() }
    }

    private inline fun lookUp(change: () -> Unit = {}): Snapshot {
        change()
        val under = blockedUnder(input.toString())
        found = Found(strokes()?.find(input.toString()).orEmpty().filterNot { blocked(it) || it in under }.iterator())
        pager.reset()
        return snapshot()
    }

    private fun pick(index: Int): Snapshot {
        val text = found[index] ?: return snapshot()
        val after = follow(text)
        idle = after.preedit.isEmpty()
        return end(commit = text)
    }

    /** Ends the lookup with [text] committed: what is typed next does not run on from it. */
    private fun leave(text: String, handled: Boolean = true): Snapshot {
        idle = pinyin.apply(Action.Reset).preedit.isEmpty()
        return end(commit = text, handled = handled)
    }

    private fun moved(to: Int): Snapshot {
        if (input.isEmpty()) return snapshot()
        cursor = to.coerceIn(0, input.length)
        return snapshot()
    }

    private fun turn(to: Int): Snapshot {
        pager.turn(to) { found[it] != null }
        return snapshot()
    }

    private fun end(commit: String = "", handled: Boolean = true): Snapshot {
        looking = false
        input.setLength(0)
        cursor = 0
        found = Found.none()
        pager.reset()
        return Snapshot(commit, "", emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = handled, predicting = false)
    }

    private fun snapshot(): Snapshot {
        val shown = found.page(pager.from, pager.size)
        return Snapshot(
            commit = "",
            preedit = "$KEY" + Strokes.shown(input),
            cursor = if (cursor < input.length) cursor + 1 else -1,
            candidates = shown,
            page = pager.page,
            hasPreviousPage = pager.hasPrevious,
            hasNextPage = found[pager.from + pager.size] != null,
            handled = true,
            predicting = false,
            hints = shown.map(reading),
            total = found.size,
            first = pager.from,
            // a long press's 屏蔽 goes by the host's candidate actions, which a list not actionable has none of
            actionable = shown.isNotEmpty(),
        )
    }

    /** The characters found, made lazily: the first stroke finds thousands, a page shows a few. */
    private class Found(private val rest: Iterator<String>) {
        private val made = ArrayList<String>()
        private var done = false

        operator fun get(i: Int): String? {
            while (made.size <= i && !done) {
                if (rest.hasNext()) made += rest.next() else done = true
            }
            return made.getOrNull(i)
        }

        val size: Int get() = if (done) made.size else -1

        fun page(from: Int, count: Int): List<String> = (from until from + count).mapNotNull { get(it) }

        companion object {
            // a new one each time: get() writes done, on whichever thread a session is on
            fun none() = Found(emptySequence<String>().iterator())
        }
    }

    companion object {
        const val KEY = 'u'

        /** The record table strokes blocks are filed under ([Kinds.table]; no table is named so). */
        const val BLOCK_TABLE = "strokes"
        const val DEFAULT_PAGE_SIZE = 5
    }
}
