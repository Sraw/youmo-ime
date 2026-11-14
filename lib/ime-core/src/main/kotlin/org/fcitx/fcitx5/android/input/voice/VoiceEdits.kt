/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * 语音先问再记 (061-voice-learn-from-edits.md): what voice input put in the field ([said]), followed
 * as the user changes it, and a word of it deleted and typed again the same length, every character
 * read the same, tones and all, asked about: 「让语音记住『徐婧』？」. Nothing is learned here; the
 * user's tap adds the word by hand ([Engines.voiceRemember][org.fcitx.fcitx5.android.engine.runtime.Engines.voiceRemember]).
 *
 * Told every change the engines see, in order, on the engine's thread: voice's text with what was
 * read either side of the cursor once it was in ([said]), a Backspace the app took ([backspace]),
 * a commit of the engines' ([committed], pinyin's and handwriting's [typed] ones those an edit is
 * made of), anything else that went in ([putIn]), and the host's contexts ([context]). Kept on
 * [Engines][org.fcitx.fcitx5.android.engine.runtime.Engines], not a session: a word added drops
 * them all (review R8). Only in memory; whatever does not match ends it (CommitJournal's rule: not
 * sure, not asked).
 *
 * The field's text is followed as cells, one a UTF-16 unit, each what voice wrote there ([Cell.heard])
 * and what it is now; a stretch deleted is a gap ([gapAt], [gone]) that what is typed next fills.
 */
class VoiceEdits(
    private val words: Words,
    private val simplify: (String) -> String = { it },
) {
    /** What the engine knows of the user's words and of characters, asked once an edit is made. */
    interface Words {
        /** [c]'s readings, all of them ([VoiceHomophones.reading]), null for none */
        fun readings(c: Char): IntArray?

        /**
         * the user's words: added by hand, learned (not those used once), blocked. Read once an edit
         * is made: what [takes] asks of for that edit is these (review S7: the table built once).
         */
        fun mine(): Mine

        /** [text]'s reading as the dictionary spells it (`xu jing`), null if it cannot */
        fun pinyinOf(text: String): String?

        /**
         * Whether voice, with [word] read [pinyin] added by hand beside the user's words, [replacing]
         * (one this window added, its prompt's [Ask.replaces]) taken off them, would put it in for
         * [heard] at [at] in [text] (VoiceHomophones' rules, unchanged): asked only then.
         */
        fun takes(text: String, at: Int, heard: String, word: String, pinyin: String, replacing: String?): Boolean
    }

    class Mine(val added: Set<String>, val learned: Set<String>, val blocked: Set<String>)

    /**
     * What to say: [word] read [pinyin] for voice to put in where it heard [heard]; [replaces], one
     * this window's prompt added at the same place, taken off the added words as this one goes in
     * (review M4). [hint]: no word, the once-only 「把整个词删掉重打，语音才能记住」 instead.
     */
    class Ask(
        val word: String,
        val pinyin: String,
        val heard: String,
        val replaces: String?,
        val hint: Boolean,
        // the cells it is over, by their ids: where a later one replaces it
        internal val cells: Set<Int> = emptySet(),
    )

    /** What [putIn] answers: whether a window is still open, and what to ask (confirmed by [confirm] first). */
    class Reply(val open: Boolean, val ask: Ask?)

    // [syllable]: the one the user typed for it (a pinyin commit's reading), -1 for none known
    private class Cell(val id: Int, val heard: Char, var now: Char, val syllable: Int = -1)

    // null: no window
    private var cells: MutableList<Cell>? = null
    private var nextId = 0

    // where the cursor is among [cells] (a gap's typed text counted in), -1 while not known: voice's
    // text in, its anchor not read yet, or a Backspace over what cannot be counted
    private var cursor = -1

    // the gap: what was deleted ([gone], in order) at [gapAt], what is typed into it so far
    private var gapAt = -1
    private val gone = ArrayList<Cell>()
    private val fill = StringBuilder()

    // the syllable typed for each of [fill], -1 where none is known (handwriting's)
    private val fillSyllables = ArrayList<Int>()

    // the gap is a selection still in the field: typed over, it is what is replaced
    private var selection = false

    // the engines' commits made while open, in order, for the app's report of each ([putIn]): each
    // text, simplified, and the ask its edit made
    private val claimed = ArrayDeque<Pair<String, Ask?>>()

    // the ask the last report handed out, till the app's read [confirm]s it
    private var pending: Ask? = null

    // the words asked about in this window: not asked again in it
    private val asked = HashSet<String>()

    // the words this window's prompts added: by the cells they are over
    private val remembered = ArrayList<Pair<Set<Int>, String>>()

    val open: Boolean get() = cells != null

    /** The once-only hint ([Ask.hint]) said already: not looked for (review S7). */
    var hinted = false

    /** Nothing followed any more: learning off, another field, input finished. */
    fun clear() {
        cells = null
        cursor = -1
        closeGap()
        claimed.clear()
        pending = null
        asked.clear()
        remembered.clear()
    }

    /**
     * One talk's text [text] in the field, [before] the cursor read once it was in (null: not read).
     * Joined to the window's if it goes on at its end, else a window of its own; an anchor that does
     * not match waits for the next [context] (review R6).
     */
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
            // what was typed before it and never reported (a talk on meanwhile) is no report to wait for
            claimed.clear()
            return
        }
        clear()
        this.cells = said.mapTo(ArrayList()) { Cell(nextId++, it, it) }
        trim()
        cursor = if (read != null && endsWithPart(read, fieldText())) this.cells!!.size else -1
    }

    // the last MAX_CHARS of it kept
    private fun trim() {
        val cells = cells ?: return
        val over = cells.size - MAX_CHARS
        if (over <= 0) return
        repeat(over) { cells.removeAt(0) }
        if (cursor >= 0) cursor -= over
    }

    /** A Backspace the app took (nothing typed): the character before the cursor deleted. */
    fun backspace() {
        val cells = cells ?: return
        pending = null
        if (cursor < 0) return clear()
        // what a Backspace takes away of an emoji sequence is the editor's to say: the cursor not known,
        // till a context finds it again (a Backspace before that ends the window)
        if (cells.any { !countable(it.now) } || gone.any { !countable(it.now) } || fill.any { !countable(it) }) {
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

    /**
     * The engines committed [text]; [typed]: pinyin's or handwriting's, of the keys or the pen, not
     * a prediction (联想) nor a table's (Q5); [reading]: the syllables typed for it (`xu jing`), null
     * where not known (handwriting's), a word asked about read so (review S2). Into the gap it fills
     * it; anything else, and anything but Chinese (a mark, a digit), ends the window. Told before the
     * app puts it in: the app's report ([putIn]) comes after.
     */
    fun committed(text: String, typed: Boolean, reading: String? = null) {
        if (cells == null) return
        pending = null
        val t = simplify(text)
        // a mark, a digit: no word typed again
        val typedChinese = typed && t.all(::han)
        if (cursor < 0 || gapAt < 0 || !typedChinese) return clear()
        selection = false
        fill.append(t)
        val syllables = reading?.split(' ', '\'')?.filter { it.isNotEmpty() }?.map(Syllables::id)
        if (syllables != null && syllables.size == t.length && syllables.all { it >= 0 }) fillSyllables += syllables
        else repeat(t.length) { fillSyllables += -1 }
        cursor = gapAt + fill.length
        if (fill.length > gone.size) return clear()
        // the Chinese deleted typed again, what followed it (a sentence's mark) not yet: judged now (review S5)
        val chineseGone = gone.indexOfFirst { !han(it.now) }.let { if (it < 0) gone.size else it }
        if (fill.length < chineseGone || (fill.length < gone.size && fill.length != chineseGone)) {
            claimed.addLast(t to null)
            return
        }
        claimed.addLast(t to filled())
    }

    // the gap's deleted cells back with what was typed into them, each keeping what voice wrote there; the
    // rest of it, if any, still deleted: what to ask
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

    /**
     * The app put [text] in the field: an engine's commit told already ([committed]), or something
     * else (punctuation, an emoji, a paste, 联想), which ends the window. With what to ask, if the
     * edit it made has a word to: for the app to read the field and [confirm].
     */
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

    /**
     * The field [before] and [after] the cursor, read as the prompt is to show (review M6): [Ask]
     * if they are as followed, else nothing, the window over.
     */
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

    /** [ask]'s word added: one asked at the same place again takes its place (review M4). */
    fun remembered(ask: Ask) {
        if (ask.hint) return
        remembered.removeAll { it.second == ask.replaces || it.second == ask.word }
        remembered += ask.cells to ask.word
    }

    /** [word] taken off the added words again (its prompt's 撤销). */
    fun forgot(word: String) {
        remembered.removeAll { it.second == word }
    }

    // [said] going on at the window's end, the cursor there, nothing being changed in it, [read] ending so
    private fun goesOn(cells: List<Cell>, read: String?, said: String): Boolean {
        if (gapAt >= 0 || cursor != cells.size) return false
        return read != null && endsWithPart(read, fieldText() + said)
    }

    /**
     * The host's context: the field [before] and [after] the cursor (or a selection), as
     * [Action.Context][org.fcitx.fcitx5.android.engine.session.Action.Context] has them. The cursor
     * found in the text followed, a stretch of it found deleted (a long press, a cut, Delete) or
     * selected; else the window ends.
     */
    fun context(before: String, after: String?, selected: Boolean, editorSaid: Boolean, cleared: Boolean) {
        if (cells == null) return
        pending = null
        if (cleared || (before.isEmpty() && !editorSaid)) return clear()
        val b = simplify(before)
        val a = after?.let(simplify)
        // a word typed into the gap part way: only where it goes on
        if (fill.isNotEmpty()) {
            if (selected || !at(b, a, fieldText(), cursor)) clear()
            return
        }
        val waiting = cursor < 0
        // a selection not typed over, the cursor put elsewhere: its text is still there
        if (selection) restoreGap()
        val text = fieldText()
        if (selected) return selectedIn(deleted(b, a, text)?.takeUnless { waiting })
        val k = cursorIn(b, a, text)
        if (k >= 0) {
            // the gap is kept only where the cursor still is
            if (gapAt >= 0 && k != gapAt) closeGap()
            cursor = k
            return
        }
        if (waiting) return clear()
        deletedIn(deleted(b, a, text) ?: return clear())
    }

    // [span] of the window selected (null: not found): taken out as a gap a word typed fills
    private fun selectedIn(span: Pair<Int, Int>?) {
        val cells = cells ?: return
        val (from, to) = span ?: return clear()
        // a gap left as it was: what was deleted stays so
        closeGap()
        selection = true
        gapAt = from
        repeat(to - from) { gone += cells.removeAt(from) }
        cursor = from
    }

    // [span] of the window found deleted: the gap's, if it is beside it, else a gap of its own
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

    // the field's text of the window as followed: a gap's typed text in it, a selection's too
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

    // the deleted stays deleted: the gap forgotten, the cells as they are
    private fun closeGap() {
        gapAt = -1
        gone.clear()
        fill.setLength(0)
        fillSyllables.clear()
        selection = false
    }

    // a selection's cells back where they were
    private fun restoreGap() {
        val cells = cells ?: return
        cells.addAll(gapAt, gone)
        closeGap()
    }

    // [k] the cursor in [text]: [b] ends with what is before it, [a] (if read) starts with what is after
    private fun at(b: String, a: String?, text: String, k: Int) =
        k in 0..text.length && endsWithPart(b, text.substring(0, k)) && (a == null || startsWithPart(a, text.substring(k)))

    // where the cursor is in [text]: the one place both sides match; with nothing read after it, the
    // furthest the text before it reaches. -1 for none, or more than one
    private fun cursorIn(b: String, a: String?, text: String): Int {
        if (a == null) return (text.length downTo 1).firstOrNull { endsWithPart(b, text.substring(0, it)) } ?: -1
        val found = (0..text.length).filter { at(b, a, text, it) }
        return found.singleOrNull() ?: -1
    }

    // the stretch of [text], not empty, the field has not: [b] ends with what is before it, [a] starts with
    // what is after. What is around the window is not known, so of those that fit, the one that keeps the
    // most of it (a stretch of the window's end deleted with what follows fits as well); one keeping none
    // of it is not told from the cursor put elsewhere
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

    // an edit of [n] cells from [at] made: what to ask, if anything (§2.4, §3)
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
        // (b) 2–4 characters deleted: the changed ones first, then all typed (review R4)
        if (n in 2..MAX_TYPED && y.all(::han)) {
            if (core.count() >= 2) spans += core
            spans += at until at + n
        }
        for (span in spans) asking(span, heard, now, mine)?.let { return it }
        // one character typed again: the way to have it remembered said once, where it would work (review R2)
        return if (n == 1 && !hinted) hintAround(core.first, heard, now, mine) else null
    }

    // the stretch of [x] (at [at] of the window) that [y] changed: the same characters at its ends left out
    private fun changed(x: String, y: String, at: Int): IntRange {
        val n = x.length
        var p = 0
        while (p < n && x[p] == y[p]) p++
        var s = 0
        while (s < n - p && x[n - 1 - s] == y[n - 1 - s]) s++
        return at + p until at + n - s
    }

    // the hint, if a word around the one character [c] typed again would be asked about
    private fun hintAround(c: Int, heard: String, now: String, mine: Mine): Ask? {
        val around = listOf(c - 1..c, c..c + 1, c - 2..c, c - 1..c + 1, c..c + 2)
        for (span in around) {
            if (span.first < 0 || span.last >= heard.length) continue
            if (asking(span, heard, now, mine) != null) return Ask("", "", "", null, hint = true, cells = emptySet())
        }
        return null
    }

    // the longest word of the user's (added or learned) in [now] over [core], at most MAX_WORD characters
    private fun ownOver(now: String, core: IntRange, mine: Mine): IntRange? {
        for (len in MAX_WORD downTo maxOf(2, core.count())) {
            for (from in maxOf(0, core.last + 1 - len)..minOf(core.first, now.length - len)) {
                val w = now.substring(from, from + len)
                if (w in mine.added || w in mine.learned) return from until from + len
            }
        }
        return null
    }

    // [span] of the window asked about, if it passes every check of §3
    private fun asking(span: IntRange, heard: String, now: String, mine: Mine): Ask? {
        val w = now.substring(span.first, span.last + 1)
        val xw = heard.substring(span.first, span.last + 1)
        if (w.length < 2 || w == xw || !w.all(::han)) return null
        if (w in mine.added || w in asked) return null
        if (mine.blocked.any { it.isNotEmpty() && it in w }) return null
        val pinyin = pinyinOf(span, heard, now, w) ?: return null
        val ids = span.mapTo(HashSet()) { cells!![it].id }
        // one added at the place before is off the words as this goes in: voice judged without it (review M4)
        val replaces = remembered.firstOrNull { (over, word) -> word != w && over.any(ids::contains) }?.second
        if (!words.takes(heard, span.first, xw, w, pinyin, replaces)) return null
        return Ask(w, pinyin, xw, replaces, hint = false, cells = ids)
    }

    // [w]'s syllables: the one typed for a character where it is known and the character may read so (one
    // changed: as what voice heard there reads too), else its reading where it has one, else the
    // dictionary's for the word if it is among them. A typed syllable none of those readings has (a
    // reading the tables lack) asks nothing: what it would add, voice could not hear as typed
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

    // the readings [x] and [y] share, tones and all (one not known taken as any of its syllable)
    private fun same(x: Char, y: Char): List<Int> {
        val rx = words.readings(x) ?: return emptyList()
        val ry = words.readings(y) ?: return emptyList()
        return ry.filter { r -> rx.any { it == r || (it / READING_TONES == r / READING_TONES && (it % READING_TONES == 0 || r % READING_TONES == 0)) } }
    }

    companion object {
        /** what is kept of voice's text: its last characters */
        const val MAX_CHARS = 256

        // as long as a word of the user's may be (VoiceHomophones' MAX_WORD)
        private const val MAX_WORD = 12

        // deleted and typed again, a word asked about whole (§3 b)
        private const val MAX_TYPED = 4

        // as VoiceHomophones.reading packs them: a syllable times 8 plus its tone
        private const val READING_TONES = 8

        // as much as the host reads either side ([Action.Context.HOST_CHARS]): one that long may be cut short
        private const val READ_CHARS = 64

        private fun han(c: Char) = c in '一'..'鿿'

        // one a Backspace takes alone: no surrogate, no joiner, selector or mark
        private fun countable(c: Char) = !Character.isSurrogate(c) && Character.getType(c).let {
            it != Character.FORMAT.toInt() && it != Character.NON_SPACING_MARK.toInt() && it != Character.ENCLOSING_MARK.toInt()
        } && c !in '︀'..'️'

        // [read] (what the host read before the cursor) ends with [s], or with as much of it as was read
        internal fun endsWithPart(read: String, s: String) =
            if (read.length >= s.length) read.endsWith(s) else read.length >= READ_CHARS && s.endsWith(read)

        // [read] (after the cursor) starts with [s], or with as much of it as was read
        internal fun startsWithPart(read: String, s: String) =
            if (read.length >= s.length) read.startsWith(s) else read.length >= READ_CHARS && s.startsWith(read)
    }
}
