/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.keepBest
import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.toMatches
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
 * A key pressed again soon after itself (连按, where it is on) is that key's next letter and
 * nothing else: the session puts it in as a letter typed as itself, in place of the digit (4 4
 * fast is `H`, 4 4 4 `I`). Read both ways, as keys too, a learned 广告 overrode the h the key's
 * preview showed (2026-10-19).
 *
 * A syllable the user chose for the first digits not yet chosen
 * ([Action.Syllable][org.fcitx.fcitx5.android.engine.session.Action.Syllable]) is in the input as
 * its letters and a separator: read as that syllable alone, or as an initial for a letter (`h`).
 *
 * On a grouped keyboard ([NineKeyLetters.isGrouped]) each key of two letters may come with where it
 * was tapped ([segment] with taps): each edge then says, for each syllable, how far the letters it
 * reads its keys as go against those places ([SyllableGraph.against]). The letters are those of
 * the spelling actually typed for it (a fuzzy partner's, üe's ue), kept beside the index as the
 * group's first or second letter of each key.
 *
 * The keys may spell syllables in zhuyin's symbols instead ([KeySpelling], 注音九键 and 大千 of
 * [ZhuyinKeys]): every syllable indexed by its symbols' keys, a symbol typed as itself its code, a
 * tone after a syllable a separator (the decoder weighs it: [SyllableGraph.tone]).
 */
class T9Segmenter(
    fuzzy: Set<Fuzzy> = emptySet(),
    private val abbreviations: Boolean = true,
    override val letters: NineKeyLetters = NineKeyLetters.STANDARD,
    override val toneAllows: ((codePoint: Int, syllable: Int, tone: Int) -> Boolean)? = null,
) : KeyedSegmenter {

    // the tables below made once for the settings that shape them, shared by the sessions made anew
    // after another setting changed (SettingsMemo, S15 N2)
    private val tables = TABLES[fuzzy.toSet() to letters]
    private val spelling = KeySpelling.of(letters)
    private val whole = tables.whole
    private val starts = tables.starts
    private val initials = tables.initials
    private val byInitial = tables.byInitial
    private val regular = tables.regular
    private val wholeLetters = tables.wholeLetters
    private val startLetters = tables.startLetters
    private val initialLetters = tables.initialLetters

    /**
     * How many times two spellings of a syllable kept for the same keys and flags had different
     * letters: none, or which one [SyllableGraph.against] judges would depend on the order read.
     */
    internal val letterTies: Int get() = tables.letterTies

    /** What a segmenter reads [letters] by under [fuzzy]: made at once, never changed after. */
    private class Tables(fuzzy: Set<Fuzzy>, private val letters: NineKeyLetters) {
        private val keySpelling = KeySpelling.of(letters)

        // digits typing a syllable whole, the start of one, and an initial
        val whole = HashMap<String, SyllableMatches>()
        val starts = HashMap<String, SyllableMatches>()
        val initials = HashMap<String, SyllableMatches>()

        // a chosen letter, as an initial: its syllables
        val byInitial = HashMap<String, SyllableMatches>()

        // digits typing a syllable of an initial and a final, or a final alone, of two letters or more:
        // 啊, 呃, 哦, 呣, 嗯 and 儿 alone would keep 简拼 off every 2, 3, 6 and 7
        val regular = HashSet<String>()

        // on a grouped keyboard, beside each of whole, starts and initials: which of its key's letters
        // the spelling kept for each syllable has at each key, two bits a key, the first key lowest
        val wholeLetters = HashMap<String, IntArray>()
        val startLetters = HashMap<String, IntArray>()
        val initialLetters = HashMap<String, IntArray>()

        var letterTies = 0
            private set

        init {
            val wholeIds = HashMap<String, TreeMap<Int, Int>>()
            val startIds = HashMap<String, TreeMap<Int, Int>>()
            val initialIds = HashMap<String, TreeMap<Int, Int>>()
            val letterIds = HashMap<String, TreeMap<Int, Int>>()
            val grouped = letters.isGrouped
            val wholeChoices = HashMap<String, TreeMap<Int, Int>>()
            val startChoices = HashMap<String, TreeMap<Int, Int>>()
            val initialChoices = HashMap<String, TreeMap<Int, Int>>()
            // keepBest, and on a grouped keyboard the letters of the spelling it kept
            fun keep(ids: HashMap<String, TreeMap<Int, Int>>, choices: HashMap<String, TreeMap<Int, Int>>, keys: String, id: Int, flags: Int, typed: String) {
                val into = ids.getOrPut(keys) { TreeMap() }
                val old = into[id]
                keepBest(into, id, flags)
                if (!grouped) return
                val choice = choiceOf(keys, typed)
                val kept = choices.getOrPut(keys) { TreeMap() }
                if (old == null || flags < old) kept[id] = choice else if (flags == old && kept[id] != choice) letterTies++
            }
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
                            val spelt = keySpelling.initial(i)
                            if (spelt != null) for (keys in letters.typings(spelt)) keep(initialIds, initialChoices, keys, id, iFlags or SyllableMatches.COMPLETION, spelt)
                        }
                        // with no initial the final is the syllable: ou's partner u is none, as in 双拼
                        SpellingIndex.finalsAfter(i, fin, finalRules).map { (f, fFlags) -> i + f to (iFlags or fFlags) }
                    }
                }
                for ((s, flags) in spellings) {
                    // üe is typed ue too (略 lue): on a phone u and v share 8, on the user's keys maybe not
                    val typed = if (keySpelling != KeySpelling.Pinyin) listOfNotNull(keySpelling.of(s)) else if ("ve" in s) listOf(s, s.replace("ve", "ue")) else listOf(s)
                    val seen = HashSet<String>()
                    for (t in typed) {
                        for (keys in letters.typings(t)) {
                            if (!seen.add(keys)) continue
                            if (parts != null && t.length > 1) regular += keys
                            keep(wholeIds, wholeChoices, keys, id, flags, t)
                            for (n in 1 until keys.length) keep(startIds, startChoices, keys.substring(0, n), id, flags or SyllableMatches.COMPLETION, t)
                        }
                    }
                }
            }
            wholeIds.forEach { (k, m) -> whole[k] = toMatches(m) }
            startIds.forEach { (k, m) -> starts[k] = toMatches(m) }
            initialIds.forEach { (k, m) -> initials[k] = toMatches(m) }
            letterIds.forEach { (k, m) -> byInitial[k] = toMatches(m) }
            // in the matches' order: by syllable
            wholeChoices.forEach { (k, m) -> wholeLetters[k] = m.values.toIntArray() }
            startChoices.forEach { (k, m) -> startLetters[k] = m.values.toIntArray() }
            initialChoices.forEach { (k, m) -> initialLetters[k] = m.values.toIntArray() }
        }

        // which of its key's letters each of [typed] is, [keys] the keys typing it: LETTER_BITS a key
        private fun choiceOf(keys: String, typed: String): Int {
            var choice = 0
            for (k in keys.indices) choice = choice or (letters.letters(keys[k]).indexOf(typed[k]).coerceIn(0, LETTER_MASK) shl (LETTER_BITS * k))
            return choice
        }
    }

    // the letters of a syllable taken are the session's to put in, not typed
    // zhuyin's symbols typed as themselves and tones are keys sent as codes ([ZhuyinKeys]), read as the groups are
    override fun reads(c: Char) = letters.isCode(c) && letters.letters(c).isNotEmpty() || spelling.readsOwn(c)

    override fun segment(input: String): SyllableGraph = segment(input, null)

    override fun segment(input: String, taps: FloatArray?): SyllableGraph {
        val placed = taps?.takeIf { letters.isGrouped && it.any { at -> !at.isNaN() } }
        return GraphEdges.build(input) { at, edges ->
            if (input[at] in 'a'..'z') chosen(input, at, edges) else walk(input, at, edges, placed)
        }
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

    private fun walk(input: String, at: Int, edges: GraphEdges, taps: FloatArray?) {
        var longest = 0
        var length = 0
        val initialEdges = ArrayList<Triple<Int, SyllableMatches, FloatArray?>>()
        while (at + length < input.length && isKey(input[at + length]) && length < MAX_SYLLABLE) {
            length++
            val typed = input.substring(at, at + length)
            val keys = keysOf(typed)
            if (read(input, at, typed, keys, edges, taps)) longest = length
            initials[keys]?.let { fitting(it, typed) }?.let { initialEdges += Triple(length, it, taps?.let { t -> against(it, initials[keys], initialLetters[keys], typed, at, t) }) }
            // no syllable goes on from here: nor from a longer one
            if (keys !in starts) break
        }
        for ((n, m, against) in initialEdges) {
            // at the end the start of a syllable is read already, as PARTIAL
            if (endsAt(input, at + n)) continue
            if (longest == 0 || abbreviations && longest <= n) edges.add(at, n, Kind.INITIAL, m, against)
        }
    }

    // the syllables [typed], the presses from [at] as [keys], are, whole or started; whether a regular one is whole
    private fun read(input: String, at: Int, typed: String, keys: String, edges: GraphEdges, taps: FloatArray?): Boolean {
        val full = whole[keys]?.let { fitting(it, typed) }
        // no taps (the nine keys, any key typed with no place): no lookups for them, in the hottest loop of a key
        full?.let { edges.add(at, typed.length, Kind.SYLLABLE, it, taps?.let { t -> against(it, whole[keys], wholeLetters[keys], typed, at, t) }) }
        if (endsAt(input, at + typed.length)) {
            starts[keys]?.let { fitting(it, typed) }?.let {
                edges.add(at, typed.length, if (full != null) Kind.EXTENDED else Kind.PARTIAL, it, taps?.let { t -> against(it, starts[keys], startLetters[keys], typed, at, t) })
            }
        }
        return isRegular(keys, full)
    }

    /**
     * For each of [m] (of [all], fitted to [typed]), how far its letters go against where [typed]'s
     * keys from input place [at] were tapped ([SyllableGraph.against]), [choices] its letters
     * beside [all]; null where no key of two letters has a place, so the graph is as untapped.
     */
    @Suppress("ReturnCount")
    private fun against(m: SyllableMatches, all: SyllableMatches?, choices: IntArray?, typed: String, at: Int, taps: FloatArray): FloatArray? {
        if (all == null || choices == null) return null
        var any = false
        val out = FloatArray(m.size) { i ->
            val choice = choices[all.indexOf(m.syllable(i))]
            var sum = 0f
            for (k in typed.indices) {
                val tap = taps.getOrElse(at + k) { Float.NaN }
                // a letter typed as itself, a key of one letter: nothing to weigh
                if (tap.isNaN() || !letters.isCode(typed[k]) || letters.letters(typed[k]).length < 2) continue
                sum += letters.against(typed[k], (choice shr (LETTER_BITS * k)) and LETTER_MASK, tap)
            }
            if (sum > 0f) any = true
            sum
        }
        return out.takeIf { any }
    }

    // a letter typed as itself as any key typing it: every way to type a syllable is indexed
    private fun keysOf(typed: String) = buildString(typed.length) {
        typed.forEach { append(if (letters.isCode(it)) it else spelling.letterOf(it).let { c -> letters.keysOf(c).firstOrNull() ?: c }) }
    }

    // [spelling] has [c] at [k]; üe is spelt lve here and typed lue as well (略, 虐)
    private fun spells(spelling: String, k: Int, c: Char) =
        spelling.getOrNull(k) == c || c == 'u' && spelling.getOrNull(k) == 'v' && spelling.getOrNull(k + 1) == 'e'

    // the end of the input, or of a syllable the user separated (or gave a tone)
    private fun endsAt(input: String, at: Int) = at == input.length || spelling.separates(input[at])

    // a regular syllable read for [keys] as [full], what a letter typed as itself left of them
    private fun isRegular(keys: String, full: SyllableMatches?): Boolean = when {
        keys !in regular || full == null -> false
        full === whole[keys] -> true
        // as [regular] has digits: of an initial and a final, or a final alone, of two letters or more
        else -> (0 until full.size).any { Syllables.spelling(full.syllable(it)).let { s -> s.length > 1 && SpellingIndex.split(s) != null } }
    }

    /** Those of [matches] spelt with each letter typed as itself in [typed] where it is; null if none. */
    private fun fitting(matches: SyllableMatches, typed: String): SyllableMatches? {
        if (typed.none { spelling.isFixed(it) }) return matches
        val kept = (0 until matches.size).filter { i ->
            val spelt = spelling.of(matches.syllable(i))
            typed.indices.all { k -> !spelling.isFixed(typed[k]) || spells(spelt, k, spelling.letterOf(typed[k])) }
        }
        if (kept.isEmpty()) return null
        return SyllableMatches(IntArray(kept.size) { matches.syllable(kept[it]) }, IntArray(kept.size) { matches.flags(kept[it]) })
    }

    /**
     * [text], of the input, as it is shown or committed: a letter typed as itself lower case, a
     * grouped key's code its first letter ([NineKeyLetters.asTyped]). In zhuyin a symbol or tone as
     * itself, and a syllable taken off the bar (its pinyin in the input) in symbols: ㄇㄧ, not mi.
     */
    override fun asTyped(text: CharSequence): String {
        if (spelling == KeySpelling.Pinyin) return letters.asTyped(text.toString())
        val out = StringBuilder()
        var at = 0
        while (at < text.length) {
            val c = text[at]
            if (c in 'a'..'z') {
                var end = at
                while (end < text.length && text[end] in 'a'..'z') end++
                out.append(shownOffered(text.substring(at, end)))
                at = end
            } else {
                out.append(spelling.shown(c) ?: letters.asTyped(c.toString()))
                at++
            }
        }
        return out.toString()
    }

    /** Whether [c] is a key typed: one of the keys' codes (the nine keys' digits) or a letter typed as itself. */
    override fun isKey(c: Char): Boolean = letters.isCode(c) || spelling.isFixed(c)

    override fun initialsOf(code: Char): List<String> = spelling.initialsOf(letters, code)

    // digits say nothing: of a syllable still being typed as many letters as were typed
    override fun started(syllable: Int, keys: Int): String = spelling.of(syllable).take(keys)

    override fun spelt(syllable: Int): String = spelling.of(syllable)

    // an initial first, where the spelling shows one so
    override fun shownOffered(offered: String): String =
        spelling.offeredInitial(offered) ?: Syllables.id(offered).takeIf { it >= 0 }?.let(spelling::of) ?: offered

    override fun keyOf(offered: String): Char? = spelling.keyOf(offered)

    override fun separates(c: Char): Boolean = spelling.separates(c)

    override fun phraseWays(c: Char): List<String>? = spelling.phraseWays(letters, c)

    override val spaceTone: Char? = if (letters == ZhuyinKeys.DACHEN) ZhuyinKeys.toneCode(1) else null

    companion object {
        private const val MAX_SYLLABLE = 6

        private val TABLES = SettingsMemo<Pair<Set<Fuzzy>, NineKeyLetters>, Tables>(SpellingIndex.MEMO) { (fuzzy, letters) -> Tables(fuzzy, letters) }

        /** How many tables are held: the settings' last few. */
        internal val held: Int get() = TABLES.size

        /** The letters that are an initial alone. */
        private const val INITIAL_LETTERS = "bcdfghjklmnpqrstwxyz"

        // a key's letter, the group's first or second, in a choice of [choiceOf]
        // a key's letter of up to NineKeyLetters.MAX (5): three bits, six keys in an Int
        private const val LETTER_BITS = 3
        private const val LETTER_MASK = 7

        /** A letter typed as itself, as it stands in the input: see the class. */
        val FIXED = 'A'..'Z'

        /** [asTyped] on the nine keys' digits ([NineKeyLetters.STANDARD]'s codes), where the codes are digits whatever the layout. */
        fun asTyped(text: CharSequence): String = text.toString().lowercase()

        /** [initialsOf] on [letters]: the key's letters that are an initial alone; a letter typed as itself offers itself alone. */
        fun initialsOf(letters: NineKeyLetters, code: Char): List<String> =
            (if (code in FIXED) code.lowercase() else letters.letters(code)).filter { it in INITIAL_LETTERS }.map { it.toString() }

        /** [isKey] on the nine keys, whatever their layout: a digit or a letter typed as itself. */
        fun isKey(c: Char): Boolean = c in '2'..'9' || c in FIXED
    }
}
