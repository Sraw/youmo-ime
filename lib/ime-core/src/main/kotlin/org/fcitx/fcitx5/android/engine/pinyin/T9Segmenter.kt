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
 * Cuts pinyin typed on nine keys into a [SyllableGraph]: each key stands for its [letters], so `64426` is
 * ni'hao, mi'gao and more, all left for the decoder; `'` separates (the session maps the keyboard's 1).
 * Per digit: every syllable typed there (with [Fuzzy] partners); at the end or before a separator, every
 * syllable it may become; before another digit, initials (简拼) where no longer syllable is read (with
 * [abbreviations]).
 *
 * A letter typed as itself (upper case in the input, from a long press or 连按) keeps only syllables with
 * that letter there. A bar-chosen syllable is in the input as its letters and a separator. On grouped
 * keyboards each two-letter key may carry a tap place, weighed per syllable ([SyllableGraph.against]). With
 * [neighbours], whole syllables with one key slipped to a neighbour are read too ([SlipCost]); in zhuyin
 * never onto a tone key. Keys may spell zhuyin instead ([KeySpelling]), a tone after a syllable acting as
 * a separator.
 */
class T9Segmenter(
    fuzzy: Set<Fuzzy> = emptySet(),
    private val abbreviations: Boolean = true,
    override val letters: NineKeyLetters = NineKeyLetters.STANDARD,
    override val toneAllows: ((codePoint: Int, syllable: Int, tone: Int) -> Boolean)? = null,
    neighbours: Boolean = false,
    // slip floors per keyboard: nine-key digits -3 (lower cost too much time on a phone); the rest the loosest
    // floor that turns no on-target sentence wrong (σ 0.15): 注音九键 -2, 大千 -1.75, 14 keys -6, 18 keys -4;
    // custom groups take the 18 keys' -4, unmeasured
    private val slipCost: SlipCost = when {
        letters == ZhuyinKeys.NINE -> SlipCost(floor = -2f)
        letters == ZhuyinKeys.DACHEN -> SlipCost(floor = -1.75f)
        letters == NineKeyLetters.G14 -> SlipCost(floor = -6f)
        letters.isGrouped -> SlipCost()
        else -> SlipCost(floor = -3f)
    },
) : KeyedSegmenter {

    // tables built once per shaping settings, shared across sessions (SettingsMemo)
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
    private val slips = neighbours

    /** Ties of two spellings with the same keys and flags but different letters: should be 0, else [SyllableGraph.against] depends on read order. */
    internal val letterTies: Int get() = tables.letterTies

    /** What a segmenter reads [letters] by under [fuzzy]: built once, immutable. */
    private class Tables(fuzzy: Set<Fuzzy>, private val letters: NineKeyLetters) {
        private val keySpelling = KeySpelling.of(letters)

        // keys typing a syllable whole, its start, and an initial
        val whole = HashMap<String, SyllableMatches>()
        val starts = HashMap<String, SyllableMatches>()
        val initials = HashMap<String, SyllableMatches>()

        // a chosen letter, as an initial: its syllables
        val byInitial = HashMap<String, SyllableMatches>()

        // keys typing a syllable of two+ letters: 啊, 呃, 哦, 呣, 嗯, 儿 alone would keep 简拼 off every 2, 3, 6, 7
        val regular = HashSet<String>()

        // on grouped keyboards, beside whole/starts/initials: each key's letter in the kept spelling, LETTER_BITS a key
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
            // keepBest, plus on grouped keyboards the kept spelling's letters
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
                    // üe is typed ue too (略 lue): u and v may be on different keys
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
            // in match order: by syllable
            wholeChoices.forEach { (k, m) -> wholeLetters[k] = m.values.toIntArray() }
            startChoices.forEach { (k, m) -> startLetters[k] = m.values.toIntArray() }
            initialChoices.forEach { (k, m) -> initialLetters[k] = m.values.toIntArray() }
        }

        // which letter of its key each of [typed] is: LETTER_BITS a key
        private fun choiceOf(keys: String, typed: String): Int {
            var choice = 0
            for (k in keys.indices) choice = choice or (letters.letters(keys[k]).indexOf(typed[k]).coerceIn(0, LETTER_MASK) shl (LETTER_BITS * k))
            return choice
        }
    }

    // bar-taken letters are the session's to put in; zhuyin symbols and tones are keys sent as codes
    override fun reads(c: Char) = letters.isCode(c) && letters.letters(c).isNotEmpty() || spelling.readsOwn(c)

    override fun segment(input: String): SyllableGraph = segment(input, null)

    override fun segment(input: String, taps: FloatArray?): SyllableGraph = segment(input, taps, null)

    override fun segment(input: String, taps: FloatArray?, touches: Array<Touch?>?): SyllableGraph {
        val placed = taps?.takeIf { letters.isGrouped && it.any { at -> !at.isNaN() } }
        val touched = touches?.takeIf { slips }?.let { passing(input, it) }
        return GraphEdges.build(input) { at, edges ->
            if (input[at] in 'a'..'z') {
                chosen(input, at, edges)
            } else {
                walk(input, at, edges, placed)
                if (touched != null) slipped(input, at, edges, placed, touched)
            }
        }
    }

    /** Adds, per key run from [at], whole syllables spelt with one key slipped to a neighbour, those spelt as typed left out, each at its cheapest; not for letters typed as themselves. */
    private fun slipped(input: String, at: Int, edges: GraphEdges, taps: FloatArray?, passing: Array<List<Passing>?>) {
        var length = 0
        var any = false
        while (at + length < input.length && isKey(input[at + length]) && length < MAX_SYLLABLE) {
            if (passing[at + length] != null) any = true
            length++
            if (any) slippedRun(input, at, length, edges, taps, passing)
        }
    }

    /** Each key's slip candidates above [SlipCost.floor]; null when none anywhere, so on-target typing skips [slippedRun] entirely. */
    private fun passing(input: String, touches: Array<Touch?>): Array<List<Passing>?>? {
        var out: Array<List<Passing>?>? = null
        for (i in input.indices) {
            val touch = touches.getOrNull(i)?.takeIf { letters.isCode(input[i]) } ?: continue
            var here: ArrayList<Passing>? = null
            for (near in touch.near) {
                val cost = if (readsAs(input[i], near.char)) slipCost.of(touch, near, legal = false) else Float.NaN
                if (!cost.isNaN()) here = (here ?: ArrayList()).apply { add(Passing(touch, near, cost)) }
            }
            if (here != null) (out ?: arrayOfNulls<List<Passing>>(input.length).also { out = it })[i] = here
        }
        return out
    }

    // whether key [typed] may be read as neighbour [d]: another key with letters
    private fun readsAs(typed: Char, d: Char) = d != typed && letters.isCode(d) && letters.letters(d).isNotEmpty()

    // a slip candidate: the tap, the key, its illegal cost
    private class Passing(val touch: Touch, val near: Near, val cost: Float)

    private fun slippedRun(input: String, at: Int, length: Int, edges: GraphEdges, taps: FloatArray?, passing: Array<List<Passing>?>) {
        val typed = input.substring(at, at + length)
        val keys = keysOf(typed)
        val full = whole[keys]?.let { fitting(it, typed) }
        // made at the first slip read
        var best: TreeMap<Int, Slip>? = null
        for (j in 0 until length) passing[at + j]?.forEach { best = slipOnto(typed, keys, j, it, full, best) }
        val found = best
        if (found == null || found.isEmpty()) return
        val builder = Slips.Builder()
        found.values.forEach { builder.add(it.cost, it.at, it.meant) }
        val m = SyllableMatches(found.keys.toIntArray(), found.values.map { it.flags }.toIntArray())
        edges.add(at, length, Kind.SYLLABLE, m, taps?.let { slipAgainst(m, found.values.toList(), typed, at, it) }, builder.build())
    }

    // [best] plus the syllables [typed] spells with key [j] read as [p]'s, those [full] lacks
    private fun slipOnto(typed: String, keys: String, j: Int, p: Passing, full: SyllableMatches?, best: TreeMap<Int, Slip>?): TreeMap<Int, Slip>? {
        val d = p.near.char
        val cost = if (full != null) slipCost.legal(p.cost) else p.cost
        if (cost.isNaN()) return best
        val m = whole[replaced(keys, j, d)]?.let { fitting(it, replaced(typed, j, d)) } ?: return best
        val out = best ?: TreeMap()
        for (i in 0 until m.size) {
            val syllable = m.syllable(i)
            if (full != null && full.indexOf(syllable) >= 0) continue
            val old = out[syllable]
            if (old == null || cost > old.cost) out[syllable] = Slip(cost, j, d, m.flags(i) or SyllableMatches.NEIGHBOUR, p.touch, p.near)
        }
        return out
    }

    private class Slip(val cost: Float, val at: Int, val meant: Char, val flags: Int, val touch: Touch, val near: Near)

    /** [against] for [m]'s slips: the key slipped to weighed by where the tap lies on it ([placeOn]), in the keyboard's own place dimensions; a tap without place weighs none. */
    private fun slipAgainst(m: SyllableMatches, found: List<Slip>, typed: String, at: Int, taps: FloatArray): FloatArray? {
        var any = false
        val out = FloatArray(m.size) { i ->
            val slip = found[i]
            val keys = replaced(keysOf(typed), slip.at, slip.meant)
            val all = whole[keys]
            val choices = wholeLetters[keys]
            if (all == null || choices == null) return@FloatArray 0f
            val choice = choices[all.indexOf(m.syllable(i))]
            var sum = 0f
            for (k in typed.indices) {
                val tap = taps.getOrElse(at + k) { Float.NaN }
                val code = if (k == slip.at) slip.meant else typed[k]
                // a tap without place (NaN) is never weighed, as in against
                if (tap.isNaN() || !letters.isCode(code) || !letters.weighs(code)) continue
                val place = if (k == slip.at) placeOn(slip.touch, slip.near, tap) else tap
                sum += letters.against(code, (choice shr (LETTER_BITS * k)) and LETTER_MASK, place)
            }
            if (sum > 0f) any = true
            sum
        }
        return out.takeIf { any }
    }

    /** The bar-chosen syllable or initial at [at]: its letters up to the separator. */
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
            // at the end a syllable's start is read already, as PARTIAL
            if (endsAt(input, at + n)) continue
            if (longest == 0 || abbreviations && longest <= n) edges.add(at, n, Kind.INITIAL, m, against)
        }
    }

    // adds what [typed] (keys [keys] from [at]) is, whole or started; returns whether a regular one is whole
    private fun read(input: String, at: Int, typed: String, keys: String, edges: GraphEdges, taps: FloatArray?): Boolean {
        val full = whole[keys]?.let { fitting(it, typed) }
        // no taps: skip the lookups in the key's hottest loop
        full?.let { edges.add(at, typed.length, Kind.SYLLABLE, it, taps?.let { t -> against(it, whole[keys], wholeLetters[keys], typed, at, t) }) }
        if (endsAt(input, at + typed.length)) {
            starts[keys]?.let { fitting(it, typed) }?.let {
                edges.add(at, typed.length, if (full != null) Kind.EXTENDED else Kind.PARTIAL, it, taps?.let { t -> against(it, starts[keys], startLetters[keys], typed, at, t) })
            }
        }
        return isRegular(keys, full)
    }

    /** Per match of [m], how far its letters go against [typed]'s tap places from [at]; null when no two-letter key has a place. */
    @Suppress("ReturnCount")
    private fun against(m: SyllableMatches, all: SyllableMatches?, choices: IntArray?, typed: String, at: Int, taps: FloatArray): FloatArray? {
        if (all == null || choices == null) return null
        var any = false
        val out = FloatArray(m.size) { i ->
            val choice = choices[all.indexOf(m.syllable(i))]
            var sum = 0f
            for (k in typed.indices) {
                val tap = taps.getOrElse(at + k) { Float.NaN }
                // a letter typed as itself, or a one-letter key: nothing to weigh
                if (tap.isNaN() || !letters.isCode(typed[k]) || !letters.weighs(typed[k])) continue
                sum += letters.against(typed[k], (choice shr (LETTER_BITS * k)) and LETTER_MASK, tap)
            }
            if (sum > 0f) any = true
            sum
        }
        return out.takeIf { any }
    }

    private fun replaced(s: String, k: Int, c: Char) = buildString(s.length) {
        append(s, 0, k)
        append(c)
        append(s, k + 1, s.length)
    }

    // a letter typed as itself as any key typing it: every way to type a syllable is indexed
    private fun keysOf(typed: String) = buildString(typed.length) {
        typed.forEach { append(if (letters.isCode(it)) it else spelling.letterOf(it).let { c -> letters.keysOf(c).firstOrNull() ?: c }) }
    }

    // üe is spelt lve here and typed lue as well (略, 虐)
    private fun spells(spelling: String, k: Int, c: Char) =
        spelling.getOrNull(k) == c || c == 'u' && spelling.getOrNull(k) == 'v' && spelling.getOrNull(k + 1) == 'e'

    // the input's end, or a syllable's end the user marked (separator or tone)
    private fun endsAt(input: String, at: Int) = at == input.length || spelling.separates(input[at])

    private fun isRegular(keys: String, full: SyllableMatches?): Boolean = when {
        keys !in regular || full == null -> false
        full === whole[keys] -> true
        // as [regular]: initial plus final, or a final alone, of two+ letters
        else -> (0 until full.size).any { Syllables.spelling(full.syllable(it)).let { s -> s.length > 1 && SpellingIndex.split(s) != null } }
    }

    /** Those of [matches] with each letter typed as itself in place; null if none. */
    private fun fitting(matches: SyllableMatches, typed: String): SyllableMatches? {
        if (typed.none { spelling.isFixed(it) }) return matches
        val kept = (0 until matches.size).filter { i ->
            val spelt = spelling.of(matches.syllable(i))
            typed.indices.all { k -> !spelling.isFixed(typed[k]) || spells(spelt, k, spelling.letterOf(typed[k])) }
        }
        if (kept.isEmpty()) return null
        return SyllableMatches(IntArray(kept.size) { matches.syllable(kept[it]) }, IntArray(kept.size) { matches.flags(kept[it]) })
    }

    /** As shown or committed: fixed letters lower case, grouped codes as first letters; in zhuyin, symbols and tones as themselves and bar syllables in symbols (ㄇㄧ). */
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

    override fun isKey(c: Char): Boolean = letters.isCode(c) || spelling.isFixed(c)

    override fun initialsOf(code: Char): List<String> = spelling.initialsOf(letters, code)

    // digits say nothing: as many letters as were typed
    override fun started(syllable: Int, keys: Int): String = spelling.of(syllable).take(keys)

    override fun spelt(syllable: Int): String = spelling.of(syllable)

    // an initial first, where the spelling shows one
    override fun shownOffered(offered: String): String =
        spelling.offeredInitial(offered) ?: Syllables.id(offered).takeIf { it >= 0 }?.let(spelling::of) ?: offered

    override fun keyOf(offered: String): Char? = spelling.keyOf(offered)

    override fun separates(c: Char): Boolean = spelling.separates(c)

    override fun phraseWays(c: Char): List<String>? = spelling.phraseWays(letters, c)

    override val spaceTone: Char? = if (letters == ZhuyinKeys.DACHEN) ZhuyinKeys.toneCode(1) else null

    companion object {
        private const val MAX_SYLLABLE = 6

        /** Where [touch] lies on neighbour [near], clamped onto it (its edge nearest the hit key), in [own]'s dimensions; 1-D is along the letters (down for a [Near.stacked] key). */
        internal fun placeOn(touch: Touch, near: Near, own: Float): Float {
            val x = ((touch.dx - near.nx) / near.nw + HALF).coerceIn(0f, 1f)
            val y = ((touch.dy - near.ny) / near.nh + HALF).coerceIn(0f, 1f)
            if (NineKeyLetters.placeY(own).isNaN()) return if (near.stacked) y else x
            return NineKeyLetters.place(x, y)
        }

        private const val HALF = 0.5f

        private val TABLES = SettingsMemo<Pair<Set<Fuzzy>, NineKeyLetters>, Tables>(SpellingIndex.MEMO) { (fuzzy, letters) -> Tables(fuzzy, letters) }

        internal val held: Int get() = TABLES.size

        private const val INITIAL_LETTERS = "bcdfghjklmnpqrstwxyz"

        // a letter's index in its group (up to 5): three bits, ten keys per Int (a syllable has at most six)
        private const val LETTER_BITS = 3
        private const val LETTER_MASK = 7

        /** A letter typed as itself, as it stands in the input. */
        val FIXED = 'A'..'Z'

        /** For nine-key digits whatever the layout. */
        fun asTyped(text: CharSequence): String = text.toString().lowercase()

        /** The key's letters that are initials alone; a fixed letter offers itself alone. */
        fun initialsOf(letters: NineKeyLetters, code: Char): List<String> =
            (if (code in FIXED) code.lowercase() else letters.letters(code)).filter { it in INITIAL_LETTERS }.map { it.toString() }

        fun isKey(c: Char): Boolean = c in '2'..'9' || c in FIXED
    }
}
