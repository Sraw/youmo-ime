/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.Action

/**
 * 语音先问再记: follows voice's text in the field and asks about a word of it deleted and retyped with the
 * same readings, tones and all; the user's tap adds it ([Engines.voiceRemember][org.fcitx.fcitx5.android.engine.runtime.Engines.voiceRemember]).
 * Told every change on the engine's thread; kept on Engines, not a session, since a word added drops them all.
 * Memory only; anything that does not match ends it. The text is followed as cells, one a UTF-16 unit.
 */
class VoiceEdits(
    private val words: Words,
    private val simplify: (String) -> String = { it },
) {
    interface Words {
        fun readings(c: Char): IntArray?

        /** Read once an edit is made: [takes] asks of these, so the table is built once. */
        fun mine(): Mine

        fun pinyinOf(text: String): String?

        /** Whether voice, with [word] added and [replacing] removed, would put it in for [heard] (VoiceHomophones' rules). */
        fun takes(text: String, at: Int, heard: String, word: String, pinyin: String, replacing: String?): Boolean
    }

    class Mine(val added: Set<String>, val learned: Set<String>, val blocked: Set<String>)

    /** [replaces]: this window's earlier prompt at the same place, removed as this one goes in. [hint]: the once-only 「把整个词删掉重打，语音才能记住」. */
    class Ask(
        val word: String,
        val pinyin: String,
        val heard: String,
        val replaces: String?,
        val hint: Boolean,
        internal val cells: Set<Int> = emptySet(),
    )

    /** Whether a window is still open, and what to ask once [confirm]ed. */
    class Reply(val open: Boolean, val ask: Ask?)

    // [syllable]: the one the user typed for it (a pinyin commit's reading), -1 for none known
    private class Cell(val id: Int, val heard: Char, var now: Char, val syllable: Int = -1)

    private var cells: MutableList<Cell>? = null
    private var nextId = 0

    // -1 while not known: voice's anchor not read yet, or a Backspace over what cannot be counted
    private var cursor = -1

    private var gapAt = -1
    private val gone = ArrayList<Cell>()
    private val fill = StringBuilder()

    private val fillSyllables = ArrayList<Int>()

    // the gap is a selection still in the field: typed over, it is what is replaced
    private var selection = false

    // the engines' commits made while open, for the app's report of each ([putIn])
    private val claimed = ArrayDeque<Pair<String, Ask?>>()

    // till the app's read [confirm]s it
    private var pending: Ask? = null

    private val asked = HashSet<String>()

    private val remembered = ArrayList<Pair<Set<Int>, String>>()

    val open: Boolean get() = cells != null

    var hinted = false

    /** Learning off, another field, or input finished. */
    fun clear() {
        cells = null
        cursor = -1
        closeGap()
        claimed.clear()
        pending = null
        asked.clear()
        remembered.clear()
    }

    /** [before]: read once it was in (null: not read). Joins the window if it goes on at its end; a mismatched anchor waits for [context]. */
    fun said(text: String, before: String?) {
        val said = simplify(text)
        if (said.isEmpty()) return
        val cells = cells
        val read = before?.let(simplify)
        if (cells != null && goesOn(cells, read, said)) {
            said.forEach { cells += Cell(nextId++, it, it) }
            cursor = cells.size
            trim()
            pending = null
            // what was typed before and never reported (a talk on meanwhile) is no report to wait for
            claimed.clear()
            return
        }
        clear()
        this.cells = said.mapTo(ArrayList()) { Cell(nextId++, it, it) }
        trim()
        cursor = if (read != null && endsWithPart(read, fieldText())) this.cells!!.size else -1
    }

    private fun trim() {
        val cells = cells ?: return
        val over = cells.size - MAX_CHARS
        if (over <= 0) return
        repeat(over) { cells.removeAt(0) }
        if (cursor >= 0) cursor -= over
    }

    fun backspace() {
        val cells = cells ?: return
        pending = null
        if (cursor < 0) return clear()
        // where the unit a Backspace takes is part of an emoji sequence, the editor decides: the cursor is unknown
        val taken = when {
            selection -> null
            fill.isNotEmpty() -> fill[fill.length - 1]
            gapAt > 0 -> cells[gapAt - 1].now
            gapAt < 0 && cursor > 0 -> cells[cursor - 1].now
            else -> null
        }
        if (taken != null && !countable(taken)) {
            cursor = -1
            return
        }
        when {
            selection -> {
                selection = false
                cursor = gapAt
            }
            fill.isNotEmpty() -> {
                fill.deleteCharAt(fill.length - 1)
                fillSyllables.removeAt(fillSyllables.size - 1)
                cursor--
            }
            gapAt >= 0 -> {
                if (gapAt == 0) return clear()
                gone.add(0, cells.removeAt(gapAt - 1))
                gapAt--
                cursor = gapAt
            }
            cursor == 0 -> clear()
            else -> {
                gapAt = cursor - 1
                gone += cells.removeAt(gapAt)
                cursor = gapAt
            }
        }
    }

    /** [typed]: from keys or pen, not 联想 nor a table's; [reading] the syllables typed. Anything but Chinese into the gap ends the window. */
    fun committed(text: String, typed: Boolean, reading: String? = null) {
        if (cells == null) return
        pending = null
        val t = simplify(text)
        val typedChinese = typed && t.all(::han)
        if (cursor < 0 || gapAt < 0 || !typedChinese) return clear()
        selection = false
        fill.append(t)
        val syllables = reading?.split(' ', '\'')?.filter { it.isNotEmpty() }?.map(Syllables::id)
        if (syllables != null && syllables.size == t.length && syllables.all { it >= 0 }) fillSyllables += syllables
        else repeat(t.length) { fillSyllables += -1 }
        cursor = gapAt + fill.length
        if (fill.length > gone.size) return clear()
        // Chinese typed again with what followed it (a mark) not yet: judged now
        val chineseGone = gone.indexOfFirst { !han(it.now) }.let { if (it < 0) gone.size else it }
        if (fill.length < chineseGone || (fill.length < gone.size && fill.length != chineseGone)) {
            claimed.addLast(t to null)
            return
        }
        claimed.addLast(t to filled())
    }

    // what was deleted and not refilled is what to ask
    private fun filled(): Ask? {
        val cells = cells ?: return null
        val at = gapAt
        val n = fill.length
        val back = (0 until n).map { i -> gone[i].let { Cell(it.id, it.heard, fill[i], fillSyllables[i]) } }
        val rest = gone.drop(n)
        closeGap()
        cells.addAll(at, back)
        if (rest.isNotEmpty()) {
            gapAt = at + n
            gone += rest
        }
        cursor = at + n
        return decide(at, n)
    }

    /** The app put [text] in: an engine's commit told already, or anything else, which ends the window. */
    fun putIn(text: String): Reply {
        if (cells == null) return Reply(false, null)
        val t = simplify(text)
        val head = claimed.removeFirstOrNull()
        if (head == null || head.first != t) {
            clear()
            return Reply(false, null)
        }
        pending = head.second
        return Reply(true, head.second)
    }

    /** Checked against the field as the prompt is to show: [Ask] if as followed, else the window ends. */
    fun confirm(before: String?, after: String?): Ask? {
        val ask = pending ?: return null
        pending = null
        val b = before?.let(simplify)
        // a gap left after what was typed (a mark deleted with the word) is the field's as followed
        val typing = fill.isNotEmpty() || selection
        if (b == null || typing || cursor < 0) return null.also { clear() }
        val text = fieldText()
        if (!endsWithPart(b, text.substring(0, cursor)) || (after != null && !startsWithPart(simplify(after), text.substring(cursor)))) {
            clear()
            return null
        }
        if (ask.hint) hinted = true else asked += ask.word
        return ask
    }

    /** One asked at the same place again takes its place. */
    fun remembered(ask: Ask) {
        if (ask.hint) return
        remembered.removeAll { it.second == ask.replaces || it.second == ask.word }
        remembered += ask.cells to ask.word
    }

    fun forgot(word: String) {
        remembered.removeAll { it.second == word }
    }

    private fun goesOn(cells: List<Cell>, read: String?, said: String): Boolean {
        if (gapAt >= 0 || cursor != cells.size) return false
        return read != null && endsWithPart(read, fieldText() + said)
    }

    /** The cursor found in the followed text, a stretch found deleted or selected; else the window ends. */
    fun context(before: String, after: String?, selected: Boolean, editorSaid: Boolean, cleared: Boolean) {
        if (cells == null) return
        pending = null
        if (cleared || (before.isEmpty() && !editorSaid)) return clear()
        val b = simplify(before)
        val a = after?.let(simplify)
        if (fill.isNotEmpty()) {
            if (selected || !at(b, a, fieldText(), cursor)) clear()
            return
        }
        val waiting = cursor < 0
        if (selection) restoreGap()
        val text = fieldText()
        if (selected) return selectedIn(deleted(b, a, text)?.takeUnless { waiting })
        val k = cursorIn(b, a, text)
        if (k >= 0) {
            if (gapAt >= 0 && k != gapAt) closeGap()
            cursor = k
            return
        }
        if (waiting) return clear()
        deletedIn(deleted(b, a, text) ?: return clear())
    }

    private fun selectedIn(span: Pair<Int, Int>?) {
        val cells = cells ?: return
        val (from, to) = span ?: return clear()
        closeGap()
        selection = true
        gapAt = from
        repeat(to - from) { gone += cells.removeAt(from) }
        cursor = from
    }

    private fun deletedIn(span: Pair<Int, Int>) {
        val cells = cells ?: return
        val (from, to) = span
        when {
            gapAt >= 0 && to == gapAt -> {
                gone.addAll(0, cells.subList(from, to).toList())
                repeat(to - from) { cells.removeAt(from) }
                gapAt = from
            }
            gapAt >= 0 && from == gapAt -> {
                gone.addAll(cells.subList(from, to).toList())
                repeat(to - from) { cells.removeAt(from) }
            }
            else -> {
                closeGap()
                gapAt = from
                repeat(to - from) { gone += cells.removeAt(from) }
            }
        }
        cursor = gapAt
    }

    private fun fieldText(): String {
        val cells = cells ?: return ""
        val out = StringBuilder(cells.size + fill.length)
        for ((i, c) in cells.withIndex()) {
            if (i == gapAt) if (selection) gone.forEach { out.append(it.now) } else out.append(fill)
            out.append(c.now)
        }
        if (gapAt == cells.size) if (selection) gone.forEach { out.append(it.now) } else out.append(fill)
        return out.toString()
    }

    private fun closeGap() {
        gapAt = -1
        gone.clear()
        fill.setLength(0)
        fillSyllables.clear()
        selection = false
    }

    private fun restoreGap() {
        val cells = cells ?: return
        cells.addAll(gapAt, gone)
        closeGap()
    }

    private fun at(b: String, a: String?, text: String, k: Int) =
        k in 0..text.length && endsWithPart(b, text.substring(0, k)) && (a == null || startsWithPart(a, text.substring(k)))

    // the one place both sides match; with nothing read after, the furthest the before-text reaches; -1 for none or several
    private fun cursorIn(b: String, a: String?, text: String): Int {
        if (a == null) return (text.length downTo 1).firstOrNull { endsWithPart(b, text.substring(0, it)) } ?: -1
        val found = (0..text.length).filter { at(b, a, text, it) }
        return found.singleOrNull() ?: -1
    }

    // of the stretches that fit, the one keeping most of the window (what surrounds it is unknown); one keeping
    // none cannot be told from the cursor put elsewhere
    private fun deleted(b: String, a: String?, text: String): Pair<Int, Int>? {
        if (a == null) return null
        var best: Pair<Int, Int>? = null
        var bestKept = 0
        var tie = false
        for (from in 0 until text.length) {
            if (!endsWithPart(b, text.substring(0, from))) continue
            for (to in from + 1..text.length) {
                if (!startsWithPart(a, text.substring(to))) continue
                val kept = from + text.length - to
                if (kept > bestKept) {
                    best = from to to
                    bestKept = kept
                    tie = false
                } else if (kept == bestKept) tie = true
            }
        }
        return best.takeIf { !tie }
    }

    private fun decide(at: Int, n: Int): Ask? {
        val cells = cells ?: return null
        val heard = String(CharArray(cells.size) { cells[it].heard })
        val now = String(CharArray(cells.size) { cells[it].now })
        val x = heard.substring(at, at + n)
        val y = now.substring(at, at + n)
        if (x == y) return null
        val core = changed(x, y, at)
        // every character changed a Chinese one of the BMP, read the same, tones and all
        for (k in core) if (!han(heard[k]) || !han(now[k]) || same(heard[k], now[k]).isEmpty()) return null
        val mine = words.mine()
        val spans = LinkedHashSet<IntRange>()
        // (a) the user's word over it, the longest
        ownOver(now, core, mine)?.let { spans += it }
        // (b) 2–4 characters deleted: the changed ones first, then all typed
        if (n in 2..MAX_TYPED && y.all(::han)) {
            if (core.count() >= 2) spans += core
            spans += at until at + n
        }
        for (span in spans) asking(span, heard, now, mine)?.let { return it }
        // one character typed again: say once how to have it remembered, where it would work
        return if (n == 1 && !hinted) hintAround(core.first, heard, now, mine) else null
    }

    private fun changed(x: String, y: String, at: Int): IntRange {
        val n = x.length
        var p = 0
        while (p < n && x[p] == y[p]) p++
        var s = 0
        while (s < n - p && x[n - 1 - s] == y[n - 1 - s]) s++
        return at + p until at + n - s
    }

    private fun hintAround(c: Int, heard: String, now: String, mine: Mine): Ask? {
        val around = listOf(c - 1..c, c..c + 1, c - 2..c, c - 1..c + 1, c..c + 2)
        for (span in around) {
            if (span.first < 0 || span.last >= heard.length) continue
            if (asking(span, heard, now, mine) != null) return Ask("", "", "", null, hint = true, cells = emptySet())
        }
        return null
    }

    private fun ownOver(now: String, core: IntRange, mine: Mine): IntRange? {
        for (len in MAX_WORD downTo maxOf(2, core.count())) {
            for (from in maxOf(0, core.last + 1 - len)..minOf(core.first, now.length - len)) {
                val w = now.substring(from, from + len)
                if (w in mine.added || w in mine.learned) return from until from + len
            }
        }
        return null
    }

    private fun asking(span: IntRange, heard: String, now: String, mine: Mine): Ask? {
        val w = now.substring(span.first, span.last + 1)
        val xw = heard.substring(span.first, span.last + 1)
        if (w.length < 2 || w == xw || !w.all(::han)) return null
        if (w in mine.added || w in asked) return null
        if (mine.blocked.any { it.isNotEmpty() && it in w }) return null
        val pinyin = pinyinOf(span, heard, now, w) ?: return null
        val ids = span.mapTo(HashSet()) { cells!![it].id }
        // voice is judged without the earlier one at the same place, which goes as this goes in
        val replaces = remembered.firstOrNull { (over, word) -> word != w && over.any(ids::contains) }?.second
        if (!words.takes(heard, span.first, xw, w, pinyin, replaces)) return null
        return Ask(w, pinyin, xw, replaces, hint = false, cells = ids)
    }

    // the typed syllable where the char may read so, else its reading, else the dictionary's for the word. A typed
    // syllable none of those has asks nothing: voice could not hear it as typed
    private fun pinyinOf(span: IntRange, heard: String, now: String, w: String): String? {
        val cells = cells ?: return null
        val each = span.map { k ->
            val r = if (heard[k] == now[k]) words.readings(now[k])?.toList().orEmpty() else same(heard[k], now[k])
            val may = r.map { it / READING_TONES }.distinct()
            val typed = cells[k].syllable
            if (typed < 0) may else if (typed in may) listOf(typed) else return null
        }
        if (each.any { it.isEmpty() }) return null
        val dictionary by lazy(LazyThreadSafetyMode.NONE) {
            words.pinyinOf(w)?.split(' ', '\'')?.filter { it.isNotEmpty() }?.map(Syllables::id)
        }
        val syllables = each.mapIndexed { i, s ->
            s.singleOrNull() ?: dictionary?.getOrNull(i)?.takeIf { it in s } ?: return null
        }
        return Syllables.spelled(syllables.size) { syllables[it] }
    }

    // one not known taken as any of its syllable
    private fun same(x: Char, y: Char): List<Int> {
        val rx = words.readings(x) ?: return emptyList()
        val ry = words.readings(y) ?: return emptyList()
        return ry.filter { r -> rx.any { it == r || (it / READING_TONES == r / READING_TONES && (it % READING_TONES == 0 || r % READING_TONES == 0)) } }
    }

    companion object {
        const val MAX_CHARS = 256

        // as VoiceHomophones' MAX_WORD
        private const val MAX_WORD = 12

        private const val MAX_TYPED = 4

        // as VoiceHomophones.reading packs them: a syllable times 8 plus its tone
        private const val READING_TONES = 8

        // as much as the host reads either side: one that long may be cut short
        private const val READ_CHARS = Action.Context.HOST_CHARS

        private fun han(c: Char) = c in '一'..'鿿'

        // one a Backspace takes alone: no surrogate, no joiner, selector or mark
        private fun countable(c: Char) = !Character.isSurrogate(c) && Character.getType(c).let {
            it != Character.FORMAT.toInt() && it != Character.NON_SPACING_MARK.toInt() && it != Character.ENCLOSING_MARK.toInt()
        } && c !in '︀'..'️'

        // or with as much of [s] as was read
        internal fun endsWithPart(read: String, s: String) =
            if (read.length >= s.length) read.endsWith(s) else read.length >= READ_CHARS && s.endsWith(read)

        internal fun startsWithPart(read: String, s: String) =
            if (read.length >= s.length) read.startsWith(s) else read.length >= READ_CHARS && s.startsWith(read)
    }
}
