/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * Pinyin with a character looked up by its strokes, as 搜狗 has it: [KEY] typed with nothing
 * else typed starts the lookup (no pinyin starts with u), then `h s p n z` are the strokes
 * ([Strokes]); each candidate shows its pinyin ([reading]) to learn it by. Picked, it is
 * committed, and the pinyin goes on after it. Any other letter ends the lookup, its keys and the
 * letter typed into pinyin instead: a Latin word (`usb` for USB) or a slip (`uan` for yan). Any
 * other key commits the first candidate, or with none what was typed, and is the app's, as in
 * pinyin: what is typed next does not run on from it, as after Enter.
 *
 * The strokes keep a cursor, at their end till moved ([Action.Cursor], [Action.Step]), as pinyin
 * keeps one: strokes go in there, backspace deletes before it, and the letter that ends the lookup
 * is typed there too.
 *
 * [strokes] is asked for at the first lookup: null, there are none, and [KEY] is [pinyin]'s. A
 * character the user [blocked] is not found. A character picked is handed to [follow], for the
 * pinyin to go on after: by default as the app's text ([Action.Context]); a PinyinSession's
 * `follow` keeps what it committed before too, whatever wraps either session. The keys of a
 * lookup a letter ended are handed to [retype], by default typed into the pinyin a key at a time.
 */
class StrokeLookup(
    private val pinyin: Session,
    private val strokes: () -> Strokes?,
    private val reading: (String) -> String = { "" },
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val blocked: (String) -> Boolean = { false },
    private val follow: (String) -> Snapshot = { pinyin.apply(Action.Context(it)) },
    private val retype: (String) -> Snapshot = { keys ->
        val typed = keys.map { pinyin.apply(Action.Key(it)) }
        typed.last().copy(commit = typed.joinToString("") { it.commit })
    },
) : Session {

    private var looking = false
    private val input = StringBuilder()
    private var cursor = 0 // where in the strokes the next goes
    private var found = Found.none()
    private var page = 0

    // whether pinyin shows nothing typed: only then does the key start a lookup
    private var idle = true

    override var learning: Boolean
        get() = pinyin.learning
        set(value) {
            pinyin.learning = value
        }

    override fun reads(c: Char): Boolean = if (looking) c in 'a'..'z' else (c == KEY && idle) || pinyin.reads(c)

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
                // before the first stroke: the key that started the lookup stays
                cursor == 0 -> snapshot()
                else -> lookUp { input.deleteCharAt(--cursor) }
            }
            is Action.Select -> if (action.index in 0 until pageSize) pick(page * pageSize + action.index) else snapshot()
            is Action.Pick -> pick(action.index)
            Action.NextPage -> turn(page + 1)
            Action.PreviousPage -> turn(page - 1)
            Action.CommitRaw -> leave("$KEY$input")
            Action.Reset, is Action.Context -> {
                end()
                pinyin.apply(action).also { idle = it.preedit.isEmpty() }
            }
            // the preedit is the key, then a stroke a character
            is Action.Cursor -> moved(action.at.toLong() - 1)
            is Action.Step -> moved(cursor.toLong() + action.by)
            // nothing learned, nothing to offer: as it was
            else -> snapshot()
        }

    override fun candidates(from: Int, count: Int): List<Choice> =
        if (!looking) pinyin.candidates(from, count)
        else found.page(from, count).map { Choice(it, reading(it)) }

    override fun offers(index: Int): Set<Offer> = if (looking) emptySet() else pinyin.offers(index)

    private fun type(c: Char): Snapshot = when {
        c in Strokes.KEYS -> lookUp { input.insert(cursor++, c) }
        c in 'a'..'z' -> toPinyin(c)
        // a key not read: the first candidate, or what was typed as Enter has it; then the key is the app's
        else -> leave(found[0] ?: "$KEY$input", handled = false)
    }

    /** Ends the lookup, [KEY], the strokes and [c] typed into [pinyin] instead. */
    private fun toPinyin(c: Char): Snapshot {
        val after = input.length - cursor
        val keys = "$KEY" + input.insert(cursor, c)
        end()
        val typed = retype(keys)
        // the pinyin's cursor where the letter went in, the strokes after it after it
        val s = if (after > 0 && typed.preedit.isNotEmpty()) pinyin.apply(Action.Step(-after)).copy(commit = typed.commit) else typed
        return s.also { idle = it.preedit.isEmpty() }
    }

    private inline fun lookUp(change: () -> Unit = {}): Snapshot {
        change()
        found = Found(strokes()?.find(input.toString()).orEmpty().filterNot(blocked).iterator())
        page = 0
        return snapshot()
    }

    private fun pick(index: Int): Snapshot {
        val text = found[index] ?: return snapshot()
        // what follows follows it, after what the pinyin committed before, as the pinyin would have it
        val after = follow(text)
        idle = after.preedit.isEmpty()
        return end(commit = text)
    }

    /**
     * Ends the lookup, [text] committed as typed or before a key that is the app's: what is typed
     * next does not run on from it, as after the pinyin's own.
     */
    private fun leave(text: String, handled: Boolean = true): Snapshot {
        idle = pinyin.apply(Action.Reset).preedit.isEmpty()
        return end(commit = text, handled = handled)
    }

    private fun moved(to: Long): Snapshot {
        if (input.isEmpty()) return snapshot()
        cursor = to.coerceIn(0, input.length.toLong()).toInt()
        return snapshot()
    }

    private fun turn(to: Int): Snapshot {
        if (to >= 0 && found[to * pageSize] != null) page = to
        return snapshot()
    }

    private fun end(commit: String = "", handled: Boolean = true): Snapshot {
        looking = false
        input.setLength(0)
        cursor = 0
        found = Found.none()
        page = 0
        return Snapshot(commit, "", emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = handled, predicting = false)
    }

    private fun snapshot(): Snapshot {
        val from = page * pageSize
        val shown = found.page(from, pageSize)
        return Snapshot(
            commit = "",
            preedit = "$KEY" + Strokes.shown(input),
            cursor = if (cursor < input.length) cursor + 1 else -1,
            candidates = shown,
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = found[from + pageSize] != null,
            handled = true,
            predicting = false,
            hints = shown.map(reading),
            total = found.size,
            first = from,
        )
    }

    /** The characters found, kept as they are made: the first stroke finds thousands, a page shows a few. */
    private class Found(private val rest: Iterator<String>) {
        private val made = ArrayList<String>()
        private var done = false

        operator fun get(i: Int): String? {
            while (made.size <= i && !done) {
                if (rest.hasNext()) made += rest.next() else done = true
            }
            return made.getOrNull(i)
        }

        /** How many there are, -1 until all were made. */
        val size: Int get() = if (done) made.size else -1

        fun page(from: Int, count: Int): List<String> = (from until from + count).mapNotNull { get(it) }

        companion object {
            // a new one each time, not one every StrokeLookup shares: get() writes done, on whichever thread a session is on
            fun none() = Found(emptySequence<String>().iterator())
        }
    }

    companion object {
        /** The key that starts a lookup. */
        const val KEY = 'u'
        const val DEFAULT_PAGE_SIZE = 5
    }
}
