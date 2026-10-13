/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.keepBest
import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.toMatches
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Companion.SEPARATOR
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import java.util.TreeMap

/**
 * Cuts pinyin typed on a phone's nine keys (九键) into a [SyllableGraph]: each of 2 to 9 stands
 * for its [letters] (on a phone `2` abc … `9` wxyz, ü as v on `8`), so `64426` is ni'hao, mi'gao
 * and the rest, all of them in the graph for the decoder to weigh. A letter the user put on more
 * than one key is read from each. The separator is `'`: the session takes
 * the keyboard's 1 for it.
 *
 * From each digit the graph gets:
 * - every syllable the digits there type, of any length, with their [Fuzzy] partners;
 * - at the end of the input or before a separator, every syllable the digits may still become
 *   ([Kind.PARTIAL], or [Kind.EXTENDED] where they are a syllable too, as on the full keyboard:
 *   `64` on the way to nian, `2`, 啊, on the way to bu, so a lone 2 reads a first);
 * - where another digit follows, the initials the digits type (简拼, `94` zh): with
 *   [abbreviations] where no longer syllable is read there (sh in `745` 时间, not in `744`, shi),
 *   without only where nothing else is read but a syllable of one letter (`25` is 北京 too, not
 *   only 啊 then j …).
 *
 * A letter typed as itself ([Action.Letter][org.fcitx.fcitx5.android.engine.session.Action.Letter],
 * chosen from a key's long press) is in the input upper case, in place of its digit: read as the
 * digit, keeping only the syllables with that letter there (`7H` is on the way to shi or sha, not
 * si or pian; fuzzy partners of another spelling go too).
 *
 * A key pressed again soon after itself (连按) is in the input as [again] its digit, a press of
 * its own: the run of such presses is read both ways, as that many keys (`744` fast is shi, as
 * typed at any speed) and as the letter it came to, as a letter typed as itself (4 4 is h, 4 4 4
 * i, 2 2 2 2 a again), for the decoder to weigh. Only a run whole within a syllable is a letter.
 *
 * A syllable the user chose for the first digits not yet chosen
 * ([Action.Syllable][org.fcitx.fcitx5.android.engine.session.Action.Syllable]) is in the input as
 * its letters and a separator: read as that syllable alone, or as an initial for a letter (`h`).
 */
class T9Segmenter(
    fuzzy: Set<Fuzzy> = emptySet(),
    private val abbreviations: Boolean = true,
    val letters: NineKeyLetters = NineKeyLetters.STANDARD,
) : Segmenter {

    // digits typing a syllable whole, the start of one, and an initial
    private val whole = HashMap<String, SyllableMatches>()
    private val starts = HashMap<String, SyllableMatches>()
    private val initials = HashMap<String, SyllableMatches>()

    // a chosen letter, as an initial: its syllables
    private val byInitial = HashMap<String, SyllableMatches>()

    // digits typing a syllable of an initial and a final, or a final alone, of two letters or more:
    // 啊, 呃, 哦, 呣, 嗯 and 儿 alone would keep 简拼 off every 2, 3, 6 and 7
    private val regular = HashSet<String>()

    // the most presses a syllable takes typed a letter at a time (连按), each letter from the farthest key
    // it is on and gone round that key once more (2 2 2 2 is a again): 31 on a phone's keys (shuang,
    // zhuang), more where the user put a letter fifth on a key. One round for the whole syllable left
    // shuang with a as 2 2 2 2 and n as 6 6 6 6 6 read as keys alone. It binds only while some reading
    // still starts a syllable or a run goes on (walk), so other typing is walked no further
    private val maxPresses = (0 until Syllables.count).map(Syllables::spelling).filter { !it[0].isUpperCase() }.maxOf { s ->
        s.sumOf { c -> letters.keysOf(c).maxOfOrNull { k -> letters.letters(k).let { onKey -> onKey.indexOf(c) + 1 + onKey.length } } ?: 1 }
    }

    init {
        val wholeIds = HashMap<String, TreeMap<Int, Int>>()
        val startIds = HashMap<String, TreeMap<Int, Int>>()
        val initialIds = HashMap<String, TreeMap<Int, Int>>()
        val letterIds = HashMap<String, TreeMap<Int, Int>>()
        val initialRules = fuzzy.filter { it.onInitial }
        for (id in 0 until Syllables.count) {
            val spelling = Syllables.spelling(id)
            if (spelling[0].isUpperCase()) continue
            val parts = SpellingIndex.split(spelling)
            val spellings = if (parts == null) {
                listOf(spelling to 0)
            } else {
                val (init, fin) = parts
                val finalRules = fuzzy.filter { !it.onInitial && it.appliesAfter(init) }
                if (init.isNotEmpty()) keepBest(letterIds.getOrPut(init) { TreeMap() }, id, SyllableMatches.COMPLETION)
                SpellingIndex.variants(init, initialRules).flatMap { (i, iFlags) ->
                    if (i.isNotEmpty()) {
                        for (keys in letters.typings(i)) keepBest(initialIds.getOrPut(keys) { TreeMap() }, id, iFlags or SyllableMatches.COMPLETION)
                    }
                    // with no initial the final is the syllable: ou's partner u is none, as in 双拼
                    SpellingIndex.finalsAfter(i, fin, finalRules).map { (f, fFlags) -> i + f to (iFlags or fFlags) }
                }
            }
            for ((s, flags) in spellings) {
                // üe is typed ue too (略 lue): on a phone u and v share 8, on the user's keys maybe not
                val typings = letters.typings(s) + if ("ve" in s) letters.typings(s.replace("ve", "ue")) else emptyList()
                for (keys in typings.distinct()) {
                    if (parts != null && s.length > 1) regular += keys
                    keepBest(wholeIds.getOrPut(keys) { TreeMap() }, id, flags)
                    for (n in 1 until keys.length) keepBest(startIds.getOrPut(keys.substring(0, n)) { TreeMap() }, id, flags or SyllableMatches.COMPLETION)
                }
            }
        }
        wholeIds.forEach { (k, m) -> whole[k] = toMatches(m) }
        startIds.forEach { (k, m) -> starts[k] = toMatches(m) }
        initialIds.forEach { (k, m) -> initials[k] = toMatches(m) }
        letterIds.forEach { (k, m) -> byInitial[k] = toMatches(m) }
    }

    // the letters of a syllable taken are the session's to put in, not typed
    override fun reads(c: Char) = c in '2'..'9' && letters.letters(c).isNotEmpty()

    override val typesLetters: Boolean get() = false

    override fun segment(input: String): SyllableGraph = GraphEdges.build(input) { at, edges ->
        if (input[at] in 'a'..'z') chosen(input, at, edges) else walk(input, at, edges)
    }

    /** The syllable, or initial, chosen at [at]: its letters up to the separator. */
    private fun chosen(input: String, at: Int, edges: GraphEdges) {
        var end = at
        while (end < input.length && input[end] in 'a'..'z') end++
        val text = input.substring(at, end)
        val id = Syllables.id(text)
        when {
            // a letter is taken as the initial it is offered as: m for ma, mi …, not 呣
            text.length == 1 && text in byInitial -> edges.add(at, 1, Kind.INITIAL, byInitial.getValue(text))
            id >= 0 -> edges.add(at, end - at, Kind.SYLLABLE, SyllableMatches(intArrayOf(id), intArrayOf(0)))
        }
    }

    private fun walk(input: String, at: Int, edges: GraphEdges) {
        var longest = 0
        var length = 0
        var readings = listOf("" to false)
        val initialEdges = ArrayList<Pair<Int, SyllableMatches>>()
        while (at + length < input.length && isKey(input[at + length]) && length < maxPresses) {
            length++
            readings = readOn(readings, input, at, at + length)
            val read = Read()
            for ((typed, tapped) in readings) read.add(typed, tapped, endsAt(input, at + length))
            if (read.regular) longest = length
            if (read.full.isNotEmpty()) edges.add(at, length, Kind.SYLLABLE, merged(read.full))
            if (read.extended.isNotEmpty()) edges.add(at, length, Kind.EXTENDED, merged(read.extended))
            if (read.partial.isNotEmpty()) edges.add(at, length, Kind.PARTIAL, merged(read.partial))
            if (read.initial.isNotEmpty()) initialEdges += length to merged(read.initial)
            // no syllable goes on from any reading: nor from a longer one, but for a run going on
            // past here, which a press more may make a letter
            if (!read.goesOn && !continues(input, at + length)) break
        }
        for ((n, m) in initialEdges) {
            // at the end the start of a syllable is read already, as PARTIAL
            if (endsAt(input, at + n)) continue
            if (longest == 0 || abbreviations && longest <= n) edges.add(at, n, Kind.INITIAL, m)
        }
    }

    /** What the readings of some presses are, whole syllables, the starts of some, initials: each reading [add]ed. */
    private inner class Read {
        val full = ArrayList<SyllableMatches>()
        val extended = ArrayList<SyllableMatches>() // the starts of a reading that is a syllable too
        val partial = ArrayList<SyllableMatches>() // the starts of one that is none
        val initial = ArrayList<SyllableMatches>()
        var regular = false
        var goesOn = false // whether some reading starts a syllable longer

        /** The reading [typed], [tapped] if a run is a letter in it, at the end of the input or of a syllable separated if [atEnd]. */
        fun add(typed: String, tapped: Boolean, atEnd: Boolean) {
            if (typed.length > MAX_SYLLABLE) return
            val keys = keysOf(typed)
            fun read(m: SyllableMatches?) = m?.let { fitting(it, typed) }?.let { if (tapped) it.with(SyllableMatches.TAPPED) else it }
            val syllable = read(whole[keys])
            syllable?.let {
                full += it
                // a run read as its letter keeps no initial off the keys as typed: 4 4 2 6 fast is
                // h'hao and g'hao too, as at any pace
                if (!tapped && isRegular(keys, it)) regular = true
            }
            if (keys in starts) goesOn = true
            // each reading's own: the B of 2 2 fast only starts bu, though 22 is ba
            if (atEnd) read(starts[keys])?.let { if (syllable != null) extended += it else partial += it }
            read(initials[keys])?.let { initial += it }
        }
    }

    // a letter typed as itself as any key typing it: every way to type a syllable is indexed
    private fun keysOf(typed: String) = buildString(typed.length) { typed.forEach { append(if (it in '2'..'9') it else letters.key(it.lowercaseChar())) } }

    /**
     * [readings], the ways the presses of [input] from [at] to [end] - 1 read, each read on by the
     * press at [end] - 1: as letters typed as themselves and digits are, every key pressed again a
     * digit; where the press ends a run of a key pressed again, each also with the run the letter it
     * came to (the runs before it read either way already). A run going on before [at] or past [end]
     * is keys alone.
     */
    private fun readOn(readings: List<Pair<String, Boolean>>, input: String, at: Int, end: Int): List<Pair<String, Boolean>> {
        val digit = digitOf(input[end - 1])
        var runStart = end - 1
        while (runStart > at && continues(input, runStart)) runStart--
        val run = end - runStart
        // a reading longer than a syllable before the run stays so whatever the run reads as: dropped,
        // or a user's keys of five letters, typed letter by letter, would double them each letter
        val typed = readings.map { (r, tapped) -> r + digit to tapped }.filter { it.first.length - run < MAX_SYLLABLE }
        val keyLetters = letters.letters(digit)
        val ended = run > 1 && !(runStart > 0 && continues(input, runStart)) && !continues(input, end)
        if (!ended || keyLetters.isEmpty()) return typed
        val letter = keyLetters[(run - 1) % keyLetters.length].uppercaseChar()
        // the run's presses are the last of each reading, as digits: counted from its end
        return typed.flatMap { (r, tapped) -> listOf(r to tapped, r.substring(0, r.length - run) + letter to true) }
    }

    // [input] at [p] is a key pressed again after the key before it
    private fun continues(input: String, p: Int) = p < input.length && input[p] in AGAIN && digitOf(input[p - 1]) == digitOf(input[p])

    /**
     * How many letters the presses [text] (of an edge) stand for, read as [syllable]: a run of a
     * key pressed again may be one. The presses where no reading fits the syllable.
     */
    fun lettersOf(text: String, syllable: Int): Int {
        val spelling = Syllables.spelling(syllable)
        val readings = (1..text.length).fold(listOf("" to false)) { readings, end -> readOn(readings, text, 0, end) }
        return readings.map { it.first }.firstOrNull { typed ->
            val keys = keysOf(typed)
            spelling.length >= typed.length && listOfNotNull(whole[keys], starts[keys], initials[keys])
                .any { m -> fitting(m, typed)?.let { it.indexOf(syllable) >= 0 } == true }
        }?.length ?: text.length
    }

    // [spelling] has [c] at [k]; üe is spelt lve here and typed lue as well (略, 虐)
    private fun spells(spelling: String, k: Int, c: Char) =
        spelling.getOrNull(k) == c || c == 'u' && spelling.getOrNull(k) == 'v' && spelling.getOrNull(k + 1) == 'e'

    // the end of the input, or of a syllable the user separated
    private fun endsAt(input: String, at: Int) = at == input.length || input[at] == SEPARATOR

    // a regular syllable read for [keys] as [full], what a letter typed as itself left of them
    private fun isRegular(keys: String, full: SyllableMatches?): Boolean = when {
        keys !in regular || full == null -> false
        full === whole[keys] -> true
        // as [regular] has digits: of an initial and a final, or a final alone, of two letters or more
        else -> (0 until full.size).any { Syllables.spelling(full.syllable(it)).let { s -> s.length > 1 && SpellingIndex.split(s) != null } }
    }

    /** Those of [matches] spelt with each letter typed as itself in [typed] where it is; null if none. */
    private fun fitting(matches: SyllableMatches, typed: String): SyllableMatches? {
        if (typed.none { it in FIXED }) return matches
        val kept = (0 until matches.size).filter { i ->
            val spelling = Syllables.spelling(matches.syllable(i))
            typed.indices.all { k -> typed[k] !in FIXED || spells(spelling, k, typed[k].lowercaseChar()) }
        }
        if (kept.isEmpty()) return null
        return SyllableMatches(IntArray(kept.size) { matches.syllable(kept[it]) }, IntArray(kept.size) { matches.flags(kept[it]) })
    }

    // [all] as one: each syllable with its best flags, sorted as SyllableMatches are searched
    private fun merged(all: List<SyllableMatches>): SyllableMatches {
        if (all.size == 1) return all[0]
        val best = TreeMap<Int, Int>()
        for (m in all) for (i in 0 until m.size) keepBest(best, m.syllable(i), m.flags(i))
        return toMatches(best)
    }

    companion object {
        private const val MAX_SYLLABLE = 6

        /** A letter typed as itself, as it stands in the input: see the class. */
        val FIXED = 'A'..'Z'

        // a noncharacter, for a program's own use: the private use area is the keyboard's syllables
        // (Keyboard.SYLLABLES), and these stand in no text but the session's input
        private const val AGAIN_BASE = '\uFDD0'

        /** [digit] pressed again soon after itself (连按), as it stands in the input: see the class. */
        fun again(digit: Char): Char = AGAIN_BASE + (digit - '0')

        /** The keys pressed again, as they stand in the input. */
        val AGAIN = again('2')..again('9')

        /** The digit [c] is, a key pressed again or not; else [c]. */
        fun digitOf(c: Char): Char = if (c in AGAIN) '0' + (c - AGAIN_BASE) else c

        /** [text] with each key pressed again as its digit. */
        fun plain(text: CharSequence): String = buildString(text.length) { text.forEach { append(digitOf(it)) } }

        /** [text], of the input, as it is shown or committed: a key pressed again as its digit, a letter typed as itself lower case. */
        fun asTyped(text: CharSequence): String = plain(text).lowercase()

        /** Whether [c] is a key typed: a digit, pressed again or not, or a letter typed as itself. */
        fun isKey(c: Char): Boolean = c in '2'..'9' || c in FIXED || c in AGAIN
    }
}
