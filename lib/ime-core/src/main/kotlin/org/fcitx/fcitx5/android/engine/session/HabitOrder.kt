/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Han
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.SpelledForm
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * Where a [PinyinSession] puts its candidates by the user's habits ([HabitRanker], impl/design-1b.md
 * §1, §2): two levels of the input after the pieces, each ranked under its own keys after the word
 * before it, within the places the decoder gave its candidates, the words the decoder lacks that
 * the habits want there put in ([Level.injected]).
 *
 * - Level 1: what reads all the input, under all its keys; those put in go after the last sentence.
 * - Level 2: what ends where the decoder's own first word ends, under the keys up to there; those
 *   put in go after the level's last place on the first page: the first page of the decoder's list,
 *   before the phrases, the emoji and the coarse words held back move it, so near it only.
 *
 * What is put in: the code's pin, its first after the word before, the words used under it, and
 * every word of the user's own or added by hand the input reads there (U-08's (c): theirs under
 * every code), and those only imported (U-101: after the words of the dictionary's spelt so). A word of one piece is the decoder's arc from the start; a text of several (点了 of
 * 点 and 了, picked so under dl) is walked along the input's syllables, each word as the model has
 * it, never made. A word blocked, or that the input does not read, is not.
 */
internal class HabitOrder(
    private val data: PinyinData,
    private val user: UserModel?,
    private val recorder: CommitRecorder,
    private val decoder: PinyinDecoder,
    /** The words added by hand, in the list's order: theirs after the words they formed. Read as the user's lists change. */
    private val added: () -> Collection<String>,
) {
    private val habits get() = recorder.habits

    // the words added by hand as of the user's lists' [UserModel.listings]: one deleted with a long
    // press goes at once, the sessions kept (review-1b S2)
    private var addedAt = -1
    private var addedOrder = emptyMap<String, Int>()
    private var addedIds = emptySet<Int>()

    private fun addedNow() {
        val at = user?.listings ?: 0
        if (at == addedAt) return
        addedAt = at
        val texts = added()
        addedOrder = texts.withIndex().associate { it.value to it.index }
        // the dictionary's words added by hand; a word of the user's own trie is [UserModel.chosen]
        addedIds = texts.mapNotNullTo(HashSet()) { text -> data.wordIndex.find(text).takeIf { it >= 0 } }
    }

    /** Whether [text] is a word added by hand. */
    fun isAdded(text: String): Boolean {
        addedNow()
        return text in addedOrder
    }

    /** A level of the input: the candidates ending at [end] (from the pieces' end), ranked under [code] after [prev]; [injected], those put in. */
    class Level(val end: Int, val code: Code, val prev: String, val injected: List<Candidate>, val habitsHere: Boolean)

    /** [list] as shown, how those ranked were ([ranks]: the tier, the uses); [pinned], the pin in front, if one is. */
    class Ordered(val list: List<Candidate>, val ranks: Map<Candidate, HabitRanker.Ranked<Candidate>>) {
        val pinned: Candidate? get() = list.firstOrNull()?.takeIf { tier(it) == Tier.PINNED }

        fun tier(c: Candidate): Tier? = ranks[c]?.tier
    }

    private val view = object : HabitRanker.View {
        override fun pinned(code: Code) = habits.pinned(code)
        override fun first(code: Code, prev: String) = habits.first(code, prev)
        override fun uses(code: Code, words: Words) = habits.uses(code, words)
        override fun corrections(code: Code, words: Words) = habits.corrections(code, words)
        override fun firstUse(code: Code, words: Words) = habits.firstUse(code, words)
        override fun mineOrder(text: String) = habits.mineOrder(text).takeIf { it >= 0 } ?: addedOrder[text]?.let { HAND + it } ?: -1L
        override fun importOrder(text: String) = habits.importOrder(text)
        override fun spelled(kind: String, words: Words) = habits.spelled(kind, words)
    }

    /**
     * The levels of [graph], the input from [from] on, read with [read] (the decoder's sentences and
     * words) and the decoder's own [first] reading, after [prev]: none where nothing ranks (learning
     * off, a piece not learned: [prev] null) or the keys were glided, whose code is each word's own.
     */
    fun levels(graph: SyllableGraph, from: Int, prev: String?, first: Candidate?, read: List<Candidate>): List<Level> {
        if (prev == null || !habits.learns) return emptyList()
        addedNow()
        val rest = graph.end
        val present = read.mapTo(HashSet()) { it.text }
        // what is put in scores below all that was read: only its tier places it
        val low = (read.minOfOrNull { it.score } ?: 0f) - 1f
        val levels = ArrayList<Level>(2)
        level(graph, from, rest, prev, present, low)?.let(levels::add) ?: return emptyList()
        val e1 = first?.ends?.firstOrNull()
        if (e1 != null && e1 < rest) level(graph, from, e1, prev, present, low)?.let(levels::add)
        // the other ends of the words of the start where the habits have something: ranked in their
        // own places too, so 汪 picked under wang goes before 往 in wangxiaomingchifan, whose first
        // word is 王小明 (design-1c §1, names:36). The user's own words only at the levels above
        val ends = read.mapTo(sortedSetOf()) { it.end }.filter { it != rest && it != e1 }
        for (end in ends) level(graph, from, end, prev, present, low, habitsOnly = true)?.let(levels::add)
        return levels
    }

    private fun level(graph: SyllableGraph, from: Int, end: Int, prev: String, present: MutableSet<String>, low: Float, habitsOnly: Boolean = false): Level? {
        if (recorder.pressed.glided(from, from + end)) return null
        val code = recorder.code(from, from + end, emptyList())
        val pinned = habits.pinned(code)
        val firstHere = habits.first(code, prev)
        val used = habits.used(code)
        // the words this keyboard's other spellings of the code raise here (U-20, D32 B), read so
        val spelled = spelledHere(code)
        val habitsHere = pinned != null || firstHere != null || used.isNotEmpty() || spelled.isNotEmpty()
        if (habitsOnly && !habitsHere) return null
        val injected = ArrayList<Candidate>()
        fun add(c: Candidate) {
            if (present.add(c.text)) injected += c
        }
        for (w in listOfNotNull(pinned, firstHere) + used + spelled) if (w.text !in present) walk(graph, end, w)?.let(::add)
        // the user's own words and those added by hand the input reads here: theirs under every code; those imported, in their slot
        val user = user
        if (user != null && !habitsOnly) {
            // (a name added by hand, of the user's own trie, is [UserModel.chosen]; review-1b M1). One
            // pass over the start's arcs for both, each key (review-int-runtime N4)
            val own = { id: Int -> user.chosen(id) || id in addedIds }
            val starts = decoder.startWords(graph, end) { own(it) || user.startOnly(it) }
            starts.forEach { if (own(it.words[0])) add(it) }
            // a page of them by their order (D24), not each of the hundreds a short code reads (review-G S9): the rest as the decoder has them.
            // Each one's order asked once, not at each comparison (N4)
            starts.filter { !own(it.words[0]) && it.text !in present }.map { it to habits.importOrder(it.text) }.sortedBy { it.second }
                .take(IMPORTS_PER_LEVEL).forEach { add(it.first) }
        }
        return Level(end, code, prev, injected.map { Candidate(it.text, it.end, low, it.words, it.ends) }, habitsHere)
    }

    // those of the code's keyboard and first key whose counts across its spellings move them under it
    private fun spelledHere(code: Code): List<Words> {
        val first = code.keys.firstOrNull { it != '\'' } ?: return emptyList()
        return habits.spelledFrom(code.kind, first).filter { w ->
            val form = SpelledForm.of(code.kind, code.keys, w.reading) ?: return@filter false
            if (!SpelledForm.spells(Kinds.keyboard(code.kind), code.keys, w.reading)) return@filter false
            val s = habits.spelled(code.kind, w)
            (form != SpelledForm.INITIALS && s.initialsCorrections > 0) || (form != SpelledForm.FULL && s.fullUses >= HabitRanker.FULL_USES)
        }
    }

    /**
     * The words of [words], each as the model has it (never made one), along the syllables of
     * [graph] from its start to [end]; null if one is none, blocked, or the input does not read them so.
     */
    private fun walk(graph: SyllableGraph, end: Int, words: Words, start: Int = graph.start): Candidate? {
        // a sentence pinned is one word of the record's, the model's words apart (review-1b S4)
        sentence(words)?.let { return walk(graph, end, it, start) }
        val user = user
        val ids = IntArray(words.list.size)
        val syllables = ArrayList<IntArray>(words.list.size)
        for ((i, w) in words.list.withIndex()) {
            val entry = HabitCounts.entry(w)
            val id = when {
                entry != null -> user?.known(entry) ?: data.wordIndex.find(w.text)
                // kept with no reading (a pin of an older build's): the model's word of its text, at any reading
                else -> data.wordIndex.find(w.text)
            }
            if (id < 0 || user?.blockedAnyhow(id) == true) return null
            ids[i] = id
            syllables += entry?.syllables ?: return startWord(graph, start, end, id, words)
        }
        val ends = IntArray(ids.size)
        fun go(word: Int, k: Int, at: Int): Boolean {
            if (k == syllables[word].size) {
                ends[word] = at
                return if (word == ids.size - 1) at == end else go(word + 1, 0, at)
            }
            for (e in graph.edges(at)) {
                if (graph.kind(e) == Kind.RAW || graph.to(e) > end) continue
                val m = graph.matches(e)
                if ((0 until m.size).any { m.syllable(it) == syllables[word][k] } && go(word, k + 1, graph.to(e))) return true
            }
            return false
        }
        // its end the graph's place, as the decoder's candidates' and the levels' (F2814)
        return if (go(0, 0, start)) Candidate(words.text, end, 0f, ids, ends) else null
    }

    /** [words], one word of several syllables the model has not, as the fewest of the model's words; null if it is no such or they are none. */
    private fun sentence(words: Words): Words? {
        val w = words.list.singleOrNull() ?: return null
        return if (' ' in w.reading && !known(w)) split(w) else null
    }

    /** [w], a text the model has not as one word, as the fewest of its words, a character a syllable; null if none are. */
    private fun split(w: Word): Words? {
        val syllables = w.reading.split(' ')
        val n = w.text.length
        if (n != syllables.size || w.text.codePointCount(0, n) != n) return null
        // fewest[j]: the fewest words of the first j characters
        val fewest = arrayOfNulls<List<Word>>(n + 1)
        fewest[0] = emptyList()
        for (i in 0 until n) {
            val before = fewest[i] ?: continue
            for (j in i + 1..minOf(n, i + MAX_PIECE)) {
                val piece = Word(w.text.substring(i, j), syllables.subList(i, j).joinToString(" "))
                if (!known(piece)) continue
                if (fewest[j].let { it == null || it.size > before.size + 1 }) fewest[j] = before + piece
            }
        }
        return fewest[n]?.let(::Words)
    }

    private fun known(w: Word): Boolean {
        val entry = HabitCounts.entry(w) ?: return false
        return (user?.known(entry) ?: data.wordIndex.find(w.text)) >= 0
    }

    // a word of one piece with no reading: the decoder's arc, if it reads all of it
    private fun startWord(graph: SyllableGraph, start: Int, end: Int, id: Int, words: Words): Candidate? =
        if (words.list.size == 1) decoder.arcsBetween(graph, start, end).firstOrNull { it.words[0] == id } else null

    // the codes kept of a kind by their keys, as [Habits.kept] lists them (the same list till the record changes)
    private var keptList: List<Habits.Kept>? = null
    private var keptByKeys = emptyMap<String, List<Habits.Kept>>()
    private var keptLongest = 0

    /**
     * [sentences] with what the user's sentences keep put in (U-08, design-1c §1) by [Enforced],
     * as many as there were: see [enforced].
     */
    fun enforce(graph: SyllableGraph, from: Int, prev: String?, sentences: List<Candidate>): List<Candidate> {
        val items = enforced(graph, from, prev, sentences)
        if (items.isEmpty()) return sentences
        // as many as there were, of the more the decoder has: those made the same leave no fewer (review-design-1c S4)
        val more = decoder.moreSentences(graph).ifEmpty { sentences }
        return Enforced.apply(more, items, prev.orEmpty(), ::text, graph.start).take(sentences.size)
    }

    private fun text(word: Int) = wordText(data, user, word)

    /**
     * What the user's sentences keep over [graph], read from input place [from] after [prev]
     * ([Enforced], impl/design-1c.md §1): the pins and the user's firsts of several characters
     * whose keys the input has, read along its syllables there; the words added by hand that are
     * the only word of the decoder's arcs over a stretch the [sentences] have a word boundary at both ends of.
     */
    fun enforced(graph: SyllableGraph, from: Int, prev: String?, sentences: List<Candidate>): List<Enforced.Item> {
        if (prev == null || !habits.learns || sentences.isEmpty()) return emptyList()
        // a pin or a first over all the input is the whole sentence: ranked first already (HabitRanker), and
        // put in every sentence it would leave no other (PinyinSessionT9Test: 米糕 first, 你好 still a
        // sentence). A word added by hand alone over its keys is first there too as the sentence (U-65)
        return kept(graph, from).filter { it.from != graph.start || it.to != graph.end } + added(graph, from, sentences)
    }

    // the pins and the firsts of several characters whose keys the input has, its syllables reading them there
    private fun kept(graph: SyllableGraph, from: Int): List<Enforced.Item> {
        val rest = recorder.code(from, from + graph.end, emptyList())
        val kept = habits.kept(rest.kind)
        if (kept !== keptList) {
            keptList = kept
            keptByKeys = kept.groupBy { it.code.keys }
            keptLongest = kept.maxOfOrNull { it.code.keys.length } ?: 0
        }
        if (keptByKeys.isEmpty()) return emptyList()
        val items = ArrayList<Enforced.Item>()
        // by the input's places, not its keys: a separator is a place of no key, a syllable taken off
        // the nine keys' bar several places of its digits (F2672)
        for (a in graph.start until graph.end) itemsFrom(graph, from, a, items)
        return items
    }

    // the kept codes over the input from place [a]: each place to the end, while its keys are no longer than the longest kept
    private fun itemsFrom(graph: SyllableGraph, from: Int, a: Int, items: MutableList<Enforced.Item>) {
        // a code starts at a key, not at a separator before it
        if (recorder.pressed.keys(from + a, from + a + 1).isEmpty()) return
        for (b in a + 1..graph.end) {
            // keys glided are no keys of a code: none read so, nor any longer
            if (recorder.pressed.glided(from + a, from + b)) return
            // the keys looked up first, a code made only of those kept (review F3164): unglided, its keys are these
            val keys = recorder.pressed.keys(from + a, from + b)
            if (keys.length > keptLongest) return
            val under = keptByKeys[keys] ?: continue
            val code = recorder.code(from + a, from + b, emptyList())
            // the same keys end before a separator and past it: only past it is a node, as the walk asks
            under.filter { it.code == code }.forEach { k ->
                walk(graph, b, k.words, a)?.let { c ->
                    items += Enforced.Item(a, b, c.words, c.ends, c.text, k.prev, if (k.prev == null) Enforced.Kind.PIN else Enforced.Kind.FIRST)
                }
            }
        }
    }

    // the words added by hand, each the only word of the decoder's arcs between two of the sentences' word boundaries
    private fun added(graph: SyllableGraph, from: Int, sentences: List<Candidate>): List<Enforced.Item> {
        addedNow()
        val user = user
        if (user == null || addedOrder.isEmpty()) return emptyList()
        // the places the decoder's sentences shown have words end at: those kept are of them unless
        // putting items in made some the same, and more of the decoder's on every key cost (review-1c N4)
        val bounds = sortedSetOf(graph.start).apply { sentences.forEach { s -> s.ends.forEach { add(it) } } }
        val items = ArrayList<Enforced.Item>()
        for (a in bounds) for (b in bounds.tailSet(a + 1)) {
            // keys glided are no keys of a word (1b N8)
            val arcs = if (recorder.pressed.glided(from + a, from + b)) emptyList() else decoder.arcsBetween(graph, a, b)
            val only = arcs.singleOrNull()?.takeIf { user.addedByHand(it.words[0]) || it.words[0] in addedIds } ?: continue
            items += Enforced.Item(a, b, only.words, only.ends, only.text, null, Enforced.Kind.ADDED)
        }
        return items
    }

    /**
     * [read] (the decoder's [sentences] first) with each level ranked in its places, those put in
     * placed as the class says: what the decoder reads only on a later page of a text put in is left
     * out, as that one stays where it is (S2).
     */
    fun order(graph: SyllableGraph, read: List<Candidate>, sentences: List<Candidate>, levels: List<Level>, pageSize: Int): Ordered {
        if (levels.isEmpty()) return Ordered(read, emptyMap())
        val putIn = levels.flatMap { it.injected }.mapTo(HashSet()) { it.text }
        val list = read.filterTo(ArrayList()) { it.text !in putIn }
        val ranks = HashMap<Candidate, HabitRanker.Ranked<Candidate>>()
        for ((k, level) in levels.withIndex()) {
            val at = if (k == 0) list.indexOfLast { it in sentences } + 1 else place(list, level.end, pageSize)
            list.addAll(at, level.injected)
            val slots = list.indices.filter { list[it].end == level.end }
            val injected = level.injected.toHashSet()
            // whether a word reads the keys exactly is asked only where an imported word needs its slot
            val imports = slots.any { imported(list[it]) }
            val items = slots.map { item(graph, list[it], list[it] in injected, level.habitsHere, imports) }
            val ranked = HabitRanker.rank(items, level.code, level.prev, view)
            for ((i, r) in ranked.withIndex()) {
                list[slots[i]] = r.value
                ranks[r.value] = r
            }
        }
        return Ordered(list, ranks)
    }

    // where level 2's put in go: after its last place on the first page, else at its first, else last
    private fun place(list: List<Candidate>, end: Int, pageSize: Int): Int {
        val slots = list.indices.filter { list[it].end == end }
        return slots.lastOrNull { it < pageSize }?.plus(1) ?: slots.firstOrNull() ?: list.size
    }

    private fun item(graph: SyllableGraph, c: Candidate, injected: Boolean, habitsHere: Boolean, imports: Boolean): HabitRanker.Item<Candidate> {
        val letters = NO_WORD in c.words
        // no habit under the code: its words' readings matter to none, the user's own words go by their text
        val words = if (letters || !habitsHere) Words.of(c.text) else words(graph, c) ?: Words.of(c.text)
        val single = c.text.codePointCount(0, c.text.length) == 1 && Han.all(c.text)
        val imported = imported(c)
        return HabitRanker.Item(
            c, words, single = single, letters = letters || words.list.any { it.reading.isLatin() }, imported = imported, injected = injected,
            exact = imports && !imported && !letters && exact(graph, c),
        )
    }

    // a word only imported (U-101): the decoder has it from the start alone, a candidate of one word
    private fun imported(c: Candidate) = c.words.size == 1 && user?.startOnly(c.words[0]) == true

    // whether each word of [c] reads its keys as typed, none fuzzy nor a slip: where an imported word goes after (PLAN §2.3)
    private fun exact(graph: SyllableGraph, c: Candidate): Boolean {
        var from = graph.start
        for (i in c.words.indices) {
            val word = c.words[i]
            val own = user?.takeIf { word >= data.vocabulary.size }
            if (reading(graph, from, c.ends[i], word, data.dictionary, own) { _, _ -> "" }?.exact != true) return false
            from = c.ends[i]
        }
        return true
    }

    // a Latin run read as letters (U-27): spelt as letters, never a syllable of pinyin
    private fun String.isLatin() = isNotEmpty() && split(' ').all { s -> Syllables.id(s).let { it >= 0 && LatinWords.isLetter(it) } }

    /** [c]'s words as the learning record files them: each read along [graph]; null if one is not. */
    fun words(graph: SyllableGraph, c: Candidate): Words? {
        var from = graph.start
        val out = ArrayList<Word>(c.words.size)
        for (i in c.words.indices) {
            val word = c.words[i]
            if (word == NO_WORD) return null
            val own = user?.takeIf { word >= data.vocabulary.size }
            val r = reading(graph, from, c.ends[i], word, data.dictionary, own) { _, s -> Syllables.spelling(s) } ?: return null
            out += Word(if (own != null) own.text(word) else data.vocabulary.word(word), r.syllables.joinToString(" ") { Syllables.spelling(it) })
            from = c.ends[i]
        }
        return Words(out)
    }

    companion object {
        // the words added by hand go after those the user formed, in the list's order
        private const val HAND = Long.MAX_VALUE / 2

        // the longest word a sentence pinned is split into
        private const val MAX_PIECE = 8
        // the words imported put in at a level: a page
        private const val IMPORTS_PER_LEVEL = 10
    }
}
