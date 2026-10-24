/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Absorbed
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BarRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BatchRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Cut
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.DateRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnAdd
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCorrect
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCommit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnForget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnPairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.FirstRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Forget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Head
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportBatch
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportUndo
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Imported
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportedRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.MineRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.NoPredict
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.NoPredictRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PendingRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Pin
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PinRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Restored
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.StatRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableBlock
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableBlockRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableOrderReset
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Undo
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.WordUseRow
import java.util.concurrent.atomic.AtomicLong

/**
 * What the learning record says (PLAN §2.2): built only by [apply], record after record, the
 * same way at open, in a fold and as the user types, so the three agree (impl/design-1a.md §2).
 * Nothing outside the records is read: what needs the dictionary or the clock was decided when
 * the record was written.
 *
 * Expiry is by typing day, swept once as a record takes the day on, never by the clock. Counts
 * are taken back by subtraction; a first, a pending text and the user's words are put back by an
 * [Undo]'s [Before]s, and only while the commit undone is still the last to have changed them
 * ([First.lastBy] and so on). A commit removes none of those rows, so an absent one was removed by
 * the user ([Forget]), and stays so.
 */
class LearnState {
    /** (code, words) used in a commit, by typing day; [firstSeq] the commit that first used them there. */
    class Stat(var words: Words, val uses: DayCounts, val corrections: DayCounts, var older: Int, val firstSeq: Long = 0)

    data class StatKey(val code: Code, val text: String, val reading: String)
    data class FirstKey(val code: Code, val prev: String)
    data class PairKey(val prev: String, val next: String, val nextReading: String)

    /** A count and the typing day it last went up. */
    class Counted(var count: Int, var lastDay: Int)

    class EnWord(var flags: Int, val uses: DayCounts)

    var cut = -1
    var lastMinute = Int.MIN_VALUE
    var day = 0
    var localDay = Int.MIN_VALUE
    var nextSeq = 1L
    var convertedDay = -1
    var restoredMinute = -1
    var convertedTables = ""

    private val statMap = HashMap<StatKey, Stat>()
    // the same by code, for a code's used words (impl/design-1b.md S10): kept with statMap, as only this class changes it
    private val statsByCode = HashMap<Code, HashMap<StatKey, Stat>>()
    val stats: Map<StatKey, Stat> get() = statMap

    /** The rows of what was used under [code]. */
    fun statsOf(code: Code): Map<StatKey, Stat> = statsByCode[code].orEmpty()

    // those spelling their words in full or by 简拼, by word and by keyboard and first key
    // ([SpelledForm.filedUnder]): what U-20 counts at a key, with no pass over every row after
    // each commit (R14 F3733); kept with statMap too
    private val statsByWord = HashMap<Pair<String, String>, HashMap<StatKey, Stat>>()
    private val spelledByFirst = HashMap<Pair<String, Char>, HashMap<StatKey, Stat>>()

    /** The rows of [text] read [reading] under codes spelling it in full or by 简拼 ([SpelledForm]), of any keyboard. */
    fun statsOf(text: String, reading: String): Map<StatKey, Stat> = statsByWord[text to reading].orEmpty()

    /** The rows spelling their words in full or by 简拼 on [keyboard] ([Kinds.keyboard]), filed under [first] ([SpelledForm.filedUnder]). */
    fun spelledFrom(keyboard: String, first: Char): Map<StatKey, Stat> = spelledByFirst[keyboard to first].orEmpty()

    /** The words of the whole commits by text, then reading: when each was used ([WordUseRow]). */
    val wordUses = HashMap<String, HashMap<String, DayCounts>>()
    val firsts = HashMap<FirstKey, First>()
    val pending = HashMap<String, Pending>()
    val mine = HashMap<String, Mine>()
    val pairs = HashMap<PairKey, Counted>()
    val pins = HashMap<Code, Words>()
    val bars = HashMap<Take, Counted>()
    val enWords = HashMap<String, EnWord>()
    val enPairs = HashMap<Pair<String, String>, Counted>()
    val tableWords = HashMap<Triple<String, String, String>, Int>()
    val noPredict = HashSet<Pair<String, String>>()

    /** The calendar day of each typing day in the window, as its first commit had it: a word's last use as a date (U-80). */
    val dates = HashMap<Int, Int>()
    /** The words imported (U-101), by batch. */
    var imports = ImportedWords()
        private set

    /** [was]'s words imported made what this state's are, and this state's: a record read afresh keeps their places ([ImportedWords.takeFrom]). */
    fun keepImportsOf(was: LearnState, endUndo: Boolean = true) {
        if (was.imports === imports) return
        was.imports.takeFrom(imports, endUndo)
        imports = was.imports
    }

    /** The texts each table code shows no more, by table and code (U-28 屏蔽). */
    val tableBlocks = HashMap<Pair<String, String>, MutableSet<String>>()

    /**
     * What applying records changed that the user model keeps apart from them (impl/design-1b.md
     * §3): the user's words that came to be and those gone, by text with their reading, the texts
     * forgotten, whether the words imported changed (U-101: the model reads them from [imports]),
     * and whether the decoder's counts are to be made again whole, else from the [commits] alone.
     */
    class Changes {
        val formed = LinkedHashMap<String, String>()
        val removed = LinkedHashMap<String, String>()
        val forgotten = ArrayList<String>()
        var recount = false

        /** The 30 typing days moved on a day: every count is another by that day's uses, a recount that may wait (HabitCounts.settle). */
        var window = false
        val commits = ArrayList<Commit>()
        var imports = false

        fun isEmpty() = formed.isEmpty() && removed.isEmpty() && forgotten.isEmpty() && !recount && !window && commits.isEmpty() && !imports

        companion object {
            /** From [was] to [now], a state made anew (a log rewritten): the user's words that came and went, every count anew. */
            fun between(was: LearnState, now: LearnState) = Changes().also { c ->
                for ((text, m) in was.mine) if (now.mine[text]?.reading != m.reading) c.removed[text] = m.reading
                for ((text, m) in now.mine) if (was.mine[text]?.reading != m.reading) c.formed[text] = m.reading
                c.recount = true
                // the imported words made anew too: what keeps their places (a table's codes) recodes them (review-G M1)
                c.imports = true
            }
        }
    }

    private var changes = Changes()

    /** What was changed since last taken. */
    fun takeChanges(): Changes = changes.also { changes = Changes() }

    /** Whether nothing is learned: a log of a snapshot of nothing, as a first start leaves it, is none. */
    fun isEmpty() = stats.isEmpty() && wordUses.isEmpty() && firsts.isEmpty() && pending.isEmpty() && mine.isEmpty() && pairs.isEmpty() && pins.isEmpty() &&
        bars.isEmpty() && enWords.isEmpty() && enPairs.isEmpty() && tableWords.isEmpty() && noPredict.isEmpty() && tableBlocks.isEmpty() &&
        imports.isEmpty() && kept.isEmpty()

    /** Records this build keeps as they are: of a type it does not know. */
    val kept = ArrayList<LearnRecord>()

    /**
     * Goes up with each record applied: a view made of the state (English's words, [org.fcitx.fcitx5.android.engine.english.EnglishUser],
     * the bar's picks by their keys) knows by it that what it made is stale.
     */
    var version = 0L
        private set

    /**
     * Goes up as English's words may have changed: its records, an undo, an absorb, its rows, the
     * typing day moved. Not with pinyin's commits, so what pinyin spells of them (EN-24) is not made
     * again after each (review-uat-fixes S9).
     */
    var englishVersion = 0L
        private set

    /** Goes up as [tableBlocks] changes (a table's per-code blocks, as event or row): what a cache of them keys on (F3137). */
    var tableBlocksVersion = 0L
        private set

    /** This state's own number, none other's: a cache keyed on which state it was made of holds no state (F3216). */
    val id = ids.incrementAndGet()

    /** Applies [record]: what it changed of the rows an [Undo] puts back, as they were. */
    fun apply(record: LearnRecord): List<Before> {
        version++
        if (touchesEnglish(record)) englishVersion++
        record.minute?.let { lastMinute = it }
        when (record) {
            is Commit -> return commit(record)
            is Undo -> undo(record)
            is Pin -> pin(record)
            is Forget -> forget(record.text)
            is NoPredict -> if (record.on) noPredict += record.prev to record.text else noPredict -= record.prev to record.text
            is TableOrderReset -> tableOrderReset(record.im)
            is TableBlock -> tableBlock(TableBlockRow(record.im, record.code, record.text), record.on)
            is EnCommit -> enCommit(record)
            is EnForget -> {
                enWords.remove(record.word)
                enPairs.keys.removeAll { it.first == record.word || it.second == record.word }
            }
            is EnAdd -> enAdd(record.word, record.on)
            is EnCorrect -> enCorrect(record.word)
            is Restored -> restoredMinute = record.minute
            is Absorbed -> absorb(record)
            is ImportBatch, is Imported, is ImportUndo -> noteImports(imports.apply(record))
            else -> row(record)
        }
        return emptyList()
    }

    private fun touchesEnglish(record: LearnRecord) = when (record) {
        is EnCommit, is EnForget, is EnAdd, is EnCorrect, is Undo, is Absorbed, is EnWordRow, is EnPairRow -> true
        else -> false
    }

    private fun pin(record: Pin) {
        if (record.on) {
            pins[record.code] = Words.of(record.text, record.reading)
        } else if (pins[record.code]?.text == record.text) {
            pins.remove(record.code)
        }
    }

    // a snapshot's row, or a record kept as it is
    private fun row(record: LearnRecord) {
        when (record) {
            is Head -> head(record)
            is StatRow -> putStat(StatKey(record.code, record.words.text, record.words.reading), Stat(record.words, record.uses.copy(), record.corrections.copy(), record.older, record.firstSeq))
            is FirstRow -> firsts[FirstKey(record.code, record.prev)] = record.value
            is PendingRow -> pending[record.text] = record.value
            is MineRow -> setMine(record.text, record.value)
            is PairRow -> pairs[PairKey(record.prev, record.next, record.nextReading)] = Counted(record.count, record.lastDay)
            is PinRow -> pins[record.code] = Words.of(record.text, record.reading)
            is BarRow -> bars[record.take] = Counted(record.count, record.lastDay)
            is EnWordRow -> enWords[record.word] = EnWord(record.flags, record.uses.copy())
            is EnPairRow -> enPairs[record.prev to record.word] = Counted(record.count, record.lastDay)
            is TableWordRow -> tableWords[Triple(record.im, record.code, record.text)] = record.since
            is NoPredictRow -> noPredict += record.prev to record.text
            is TableBlockRow -> tableBlock(record, on = true)
            is WordUseRow -> wordUses.getOrPut(record.text) { HashMap(2) }[record.reading] = record.uses.copy()
            is DateRow -> dates[record.day] = record.localDay
            is BatchRow, is ImportedRow -> noteImports(imports.apply(record))
            is Cut -> Unit
            else -> kept += record
        }
    }

    private fun head(h: Head) {
        cut = h.cut
        lastMinute = h.lastMinute
        day = h.day
        localDay = h.localDay
        nextSeq = h.nextSeq
        convertedDay = h.convertedDay
        restoredMinute = h.restoredMinute
        convertedTables = h.convertedTables
    }

    /** Takes the typing day on to [to], first sweeping what has not been used for [WINDOW] days by then. */
    private fun advance(to: Int) {
        if (to <= day) return
        if (day > 0) sweep(to)
        day = to
        englishVersion++
        // the window moved: every word's count is another
        changes.window = true
    }

    private fun old(lastDay: Int, today: Int) = lastDay == Int.MIN_VALUE || today - lastDay >= WINDOW

    /** What the day's sweep drops by typing day [today], the day itself left: a state merged of two (BackupMerge) as one of them would hold it. */
    internal fun sweepTo(today: Int) {
        // changed outside [apply]: a view made of it is stale as after a record
        version++
        englishVersion++
        sweep(today)
    }

    private fun sweep(today: Int) {
        val from = today - WINDOW + 1
        removeStatsIf { _, s -> old(s.uses.lastDay, today) }
        for (s in statMap.values) {
            s.uses.dropBefore(from)
            s.older += s.corrections.dropBefore(from)
        }
        val uses = wordUses.values.iterator()
        while (uses.hasNext()) {
            val readings = uses.next()
            readings.values.removeAll { it.dropBefore(from); it.isEmpty() }
            if (readings.isEmpty()) uses.remove()
        }
        firsts.values.removeAll { old(it.lastDay, today) }
        dates.keys.removeAll { it < from }
        pending.values.removeAll { old(it.firstDay, today) }
        pairs.values.removeAll { old(it.lastDay, today) }
        bars.values.removeAll { old(it.lastDay, today) }
        enPairs.values.removeAll { old(it.lastDay, today) }
        val en = enWords.values.iterator()
        while (en.hasNext()) {
            val w = en.next()
            w.uses.dropBefore(from)
            if (w.uses.isEmpty() && w.flags == 0) en.remove()
        }
    }

    private fun commit(c: Commit): List<Before> {
        advance(c.day)
        localDay = c.localDay
        nextSeq = maxOf(nextSeq, c.seq + 1)
        dates.getOrPut(c.day) { c.localDay }
        val befores = LinkedHashMap<Any, Before>()
        changes.commits += c
        for (row in c.rows) {
            if (!keyed(row, c)) continue
            val key = StatKey(row.code, row.words.text, row.words.reading)
            val stat = statMap[key] ?: Stat(row.words, DayCounts(), DayCounts(), 0, c.seq).also { putStat(key, it) }
            stat.words = row.words
            stat.uses.add(c.day, 1)
            if (row.corrected) stat.corrections.add(c.day, 1)
            first(row, c, befores)
            mine[row.words.text]?.takeIf { it.reading == row.words.reading }?.let {
                mine[row.words.text] = it.copy(lastUsedDay = c.day, lastUsedLocalDay = c.localDay)
            }
        }
        for (row in c.rows) {
            if (!row.whole) continue
            // formed first: the commit that makes it the user's word counts it as one, as its undo does
            form(row, c, befores)
            wordsOf(row, c) { w -> wordUses.getOrPut(w.text) { HashMap(2) }.getOrPut(w.reading) { DayCounts() }.add(c.day, 1) }
            if (c.flags and Commit.UNPAIRED == 0) pairsOf(row) { key -> pairs.getOrPut(key) { Counted(0, c.day) }.let { it.count++; it.lastDay = c.day } }
        }
        for (take in c.takes) bars.getOrPut(take) { Counted(0, c.day) }.let { it.count++; it.lastDay = c.day }
        return befores.values.toList()
    }

    // a table's or handwriting's whole commit, under no keys (KeyedRows.of): its words' uses and pairs
    // alone, for 联想; no STAT nor FIRST, one for each text typed there would be nobody's (review C S8)
    private fun keyed(row: Row, c: Commit) = (row.code.keys.isNotEmpty() || !Kinds.byText(row.code.kind)) &&
        // a 联想 picked in a search field ranks nothing (U-91), as its pairs count none
        (row.code.kind != Kinds.PREDICTION || c.flags and Commit.UNPAIRED == 0)

    /**
     * The spec's §1 「连着纠正两次」: a word corrected to, then picked again with no use of the
     * original first between (corrected to, or shown first by then), becomes the first; the first used empties the set, the original first used where another was shown restarts it with itself. No row is made for a use that
     * changes nothing: one per (code, prev) the user ever typed would be most of the state.
     */
    private fun first(row: Row, c: Commit, befores: MutableMap<Any, Before>) {
        val key = FirstKey(row.code, row.prev)
        val old = firsts[key]
        if (old == null && !row.corrected) return
        var first = old?.first
        var set = old?.set.orEmpty()
        when {
            row.words == first -> set = emptyList()
            // picked again, corrected to or shown first by then (调频's models, UAT TBL-33): the streak goes on
            row.words in set -> {
                first = row.words
                set = emptyList()
            }
            // the decoder's or table's own first picked over another shown first (调频, a pin): the others' streak
            // restarts, its own begins, so a first learned can still be taken back by it (F2739)
            row.ownFirst -> set = listOf(row.words)
            // the first shown used, no word of the streak: it restarts (docs/pinyin.md rule 2)
            !row.corrected -> set = emptyList()
            else -> set = set + row.words
        }
        val changed = old == null || first != old.first || set != old.set
        if (changed) befores.getOrPut(key) { Before.OfFirst(key.code, key.prev, old) }
        firsts[key] = First(first, set, c.day, if (changed) c.seq else old.lastBy)
    }

    /** U-06: text of none's (2 to 4 Han characters) or imported, committed twice within [WINDOW] typing days, is the user's. */
    private fun form(row: Row, c: Commit, befores: MutableMap<Any, Before>) {
        val text = row.words.text
        // a phrase has no reading: no word to make of it
        if (!formable(row) || row.words.reading.isEmpty() || text in mine) return
        val was = pending[text]
        if (was != null && c.day - was.firstDay < WINDOW) {
            befores.getOrPut("mine" to text) { Before.OfMine(text, null) }
            val how = if (row.origin == Row.IMPORTED) Mine.FORMED_FROM_IMPORT else Mine.FORMED
            // the pending row stays till it expires: a commit removes nothing an undo could not tell it removed
            setMine(text, Mine(row.words.reading, how, c.day, c.seq, c.day, c.localDay, c.seq))
        } else {
            befores.getOrPut("pending" to text) { Before.OfPending(text, was) }
            pending[text] = Pending(row.words.reading, c.day, c.seq)
        }
    }

    private inline fun wordsOf(row: Row, c: Commit, each: (Word) -> Unit) = countedWords(row, c).forEach(each)

    /**
     * The words of [row], of [c], the decoder counts ([countedWords]): its text one word if the user's
     * word read so by [c] (made so by it too), which the decoder reads as one; the same at [c]'s undo,
     * which takes back what it made after.
     */
    fun countedWords(row: Row, c: Commit): List<Word> =
        countedWords(row, mine[row.words.text]?.let { it.reading == row.words.reading && it.sinceSeq <= c.seq } == true)

    /** The user's word [text] as [value]: noted as formed if it was none. */
    private fun setMine(text: String, value: Mine) {
        val was = mine.put(text, value)
        if (was == null || was.reading != value.reading) {
            if (was != null) noteRemoved(text, was.reading)
            if (changes.removed[text] == value.reading) changes.removed.remove(text) else changes.formed[text] = value.reading
        }
    }

    private fun dropMine(text: String) {
        mine.remove(text)?.let { noteRemoved(text, it.reading) }
    }

    // the words imported changed: what the user model follows
    private fun noteImports(changed: Boolean) {
        if (changed) changes.imports = true
    }

    private fun noteRemoved(text: String, reading: String) {
        if (changes.formed[text] == reading) changes.formed.remove(text) else changes.removed[text] = reading
    }

    private fun putStat(key: StatKey, stat: Stat) {
        statMap[key] = stat
        statsByCode.getOrPut(key.code) { HashMap() }[key] = stat
        // only the rows U-20 counts, words of two syllables or more spelt in full or by 简拼 (R14 F3761)
        val spelled = spelledKey(key) ?: return
        statsByWord.getOrPut(key.text to key.reading) { HashMap(2) }[key] = stat
        spelledByFirst.getOrPut(spelled) { HashMap() }[key] = stat
    }

    private fun spelledKey(key: StatKey): Pair<String, Char>? {
        val form = SpelledForm.of(key.code.kind, key.code.keys, key.reading)
        if (form != SpelledForm.FULL && form != SpelledForm.INITIALS) return null
        val kind = Kinds.keyboard(key.code.kind)
        return SpelledForm.filedUnder(kind, key.code.keys, key.reading)?.let { kind to it }
    }

    private fun <K> HashMap<K, HashMap<StatKey, Stat>>.drop(at: K, key: StatKey) {
        val of = this[at] ?: return
        of.remove(key)
        if (of.isEmpty()) remove(at)
    }

    private fun removeStat(key: StatKey) {
        statMap.remove(key) ?: return
        statsByCode.drop(key.code, key)
        val spelled = spelledKey(key) ?: return
        statsByWord.drop(key.text to key.reading, key)
        spelledByFirst.drop(spelled, key)
    }

    private inline fun removeStatsIf(drop: (StatKey, Stat) -> Boolean) {
        val gone = statMap.entries.filter { drop(it.key, it.value) }.map { it.key }
        gone.forEach(::removeStat)
    }

    // the pairs a commit's words make, after the word before them
    private inline fun pairsOf(row: Row, each: (PairKey) -> Unit) {
        var prev = row.prev
        for (w in heard(row)) {
            if (prev.isNotEmpty()) each(PairKey(prev, w.text, w.reading))
            prev = w.text
        }
    }

    private fun undo(u: Undo) {
        changes.recount = true
        when (val c = u.undone) {
            is Commit -> uncount(c)
            is EnCommit -> {
                // a word left with no use and no flag goes, as an EN_ADD taken back leaves it (enAdd)
                enWords[c.word]?.let { w ->
                    w.uses.add(c.day, -1)
                    if (w.flags == 0 && w.uses.isEmpty()) enWords.remove(c.word)
                }
                // only a pair the commit counted (enCommit): not one of a field that learns none (review-int-records S2)
                if (c.prev.isNotEmpty() && c.flags and Commit.UNPAIRED == 0) {
                    enPairs[c.prev to c.word]?.let { if (--it.count <= 0) enPairs.remove(c.prev to c.word) }
                }
            }
            // a commit of a kind this build does not know: what it counted is not known either
            else -> Unit
        }
        for (b in u.befores) restore(b, u.undoneSeq)
    }

    // a use of [w] on [day] taken back
    private fun unuse(w: Word, day: Int) {
        val readings = wordUses[w.text]
        readings?.get(w.reading)?.let { uses ->
            uses.add(day, -1)
            if (uses.isEmpty()) readings.remove(w.reading)
        }
        if (readings?.isEmpty() == true) wordUses.remove(w.text)
    }

    // what [c] counted, taken back
    private fun uncount(c: Commit) {
        for (row in c.rows) {
            val key = StatKey(row.code, row.words.text, row.words.reading)
            val stat = statMap[key]?.takeIf { keyed(row, c) } ?: continue
            stat.uses.add(c.day, -1)
            if (row.corrected) stat.corrections.add(c.day, -1)
            if (stat.uses.isEmpty() && stat.corrections.isEmpty() && stat.older == 0) removeStat(key)
        }
        for (row in c.rows) {
            if (!row.whole) continue
            wordsOf(row, c) { unuse(it, c.day) }
            if (c.flags and Commit.UNPAIRED == 0) pairsOf(row) { key -> pairs[key]?.let { if (--it.count <= 0) pairs.remove(key) } }
        }
        for (take in c.takes) bars[take]?.let { if (--it.count <= 0) bars.remove(take) }
    }

    private fun restore(b: Before, seq: Long) {
        when (b) {
            is Before.OfFirst -> {
                val key = FirstKey(b.code, b.prev)
                if (firsts[key]?.lastBy != seq) return
                if (b.value == null) firsts.remove(key) else firsts[key] = b.value
            }
            is Before.OfPending -> {
                if (pending[b.text]?.lastBy != seq) return
                if (b.value == null) pending.remove(b.text) else pending[b.text] = b.value
            }
            is Before.OfMine -> {
                if (mine[b.text]?.lastBy != seq) return
                if (b.value == null) dropMine(b.text) else setMine(b.text, b.value)
            }
        }
    }

    /** U-84: [text] forgotten under every code and after every word; its firsts no undo puts back. */
    private fun forget(text: String) {
        changes.recount = true
        changes.forgotten += text
        removeStatsIf { key, _ -> key.text == text }
        wordUses.remove(text)
        val keys = firsts.keys.toList()
        for (key in keys) {
            val f = firsts.getValue(key)
            if (key.prev == text) {
                firsts.remove(key)
                continue
            }
            val first = f.first?.takeIf { it.text != text }
            val set = f.set.filter { it.text != text }
            if (first != f.first || set.size != f.set.size) firsts[key] = First(first, set, f.lastDay, FORGOTTEN)
        }
        pending.remove(text)
        dropMine(text)
        pairs.keys.removeAll { it.prev == text || it.next == text }
        // the tables forget it too (Engines.forgottenEverywhere): their saved words as their logs have them
        tableWords.keys.removeAll { it.third == text }
        // an imported word deleted is gone (U-82 删除)
        noteImports(imports.forget(text))
    }

    private fun absorb(a: Absorbed) {
        changes.recount = true
        for (r in a.removed) when (r) {
            is MineRow -> forget(r.text)
            is TableWordRow -> tableWords.remove(Triple(r.im, r.code, r.text))
            is EnWordRow -> apply(EnForget(a.minute, r.word))
            else -> Unit
        }
        for (r in a.added) when (r) {
            // by hand, not Map.putIfAbsent: written for an API floor below 24
            is MineRow -> {
                if (r.text !in mine) setMine(r.text, r.value)
                // its sequence taken: no commit after it shares it (review-int-records S4)
                nextSeq = maxOf(nextSeq, r.value.sinceSeq + 1)
            }
            // an export's count, where the word has no uses of that reading
            is WordUseRow -> wordUses.getOrPut(r.text) { HashMap(2) }.let { if (r.reading !in it) it[r.reading] = r.uses.copy() }
            is TableWordRow -> Triple(r.im, r.code, r.text).let { if (it !in tableWords) tableWords[it] = r.since }
            is EnWordRow -> enWords.getOrPut(r.word) { EnWord(0, DayCounts()) }.let { it.flags = it.flags or r.flags }
            else -> Unit
        }
    }

    private fun tableBlock(r: TableBlockRow, on: Boolean) {
        val key = r.im to r.code
        tableBlocksVersion++
        if (on) {
            tableBlocks.getOrPut(key) { HashSet(2) } += r.text
        } else {
            val texts = tableBlocks[key] ?: return
            texts -= r.text
            if (texts.isEmpty()) tableBlocks.remove(key)
        }
    }

    private fun tableOrderReset(im: String) {
        val kind = Kinds.table(im)
        removeStatsIf { key, _ -> key.code.kind == kind }
        firsts.keys.removeAll { it.code.kind == kind }
    }

    private fun enCommit(c: EnCommit) {
        advance(c.day)
        localDay = c.localDay
        nextSeq = maxOf(nextSeq, c.seq + 1)
        dates.getOrPut(c.day) { c.localDay }
        val w = enWords.getOrPut(c.word) { EnWord(0, DayCounts()) }
        w.uses.add(c.day, 1)
        if (c.how == EnCommit.REVERTED) w.flags = w.flags or EnWordRow.NEVER_CORRECT
        if (c.prev.isNotEmpty() && c.flags and Commit.UNPAIRED == 0) {
            enPairs.getOrPut(c.prev to c.word) { Counted(0, c.day) }.let { it.count++; it.lastDay = c.day }
        }
    }

    // corrected again (U-31 恢复): neither never corrected nor added, the uses and pairs kept
    private fun enCorrect(word: String) {
        val w = enWords[word] ?: return
        w.flags = w.flags and (EnWordRow.NEVER_CORRECT or EnWordRow.ADDED).inv()
        if (w.flags == 0 && w.uses.isEmpty()) enWords.remove(word)
    }

    private fun enAdd(word: String, on: Boolean) {
        val w = enWords.getOrPut(word) { EnWord(0, DayCounts()) }
        w.flags = if (on) w.flags or EnWordRow.ADDED else w.flags and EnWordRow.ADDED.inv()
        if (w.flags == 0 && w.uses.isEmpty()) enWords.remove(word)
    }

    /** The state as a snapshot's rows, [cut] its cut, each in a canonical order, then what is [kept], then [Cut]. */
    fun snapshot(cut: Int): List<LearnRecord> = buildList {
        add(Head(cut, lastMinute, day, localDay, nextSeq, convertedDay, restoredMinute, convertedTables))
        for ((k, s) in stats.entries.sortedWith(compareBy({ it.key.code.kind }, { it.key.code.keys }, { it.key.text }, { it.key.reading }))) {
            add(StatRow(k.code, s.words, s.uses, s.corrections, s.older, s.firstSeq))
        }
        for ((k, f) in firsts.entries.sortedWith(compareBy({ it.key.code.kind }, { it.key.code.keys }, { it.key.prev }))) add(FirstRow(k.code, k.prev, f))
        for ((t, p) in pending.entries.sortedBy { it.key }) add(PendingRow(t, p))
        for ((t, m) in mine.entries.sortedBy { it.key }) add(MineRow(t, m))
        for ((k, c) in pairs.entries.sortedWith(compareBy({ it.key.prev }, { it.key.next }, { it.key.nextReading }))) {
            add(PairRow(k.prev, k.next, k.nextReading, c.count, c.lastDay))
        }
        for ((code, w) in pins.entries.sortedWith(compareBy({ it.key.kind }, { it.key.keys }))) add(PinRow(code, w.text, w.reading))
        for ((t, c) in bars.entries.sortedWith(compareBy({ it.key.layout }, { it.key.digits }, { it.key.syllable }))) add(BarRow(t, c.count, c.lastDay))
        for ((word, w) in enWords.entries.sortedBy { it.key }) add(EnWordRow(word, w.flags, w.uses))
        for ((k, c) in enPairs.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) add(EnPairRow(k.first, k.second, c.count, c.lastDay))
        for ((k, since) in tableWords.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }, { it.key.third }))) {
            add(TableWordRow(k.first, k.second, k.third, since))
        }
        for ((prev, text) in noPredict.sortedWith(compareBy({ it.first }, { it.second }))) add(NoPredictRow(prev, text))
        for ((k, texts) in tableBlocks.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            for (text in texts.sorted()) add(TableBlockRow(k.first, k.second, text))
        }
        for ((text, readings) in wordUses.entries.sortedBy { it.key }) {
            for ((reading, uses) in readings.entries.sortedBy { it.key }) add(WordUseRow(text, reading, uses))
        }
        for ((d, local) in dates.entries.sortedBy { it.key }) add(DateRow(d, local))
        addAll(imports.rows())
        addAll(kept)
        add(Cut)
    }

    /** The state as text, in a canonical order, the cut left out: for tests to compare states built different ways. */
    fun dump(): String = snapshot(0).drop(1).joinToString("\n", prefix = "day=$day localDay=$localDay lastMinute=$lastMinute next=$nextSeq " +
        "converted=$convertedDay/$convertedTables restored=$restoredMinute\n") { r ->
        when (r) {
            is StatRow -> "stat ${r.code} ${r.words.list} uses=${r.uses} corr=${r.corrections} older=${r.older} first=${r.firstSeq}"
            else -> r.toString()
        }
    }

    companion object {
        private val ids = AtomicLong()

        /** The spec's 最近: typing days. */
        const val WINDOW = 30

        /** U-06: a commit longer than this is not made a word. */
        const val MAX_FORMED = 4

        /**
         * [row]'s words as they count: a table's, handwriting's or 联想's by their text alone, any
         * reading they carry (handwriting's, to form the user's word, U-06) dropped, never pinyin's
         * order to move (D28).
         */
        fun heard(row: Row): List<Word> =
            if (Kinds.byText(row.code.kind)) row.words.list.map { if (it.reading.isEmpty()) it else Word(it.text, "") } else row.words.list

        /** Whether [row]'s text is one the user's word may form of (U-06): of none's, 2 to 4 Han characters, or imported. */
        fun formable(row: Row): Boolean {
            val text = row.words.text
            return row.origin == Row.IMPORTED || (row.origin == Row.NONE && text.codePointCount(0, text.length) in 2..MAX_FORMED && Han.all(text))
        }

        /**
         * The words of a whole row the decoder counts, once a time each is in it (好｜好 twice); text
         * kept as typed, which has no reading, is none, but for a table's or handwriting's words, read
         * by their text. Words corrected to (pieces put together, a reading picked over the first)
         * whose text may form the user's word count as that one word, as an older build learned them
         * as one phrase: counted once it is a word, not their pieces now (汪 and 小明, once picked
         * so, would lift the sentence 汪小明 over the first it was picked over); so does the text of
         * a word of the user's already ([isWord]), which the decoder reads as one. A sentence put in
         * as read counts its words.
         */
        fun countedWords(row: Row, isWord: Boolean = false): List<Word> {
            val list = heard(row)
            val one = (row.corrected && formable(row)) || isWord
            if (one && list.size > 1 && list.all { it.reading.isNotEmpty() }) return listOf(Word(row.words.text, row.words.reading))
            val byText = Kinds.byText(row.code.kind)
            return list.filter { w -> w.text.isNotEmpty() && (w.reading.isNotEmpty() || byText) }
        }

        /** The last change of a first a [Forget] made: no undo puts it back. */
        const val FORGOTTEN = -1L
    }
}
