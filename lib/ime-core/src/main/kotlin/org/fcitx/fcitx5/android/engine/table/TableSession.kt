/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * A table input method (五笔, 仓颉 ...): the candidates are the entries whose code starts with
 * what was typed, ranked as libime ranks them. Codes no longer than [TableOptions.noSortInputLength]
 * (nor than what was typed) come first in the table's order; then the others, shorter codes
 * first ([TableOptions.sortByCodeLength]), and among codes as long what was picked most
 * ([TableOptions.orderByUse]). A text shows once, with its shortest code.
 *
 * With [TableOptions.autoSelect], typing commits by itself, as libime does: a code that leaves
 * one candidate, its code whole, commits it; a key past the longest code, or one that leads to no
 * entry, commits the first candidate on the page shown and starts a new code. A code with no
 * candidate is dropped by the key that ends it, as 五笔 users expect of 空码.
 *
 * [TableOptions.pinyinKey] starting a code hands the keys after it to [pinyin], and its
 * candidates show their code here: the way to type a character whose code one does not know.
 *
 * Characters committed one by one become phrases ([TableOptions.autoPhraseLength]) coded by the
 * table's 组词规则, offered last for their code. One picked, or typed character by character
 * [TableOptions.saveAutoPhraseAfter] times, joins the table's own. What is picked and learned is
 * kept in memory only.
 */
class TableSession(
    private val table: TableDictionary,
    private val options: TableOptions = TableOptions(),
    private val pinyin: Session? = null,
) : Session {

    /** A candidate: a table entry (its [index]), or a phrase learned here ([index] -1). */
    private class Item(val code: String, val text: String, val auto: Boolean, val index: Int = -1)

    private val input = StringBuilder()
    private var ranking = Ranking.NONE
    private var page = 0

    // the pinyin lookup: on while the input starts with the pinyin key
    private var lookingUp = false
    private var lookedUp: Snapshot? = null

    override var learning = true

    // what was committed last, one character at a time, for auto phrases
    private val recent = ArrayList<String>()
    private val autoPhraseLength = if (options.autoPhraseLength < 0) table.maxLength else options.autoPhraseLength

    // picks of table entries by index (at most one each), of saved phrases by code and text
    private val picks = HashMap<Int, Int>()
    private val savedPicks = HashMap<String, Int>()
    private val saved = HashMap<String, MutableList<String>>()
    private val autoPhrases = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > MAX_AUTO_PHRASES
    }

    override fun apply(action: Action): Snapshot = if (lookingUp) lookUp(action) else when (action) {
        is Action.Key -> type(action.char)
        Action.Backspace -> backspace()
        is Action.Select -> select(action.index)
        is Action.Pick -> pick(action.index)
        Action.NextPage -> turn(page + 1)
        Action.PreviousPage -> turn(page - 1)
        Action.CommitRaw -> {
            val text = input.toString()
            recent.clear()
            clear()
            snapshot(commit = text, handled = text.isNotEmpty())
        }
        Action.Reset -> {
            recent.clear()
            clear()
            snapshot()
        }
    }

    override fun reads(c: Char): Boolean = if (lookingUp) {
        c in 'a'..'z' || c == '\''
    } else {
        c in table.keys || c == options.matchingKey || (c == options.pinyinKey && pinyin != null && input.isEmpty())
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        if (lookingUp) return pinyin!!.candidates(from, count).map { Choice(it.text, table.codeOf(it.text).orEmpty()) }
        val wild = wild()
        return ranking.page(from, count).map { Choice(it.text, if (options.hint) hint(it, wild) else "") }
    }

    private fun type(c: Char): Snapshot {
        val startsLookUp = c == options.pinyinKey && pinyin != null
        val isCode = c in table.keys || c == options.matchingKey
        if (!isCode && !(startsLookUp && input.isEmpty())) {
            // not a code key: what was typed is committed, then the key is the app's
            val text = if (input.isEmpty()) "" else autoCommit()
            recent.clear()
            return snapshot(commit = text, handled = false)
        }
        var commit = ""
        if (options.autoSelect && input.isNotEmpty() && endsCode(c)) commit = autoCommit()
        // after a code just committed too: 叫 by kkkk, then z looks the next one up
        if (startsLookUp && input.isEmpty()) {
            lookingUp = true
            input.append(c)
            return snapshot(commit = commit)
        }
        input.append(c)
        update()
        val only = ranking.only()
        if (only != null && commitsItself(only)) commit += take(only)
        return snapshot(commit = commit)
    }

    /** Whether [c] cannot go on the code typed: it is full, or [c] would lead nowhere. */
    private fun endsCode(c: Char) =
        input.length >= table.maxLength || (reaches(options.noMatchAutoSelectLength) && !leadsAnywhere("$input$c"))

    /** Whether [item], the only candidate, is committed without asking. */
    private fun commitsItself(item: Item): Boolean {
        // an auto phrase waits to be picked; so does a code found through the matching key
        if (!options.autoSelect || item.auto) return false
        return item.code == input.toString() && reaches(options.autoSelectLength)
    }

    /** Whether what is typed is long enough for a length [limit] of [TableOptions]. */
    private fun reaches(limit: Int) = limit != 0 && (limit < 0 || input.length >= limit)

    /** Commits the first candidate on the page shown, unless it is an auto phrase: then, as with no candidate, the code is dropped. */
    private fun autoCommit(): String {
        val first = ranking[page * options.pageSize] ?: ranking[0]
        return take(first?.takeUnless { it.auto })
    }

    private fun backspace(): Snapshot {
        if (input.isEmpty()) {
            recent.clear()
            return snapshot(handled = false)
        }
        input.setLength(input.length - 1)
        update()
        return snapshot()
    }

    private fun select(index: Int): Snapshot {
        if (input.isEmpty()) {
            recent.clear()
            return snapshot(handled = false)
        }
        // space on a code with no candidate drops it
        if (ranking[0] == null) return snapshot(commit = take(null))
        val item = ranking[page * options.pageSize + index]
        if (item == null || index !in 0 until options.pageSize) return snapshot()
        return snapshot(commit = take(item))
    }

    private fun pick(index: Int): Snapshot {
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        return snapshot(commit = take(item))
    }

    private fun turn(to: Int): Snapshot {
        // a code on screen keeps the key, candidates or not
        if (to < 0 || ranking[to * options.pageSize] == null) return snapshot(handled = input.isNotEmpty())
        page = to
        return snapshot()
    }

    /** Commits [item], learning from it, and clears the input; a code with none is dropped. */
    private fun take(item: Item?): String {
        clear()
        if (item == null) {
            recent.clear()
            return ""
        }
        if (!learning) {
            recent.clear()
            return item.text
        }
        if (item.index >= 0) {
            picks[item.index] = (picks[item.index] ?: 0) + 1
        } else {
            val key = key(item.code, item.text)
            savedPicks[key] = (savedPicks[key] ?: 0) + 1
        }
        // picked once, an auto phrase is the user's
        if (item.auto) save(item.code, item.text)
        learnPhrases(item.text)
        return item.text
    }

    private fun save(code: String, text: String) {
        autoPhrases.remove(key(code, text))
        saved.getOrPut(code) { ArrayList() } += text
    }

    /** Offers the last characters committed one by one, [text] ending them, as phrases. */
    private fun learnPhrases(text: String) {
        if (autoPhraseLength < 2 || table.rules.isEmpty) return
        val chars = PhraseRules.codePoints(text)
        // a phrase committed whole says nothing of what the user builds (fcitx's AutoPhraseWithPhrase)
        if (chars.size != 1) {
            recent.clear()
            return
        }
        recent += chars[0]
        while (recent.size > autoPhraseLength) recent.removeAt(0)
        for (n in 2..recent.size) sighted(recent.subList(recent.size - n, recent.size).joinToString(""))
    }

    /** Counts [phrase] typed once more, saving it when seen often enough. */
    private fun sighted(phrase: String) {
        val code = table.encode(phrase) ?: return
        if (saved[code]?.contains(phrase) == true || table.contains(code, phrase)) return
        val seen = (autoPhrases[key(code, phrase)] ?: 0) + 1
        if (options.saveAutoPhraseAfter in 1..seen) save(code, phrase) else autoPhrases[key(code, phrase)] = seen
    }

    private fun clear() {
        input.setLength(0)
        ranking = Ranking.NONE
        page = 0
    }

    /** Whether some entry, learned ones too, has a code [pattern] leads to. */
    private fun leadsAnywhere(pattern: String) = table.hasMatch(pattern, options.matchingKey) ||
        saved.keys.any { it.startsWith(pattern) } || autoPhrases.keys.any { it.startsWith(pattern) }

    private fun update() {
        page = 0
        ranking = if (input.isEmpty()) Ranking.NONE else rank(input.toString())
    }

    /** The candidates of [code], ranked; decoded as they are asked for. */
    private fun rank(code: String): Ranking {
        val entries = table.match(code, options.matchingKey)
        // learned phrases are found by their plain code; the matching key is for the table's
        val learned = saved.flatMap { (c, texts) -> if (c.startsWith(code)) texts.map { Item(c, it, false) } else emptyList() }
        val noSort = minOf(code.length, options.noSortInputLength)
        // one sortable number per candidate: its group, length, picks, then where it was
        val keys = LongArray(learned.size + entries.size) { at ->
            val length = if (at < learned.size) learned[at].code.length else table.codeLength(entries[at - learned.size])
            val sorted = length > noSort
            val used = when {
                !sorted || !options.orderByUse -> 0
                at < learned.size -> savedPicks[key(learned[at].code, learned[at].text)] ?: 0
                else -> picks[entries[at - learned.size]] ?: 0
            }
            (if (sorted) 1L shl GROUP_SHIFT else 0L) or
                ((if (sorted && options.sortByCodeLength) minOf(length, MAX_LENGTH) else 0).toLong() shl LENGTH_SHIFT) or
                ((MAX_PICKS - minOf(used, MAX_PICKS)).toLong() shl PICKS_SHIFT) or
                at.toLong()
        }
        keys.sort()
        val order = IntArray(keys.size) { (keys[it] and POSITION_MASK).toInt() }
        // the most recently seen first
        val auto = autoPhrases.keys.filter { it.startsWith(code) }.asReversed().map {
            val at = it.indexOf(SEPARATOR)
            Item(it.substring(0, at), it.substring(at + 1), true)
        }
        return Ranking { at ->
            when {
                at < order.size -> order[at].let { if (it < learned.size) learned[it] else entryItem(entries[it - learned.size]) }
                else -> auto.getOrNull(at - order.size)
            }
        }
    }

    private fun entryItem(index: Int) = Item(table.code(index), table.text(index), false, index)

    /**
     * Ranked candidates made as needed, each text once: the first of it, which is its shortest
     * code when codes are sorted by length. Unsorted, the first stays (libime would show the
     * shorter code in its place); no preset turns sorting off.
     */
    private class Ranking(private val source: (Int) -> Item?) {
        private val items = ArrayList<Item>()
        private val seen = HashSet<String>()
        private var next = 0
        private var done = false

        operator fun get(i: Int): Item? {
            while (items.size <= i && !done) {
                val item = source(next++)
                if (item == null) done = true else if (seen.add(item.text)) items += item
            }
            return items.getOrNull(i)
        }

        fun only(): Item? = if (get(1) == null) get(0) else null

        /** How many there are, -1 until all were made: a key's range may hold thousands. */
        val size: Int get() = if (done) items.size else -1

        fun page(from: Int, size: Int): List<Item> = (from until from + size).mapNotNull { get(it) }

        companion object {
            val NONE = Ranking { null }
        }
    }

    private fun snapshot(commit: String = "", handled: Boolean = true): Snapshot {
        val from = page * options.pageSize
        val shown = ranking.page(from, options.pageSize)
        val wild = wild()
        return Snapshot(
            commit = commit,
            preedit = input.toString(),
            candidates = shown.map { it.text },
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = ranking[from + options.pageSize] != null,
            handled = handled,
            predicting = false,
            hints = if (!options.hint || shown.isEmpty()) emptyList() else shown.map { hint(it, wild) },
            total = ranking.size,
            first = from,
        )
    }

    private fun wild() = options.matchingKey != null && input.contains(options.matchingKey)

    /** What is left of [item]'s code to type; all of it where a wildcard stands in the input. */
    private fun hint(item: Item, wild: Boolean) = if (wild) item.code else item.code.substring(input.length)

    /** An action while looking up by pinyin: [pinyin] reads the keys after the pinyin key. */
    private fun lookUp(action: Action): Snapshot {
        val pinyin = pinyin!!
        return when (action) {
            is Action.Key -> if (action.char in 'a'..'z' || action.char == '\'') {
                val s = pinyin.apply(action)
                // a key it would not take (' first) is swallowed, not handed on: the app must
                // not get what the preedit does not show
                if (s.handled) {
                    input.append(action.char)
                    fromPinyin(s)
                } else {
                    lookedUp ?: snapshot()
                }
            } else {
                // what was looked up is committed, then the key is the app's
                var text = if (input.length > 1) pinyin.apply(Action.Select(0)).commit else ""
                if (text.isEmpty()) text = pinyin.apply(Action.CommitRaw).commit
                endLookUp()
                snapshot(commit = text, handled = false)
            }
            Action.Backspace -> if (input.length > 1) {
                input.setLength(input.length - 1)
                fromPinyin(pinyin.apply(action))
            } else {
                endLookUp()
                snapshot()
            }
            Action.CommitRaw -> {
                // as typed, the pinyin key too, unless a word was picked from the start of it
                val raw = if (input.length > 1) pinyin.apply(action).commit else ""
                val text = if (raw == input.substring(1)) input.toString() else raw
                endLookUp()
                snapshot(commit = text)
            }
            Action.Reset -> {
                endLookUp()
                snapshot()
            }
            // the pinyin key alone: nothing to pick, and space drops it
            is Action.Select -> if (input.length > 1) fromPinyin(pinyin.apply(action)) else {
                endLookUp()
                snapshot()
            }
            else -> fromPinyin(pinyin.apply(action))
        }
    }

    private fun fromPinyin(s: Snapshot): Snapshot {
        if (s.commit.isNotEmpty()) {
            endLookUp()
            return snapshot(commit = s.commit)
        }
        return s.copy(
            preedit = s.preedit.ifEmpty { input.toString() },
            // the lookup has the keys, whatever the pinyin session says of them
            handled = true,
            predicting = false,
            hints = s.candidates.map { table.codeOf(it).orEmpty() },
        ).also { lookedUp = it }
    }

    private fun endLookUp() {
        // no 联想 after a lookup, and no context carried into the next one
        pinyin?.apply(Action.Reset)
        lookingUp = false
        lookedUp = null
        // a looked-up character is not one typed by its code: no phrase from it
        recent.clear()
        clear()
    }

    companion object {
        private const val SEPARATOR = '\t'
        // how many auto phrases not yet picked often enough are remembered
        private const val MAX_AUTO_PHRASES = 1024

        // a ranking key: group, code length, picks (fewest last), position
        private const val GROUP_SHIFT = 62
        private const val LENGTH_SHIFT = 54
        private const val PICKS_SHIFT = 32
        private const val MAX_LENGTH = 0xFF
        private const val MAX_PICKS = 0x3FFFFF
        private const val POSITION_MASK = 0xFFFFFFFFL

        private fun key(code: String, text: String) = "$code$SEPARATOR$text"
    }
}
