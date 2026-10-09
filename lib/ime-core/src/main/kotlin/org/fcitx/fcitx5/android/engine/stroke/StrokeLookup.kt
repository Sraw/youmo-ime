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
 * other key commits the first candidate, or with none what was typed, and is the app's, as in pinyin.
 *
 * [strokes] is asked for at the first lookup: null, there are none, and [KEY] is [pinyin]'s. A
 * character the user [blocked] is not found. A character picked is handed to [follow], for the
 * pinyin to go on after: by default as the app's text ([Action.Context]); a PinyinSession's
 * `follow` keeps what it committed before too, whatever wraps either session.
 */
class StrokeLookup(
    private val pinyin: Session,
    private val strokes: () -> Strokes?,
    private val reading: (String) -> String = { "" },
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val blocked: (String) -> Boolean = { false },
    private val follow: (String) -> Snapshot = { pinyin.apply(Action.Context(it)) },
) : Session {

    private var looking = false
    private val input = StringBuilder()
    private var found: List<String> = emptyList()
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
        return when (action) {
            is Action.Key -> type(action.char)
            Action.Backspace -> if (input.isEmpty()) end() else lookUp { input.setLength(input.length - 1) }
            is Action.Select -> if (action.index in 0 until pageSize) pick(page * pageSize + action.index) else snapshot()
            is Action.Pick -> pick(action.index)
            Action.NextPage -> turn(page + 1)
            Action.PreviousPage -> turn(page - 1)
            Action.CommitRaw -> end(commit = "$KEY$input")
            Action.Reset, is Action.Context -> {
                end()
                pinyin.apply(action).also { idle = it.preedit.isEmpty() }
            }
            // nothing learned, nothing to offer: as it was
            else -> snapshot()
        }
    }

    override fun candidates(from: Int, count: Int): List<Choice> =
        if (!looking) pinyin.candidates(from, count)
        else found.subList(minOf(from, found.size), minOf(from + count, found.size)).map { Choice(it, reading(it)) }

    override fun offers(index: Int): Set<Offer> = if (looking) emptySet() else pinyin.offers(index)

    private fun type(c: Char): Snapshot = when {
        c in Strokes.KEYS -> lookUp { input.append(c) }
        c in 'a'..'z' -> toPinyin(c)
        // a key not read: the first candidate, or what was typed as Enter has it; then the key is the app's
        else -> end(commit = found.firstOrNull() ?: "$KEY$input", handled = false)
    }

    /** Ends the lookup, [KEY], the strokes and [c] typed into [pinyin] instead. */
    private fun toPinyin(c: Char): Snapshot {
        val keys = "$KEY$input$c"
        end()
        val typed = keys.map { pinyin.apply(Action.Key(it)) }
        idle = typed.last().preedit.isEmpty()
        return typed.last().copy(commit = typed.joinToString("") { it.commit })
    }

    private inline fun lookUp(change: () -> Unit = {}): Snapshot {
        change()
        found = strokes()?.find(input.toString()).orEmpty().filterNot(blocked)
        page = 0
        return snapshot()
    }

    private fun pick(index: Int): Snapshot {
        val text = found.getOrNull(index) ?: return snapshot()
        // what follows follows it, after what the pinyin committed before, as the pinyin would have it
        val after = follow(text)
        idle = after.preedit.isEmpty()
        return end(commit = text)
    }

    private fun turn(to: Int): Snapshot {
        if (to >= 0 && to * pageSize < found.size) page = to
        return snapshot()
    }

    private fun end(commit: String = "", handled: Boolean = true): Snapshot {
        looking = false
        input.setLength(0)
        found = emptyList()
        page = 0
        return Snapshot(commit, "", emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = handled, predicting = false)
    }

    private fun snapshot(): Snapshot {
        val from = page * pageSize
        val shown = found.subList(minOf(from, found.size), minOf(from + pageSize, found.size))
        return Snapshot(
            commit = "",
            preedit = "$KEY" + Strokes.shown(input),
            candidates = shown,
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = from + pageSize < found.size,
            handled = true,
            predicting = false,
            hints = shown.map(reading),
            total = found.size,
            first = from,
        )
    }

    companion object {
        /** The key that starts a lookup. */
        const val KEY = 'u'
        const val DEFAULT_PAGE_SIZE = 5
    }
}
