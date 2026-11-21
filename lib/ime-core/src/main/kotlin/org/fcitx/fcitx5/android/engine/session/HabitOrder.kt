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
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * Places a [PinyinSession]'s candidates by the user's habits ([HabitRanker]) at two levels, each ranked under its
 * keys after the word before, within the decoder's places, adding words the habits want that the decoder lacks:
 *
 * - Level 1: readings of all the input; additions after the last sentence.
 * - Level 2: what ends where the decoder's first word ends; additions after the level's last place on the first page.
 *
 * Additions are the code's pin, first, used words, and every own or hand-added word the input reads there (and
 * imported ones after the dictionary's). Multi-word texts are walked along the input's syllables, never made;
 * blocked or unreadable ones are not added.
 */
internal class HabitOrder(
    private val data: PinyinData,
    private val user: UserModel?,
    private val recorder: CommitRecorder,
    private val decoder: PinyinDecoder,
    /** Hand-added words in list order, ranked after formed words. */
    private val added: () -> Collection<String>,
    /** Whether a character read as a syllable may take a typed tone (zhuyin, [PinyinDecoder]'s); null where no tone is typed. */
    private val toneAllows: ((codePoint: Int, syllable: Int, tone: Int) -> Boolean)? = null,
) {
    private val habits get() = recorder.habits

    // hand-added words as of the lists' [UserModel.listings]: a long-press delete takes effect at once, sessions kept
    private var addedAt = -1
    private var addedOrder = emptyMap<String, Int>()
    // sorted for binary search: asked of every start arc each key, a set would box each id
    private var addedIds = IntArray(0)

    private fun addedNow() {
        val at = user?.listings ?: 0
        if (at == addedAt) return
        addedAt = at
        val texts = added()
        addedOrder = texts.withIndex().associate { it.value to it.index }
        // the dictionary's words added by hand; a user-trie word is [UserModel.chosen]
        addedIds = texts.mapNotNull { text -> data.wordIndex.find(text).takeIf { it >= 0 } }.toIntArray().apply { sort() }
    }

    private fun isAddedId(id: Int) = addedIds.isNotEmpty() && addedIds.binarySearch(id) >= 0

    fun isAdded(text: String): Boolean {
        addedNow()
        return text in addedOrder
    }

    /** A level: candidates ending at [end], ranked under [code] after [prev]; [injected] those added; [recorded] texts pinned, first or used under the code itself; [gate] (双拼) a typed word reads the code as whole syllables with its last key begun; [toneOff] recorded texts a typed tone disagrees with. */
    class Level(
        val end: Int, val code: Code, val prev: String, val injected: List<Candidate>, val habitsHere: Boolean, val recorded: Set<String>, val gate: Boolean,
        val toneOff: Set<String>,
    )

    /** [ranks]: each ranked one's tier and uses; [pinned]: the pin in front, if any. */
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

    /** The levels of [graph] from [from], with the decoder's [read] and [first] after [prev]; none where nothing ranks ([prev] null) or the keys were glided. */
    fun levels(graph: SyllableGraph, from: Int, prev: String?, first: Candidate?, read: List<Candidate>): List<Level> {
        if (prev == null || !habits.learns) return emptyList()
        addedNow()
        val rest = graph.end
        val present = read.mapTo(HashSet()) { it.text }
        // additions score below everything read: only their tier places them
        val low = (read.minOfOrNull { it.score } ?: 0f) - 1f
        val levels = ArrayList<Level>(2)
        level(graph, from, rest, prev, present, low)?.let(levels::add) ?: return emptyList()
        val e1 = first?.ends?.firstOrNull()
        if (e1 != null && e1 < rest) level(graph, from, e1, prev, present, low)?.let(levels::add)
        // other first-word ends with habits are ranked in place too, so 汪 picked under wang precedes 往 in
        // wangxiaomingchifan (first word 王小明). The user's own words only at the levels above
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
        // words other spellings of the code on this keyboard raise here
        val spelled = spelledHere(code)
        val habitsHere = pinned != null || firstHere != null || used.isNotEmpty() || spelled.isNotEmpty()
        if (habitsOnly && !habitsHere) return null
        val injected = ArrayList<Candidate>()
        fun add(c: Candidate) {
            if (present.add(c.text)) injected += c
        }
        val slips = holdsSlips(graph, graph.start, end)
        // what the user did under this very code ranks as read there, slipped or not; other spellings' words and own or
        // added words only over no slip where the code reads whole. Keys read alone (简拼) count as whole
        // one record serves an input typed with tones and without: a typed tone the word disagrees with keeps it unraised
        val all = listOfNotNull(pinned, firstHere) + used
        val toneOff = toneOff(graph, end, all)
        val recorded = all.filter { it.text !in toneOff }
        walkIn(graph, end, recorded, false, present, ::add)
        walkIn(graph, end, spelled, slips, present, ::add)
        // own and added words the input reads here, under every code; imported ones in their slot
        val user = user
        if (user != null && !habitsOnly) {
            // (an added name of the user's trie is [UserModel.chosen]); one pass over the start's arcs for both
            fun own(id: Int) = user.chosen(id) || isAddedId(id)
            val starts = decoder.startWords(graph, end, { own(it) || user.startOnly(it) }, slips)
            starts.forEach { if (own(it.words[0])) add(it) }
            // a page of them by import order, not each of the hundreds a short code reads; each order asked once
            starts.filter { !own(it.words[0]) && it.text !in present }.map { it to habits.importOrder(it.text) }.sortedBy { it.second }
                .take(IMPORTS_PER_LEVEL).forEach { add(it.first) }
        }
        val gate = gate(graph, code, end)
        return Level(end, code, prev, injected.map { Candidate(it.text, it.end, low, it.words, it.ends) }, habitsHere, recorded.mapTo(HashSet()) { it.text }, gate, toneOff)
    }

    // texts of [words] the input reads to [end] yet not with the tones typed there
    private fun toneOff(graph: SyllableGraph, end: Int, words: List<Words>): Set<String> {
        if (toneAllows == null || words.isEmpty() || (graph.start until end).none { ZhuyinKeys.toneOf(graph.input[it]) > 0 }) return emptySet()
        return words.filter { walk(graph, end, it, slips = false) != null && walk(graph, end, it, slips = false, toned = true) == null }.mapTo(HashSet()) { it.text }
    }

    // [view] with [off] texts neither pinned, first nor used: ranked as the tone puts them, like any other word
    private fun toned(off: Set<String>) = object : HabitRanker.View by view {
        override fun pinned(code: Code) = view.pinned(code)?.takeUnless { it.text in off }
        override fun first(code: Code, prev: String) = view.first(code, prev)?.takeUnless { it.text in off }
        override fun uses(code: Code, words: Words) = if (words.text in off) 0 else view.uses(code, words)
        override fun corrections(code: Code, words: Words) = if (words.text in off) 0 else view.corrections(code, words)
    }

    // 双拼's gate, by every word of the start the decoder read to [end], once per decode (by candidates alone it opened
    // too late); where the code does not read whole it costs one more pass over the start's arcs
    private fun gate(graph: SyllableGraph, code: Code, end: Int): Boolean {
        val user = user ?: return false
        if (!Kinds.isShuangpin(code.kind) || readsWholeAsTyped(graph, graph.start, end)) return false
        val readings = Readings()
        return decoder.startWords(graph, end, { user.typed(it) }).any { aloneShape(graph, it, readings) == Shape.HALF_TYPED }
    }

    // adds each of [words] not [present] that the input reads to [end], over no slip where [slips]
    private fun walkIn(graph: SyllableGraph, end: Int, words: List<Words>, slips: Boolean, present: Set<String>, add: (Candidate) -> Unit) {
        for (w in words) if (w.text !in present) walk(graph, end, w, slips = slips)?.let(add)
    }

    // words of the code's keyboard and first key whose counts across spellings move them under it; each word's
    // reading split once per record, the code's keys once per level (heavy users have hundreds per key)
    private fun spelledHere(code: Code): List<Words> {
        val first = code.keys.firstOrNull { it != '\'' } ?: return emptyList()
        val words = habits.spelledFrom(code.kind, first)
        if (words.isEmpty() || Kinds.byText(code.kind) || Kinds.isZhuyin(code.kind)) return emptyList()
        val shuangpin = Kinds.isShuangpin(code.kind)
        val keyboard = Kinds.keyboard(code.kind)
        val typed = code.keys.filter { it != '\'' }
        // 双拼's keys are no nine-key letters
        val letters = if (shuangpin) null else Array(typed.length) { SpelledForm.nineKeyLetters(keyboard, typed[it]) }
        val here = ArrayList<Words>()
        for (w in words) {
            val form = SpelledForm.of(shuangpin, typed.length, w.syllables.size, w.letters) ?: continue
            val s = w.spelled
            val moves = (form != SpelledForm.INITIALS && s.initialsCorrections > 0) || (form != SpelledForm.FULL && s.fullUses >= HabitRanker.FULL_USES)
            if (moves && (letters == null || SpelledForm.spells(typed, letters, w.syllables))) here += w.words
        }
        return here
    }

    /** [words] as the model has them along [graph] to [end]; null if one is none, blocked, or unread there. With [slips] the code reads whole where keys also read slipped, so not along a slip (`ta` near y raises no 呀); a key alone (简拼) is no slip. With [toned], only where each character may take the tone typed after its syllable. */
    private fun walk(graph: SyllableGraph, end: Int, words: Words, start: Int = graph.start, slips: Boolean = holdsSlips(graph, start, end), toned: Boolean = false): Candidate? {
        // a pinned sentence is one record word, split into model words
        sentence(words)?.let { return walk(graph, end, it, start, slips, toned) }
        val allows = toneAllows.takeIf { toned }
        val user = user
        val ids = IntArray(words.list.size)
        val syllables = ArrayList<IntArray>(words.list.size)
        for ((i, w) in words.list.withIndex()) {
            val entry = HabitCounts.entry(w)
            val id = when {
                entry != null -> user?.known(entry) ?: data.wordIndex.find(w.text)
                // kept without reading (an older pin): the model's word of its text, any reading
                else -> data.wordIndex.find(w.text)
            }
            if (id < 0 || user?.blockedAnyhow(id) == true) return null
            ids[i] = id
            syllables += entry?.syllables ?: return startWord(graph, start, end, id, words, slips)
        }
        val chars = if (allows == null) null else toneChars(words, syllables)
        val ends = IntArray(ids.size)
        fun go(word: Int, k: Int, at: Int): Boolean {
            if (k == syllables[word].size) {
                ends[word] = at
                return if (word == ids.size - 1) at == end else go(word + 1, 0, at)
            }
            for (e in graph.edges(at)) {
                if (!passes(graph, e, end, slips)) continue
                val m = graph.matches(e)
                if ((0 until m.size).any { m.syllable(it) == syllables[word][k] } && agrees(graph, e, allows, chars?.get(word), syllables[word], k) && go(word, k + 1, graph.to(e))) return true
            }
            return false
        }
        // its end is the graph's place, as for the decoder's candidates and the levels
        return if (go(0, 0, start)) Candidate(words.text, end, 0f, ids, ends) else null
    }

    // each word's characters, where one per syllable: else no telling which a tone is of, as for the decoder
    private fun toneChars(words: Words, syllables: List<IntArray>) =
        Array(words.list.size) { i -> words.list[i].text.codePoints().toArray().takeIf { it.size == syllables[i].size } }

    // whether character [k] of a word ([chars], [syllables]) may take the tone typed after [e]
    private fun agrees(graph: SyllableGraph, e: Int, allows: ((Int, Int, Int) -> Boolean)?, chars: IntArray?, syllables: IntArray, k: Int): Boolean {
        if (allows == null || chars == null) return true
        val tone = graph.tone(e)
        return tone == 0 || allows(chars[k], syllables[k], tone)
    }

    // whether a word may continue over [e]: no raw input, nothing past [end], no slip where [slips]
    private fun passes(graph: SyllableGraph, e: Int, end: Int, slips: Boolean) =
        graph.kind(e) != Kind.RAW && graph.to(e) <= end && !(slips && graph.isSlip(e))

    /** A multi-syllable word the model lacks as the fewest model words; null if not such or none. */
    private fun sentence(words: Words): Words? {
        val w = words.list.singleOrNull() ?: return null
        return if (' ' in w.reading && !known(w)) split(w) else null
    }

    /** A text the model lacks as one word, as its fewest words, a character per syllable; null if none. */
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

    // whether [start]..[end] reads as whole syllables where keys also read slipped: a legal slip then raises no
    // word habits have under another code. Keys alone are the user's 简拼, raised as over whole syllables
    private fun holdsSlips(graph: SyllableGraph, start: Int, end: Int) = graph.hasSlips && readsWholeAsTyped(graph, start, end)

    // whether [start]..[end] reads whole as typed, no lone key or slip: its first reading is likely meant, so
    // lone-key typed words go after it
    private fun readsWholeAsTyped(graph: SyllableGraph, start: Int, end: Int) = graph.readsWhole(start, end)

    // a one-piece word without reading: the decoder's arc, if it reads all of it
    private fun startWord(graph: SyllableGraph, start: Int, end: Int, id: Int, words: Words, slips: Boolean): Candidate? =
        if (words.list.size == 1) decoder.arcsBetween(graph, start, end, slips).firstOrNull { it.words[0] == id } else null

    // kept codes of a kind by keys, as [Habits.kept] lists them (same list till the record changes)
    private var keptList: List<Habits.Kept>? = null
    private var keptByKeys = emptyMap<String, List<Habits.Kept>>()
    private var keptLongest = 0

    /** [sentences] with the user's kept words put in by [Enforced], as many as there were: see [enforced]. */
    fun enforce(graph: SyllableGraph, from: Int, prev: String?, sentences: List<Candidate>): List<Candidate> {
        val items = enforced(graph, from, prev, sentences)
        if (items.isEmpty()) return sentences
        // as many as there were, from the decoder's more, so ones made equal leave none fewer
        val more = decoder.moreSentences(graph).ifEmpty { sentences }
        return Enforced.apply(more, items, prev.orEmpty(), ::text, graph.start).take(sentences.size)
    }

    private fun text(word: Int) = wordText(data, user, word)

    /** What the user's sentences keep over [graph] from [from] after [prev] ([Enforced]): pins and multi-character firsts whose keys the input has, and hand-added words that are the decoder's only arc between two word boundaries of [sentences]. */
    fun enforced(graph: SyllableGraph, from: Int, prev: String?, sentences: List<Candidate>): List<Enforced.Item> {
        if (prev == null || !habits.learns || sentences.isEmpty()) return emptyList()
        // a pin or first over the whole input is the sentence: ranked first already (HabitRanker), and put in every
        // sentence it would leave no other. A sole added word over its keys is first as the sentence too
        return kept(graph, from).filter { it.from != graph.start || it.to != graph.end } + added(graph, from, sentences)
    }

    // pins and firsts of several characters whose keys the input has, read along its syllables
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
        // by input places, not keys: a separator is a keyless place, a bar-taken syllable several digit places
        for (a in graph.start until graph.end) itemsFrom(graph, from, a, items)
        return items
    }

    // kept codes from place [a]: each end while its keys are no longer than the longest kept
    private fun itemsFrom(graph: SyllableGraph, from: Int, a: Int, items: MutableList<Enforced.Item>) {
        // a code starts at a key, not at a separator before it
        if (recorder.pressed.keys(from + a, from + a + 1).isEmpty()) return
        for (b in a + 1..graph.end) {
            // glided keys are no code's keys: none read so, nor any longer
            if (recorder.pressed.glided(from + a, from + b)) return
            // keys looked up first, a code made only of kept ones: unglided, its keys are these
            val keys = recorder.pressed.keys(from + a, from + b)
            if (keys.length > keptLongest) return
            val under = keptByKeys[keys] ?: continue
            val code = recorder.code(from + a, from + b, emptyList())
            // the same keys end before a separator and past it: only past it is a node, as the walk asks
            under.filter { it.code == code }.forEach { k ->
                // a pin or first the typed tones disagree with is kept out, as at its level
                walk(graph, b, k.words, a, toned = true)?.let { c ->
                    items += Enforced.Item(a, b, c.words, c.ends, c.text, k.prev, if (k.prev == null) Enforced.Kind.PIN else Enforced.Kind.FIRST)
                }
            }
        }
    }

    // hand-added words each the decoder's only arc between two of the sentences' word boundaries
    private fun added(graph: SyllableGraph, from: Int, sentences: List<Candidate>): List<Enforced.Item> {
        addedNow()
        val user = user
        if (user == null || addedOrder.isEmpty()) return emptyList()
        // where the shown sentences' words end: kept ones are of them unless items made some equal, and more sentences
        // would cost every key
        val bounds = sortedSetOf(graph.start).apply { sentences.forEach { s -> s.ends.forEach { add(it) } } }
        val items = ArrayList<Enforced.Item>()
        for (a in bounds) for (b in bounds.tailSet(a + 1)) {
            // glided keys are no word's keys; only where keys read whole, not a typed word's arc over lone keys
            val arcs = if (recorder.pressed.glided(from + a, from + b)) emptyList() else decoder.arcsBetween(graph, a, b, holdsSlips(graph, a, b), readsWholeAsTyped(graph, a, b))
            val only = arcs.singleOrNull()?.takeIf { user.addedByHand(it.words[0]) || isAddedId(it.words[0]) } ?: continue
            items += Enforced.Item(a, b, only.words, only.ends, only.text, null, Enforced.Kind.ADDED)
        }
        return items
    }

    /** [read] (the decoder's [sentences] first) with each level ranked in place and additions placed as the class says; what the decoder reads only on a later page of an added text is left out. */
    fun order(graph: SyllableGraph, read: List<Candidate>, sentences: List<Candidate>, levels: List<Level>, pageSize: Int): Ordered {
        if (levels.isEmpty()) return Ordered(read, emptyMap())
        val putIn = levels.flatMap { it.injected }.mapTo(HashSet()) { it.text }
        val list = read.filterTo(ArrayList()) { it.text !in putIn }
        val ranks = HashMap<Candidate, HabitRanker.Ranked<Candidate>>()
        // the candidates mostly share words at the same places: each read once per order, not per candidate (a graph walk each)
        val readings = Readings()
        for ((k, level) in levels.withIndex()) {
            val at = if (k == 0) list.indexOfLast { it in sentences } + 1 else place(list, level.end, pageSize)
            list.addAll(at, level.injected)
            // a code read whole where keys also read slipped ranks no slip-only word, except what was pinned, first or used under it
            val whole = if (holdsSlips(graph, graph.start, level.end)) graph.withoutSlips() else null
            // 双拼's typed words by their lone keys, each candidate's shape asked once
            val alone = if (!Kinds.isShuangpin(level.code.kind)) null else BooleanArray(list.size) { list[it].end == level.end && aloneShape(graph, list[it], readings) == Shape.ALONE }
            // a lone-key typed word takes its slot however the code reads (it reads over no slip)
            val slots = list.indices.filter {
                list[it].end == level.end && (ranksHere(graph, list[it], level, whole, readings) || alone?.get(it) == true)
            }
            val injected = level.injected.toHashSet()
            // exact reading asked only where an imported word needs its slot
            val imports = slots.any { imported(list[it]) }
            val items = slots.map { item(graph, list[it], list[it] in injected, level.habitsHere, imports, readings, alone?.get(it) == true) }
            val ranked = HabitRanker.rank(items, level.code, level.prev, if (level.toneOff.isEmpty()) view else toned(level.toneOff), readsWholeAsTyped(graph, graph.start, level.end), level.gate)
            for ((i, r) in ranked.withIndex()) {
                list[slots[i]] = r.value
                ranks[r.value] = r
            }
        }
        return Ordered(list, ranks)
    }

    // level 2 additions: after its last place on the first page, else at its first, else last
    private fun place(list: List<Candidate>, end: Int, pageSize: Int): Int {
        val slots = list.indices.filter { list[it].end == end }
        return slots.lastOrNull { it < pageSize }?.plus(1) ?: slots.firstOrNull() ?: list.size
    }

    private fun item(graph: SyllableGraph, c: Candidate, injected: Boolean, habitsHere: Boolean, imports: Boolean, readings: Readings, alone: Boolean): HabitRanker.Item<Candidate> {
        val letters = NO_WORD in c.words
        // no habit under the code: readings matter to none, own words go by text
        val words = if (letters || !habitsHere) Words.of(c.text) else words(graph, c, readings) ?: Words.of(c.text)
        val single = c.text.codePointCount(0, c.text.length) == 1 && Han.all(c.text)
        val imported = imported(c)
        return HabitRanker.Item(
            c, words, single = single, letters = letters || words.list.any { it.reading.isLatin() }, imported = imported, injected = injected,
            exact = imports && !imported && !letters && exact(graph, c), alone = alone,
        )
    }

    /** A typed word's shape over the code: [ALONE] ([HabitRanker.Item.alone]); [HALF_TYPED] opens the gate. */
    private enum class Shape { ALONE, HALF_TYPED, NONE }

    /**
     * [c]'s [Shape]: one typed word from the start read exactly as whole syllables then lone keys, two or more
     * ([Shape.ALONE]) or the last only ([Shape.HALF_TYPED]); else [Shape.NONE]. Judged by edge kinds on the first
     * reading, which tries whole syllables first; a vowel key taken as a lead counts as a lone key (小鹤 `el` 恶劣).
     */
    private fun aloneShape(graph: SyllableGraph, c: Candidate, readings: Readings): Shape {
        val word = c.words.singleOrNull() ?: return Shape.NONE
        if (word == NO_WORD || user?.typed(word) != true) return Shape.NONE
        val r = readings.reading(graph.start, c.ends[0], word) { spelt(graph, graph.start, c.ends[0], word) }
        if (r == null || !r.exact || r.slips.isNotEmpty()) return Shape.NONE
        var alone = 0
        for (e in r.edges) {
            if (graph.kind(e) == Kind.SYLLABLE && !graph.isAbbreviation(e)) {
                // a whole syllable after a lone key: no such shape
                if (alone > 0) return Shape.NONE
            } else {
                alone++
            }
        }
        return when {
            alone >= 2 -> Shape.ALONE
            alone == 1 -> Shape.HALF_TYPED
            else -> Shape.NONE
        }
    }

    // whether [c] takes a slot of [level]: anything where no slip is held back ([whole] null), else what reads along [whole] or was [Level.recorded]
    private fun ranksHere(graph: SyllableGraph, c: Candidate, level: Level, whole: SyllableGraph?, readings: Readings) =
        whole == null || c.text in level.recorded || readsAlong(graph, whole, c, readings)

    /**
     * Whether each word of [c] reads along [whole] ([graph] without slips). A word reached along [whole] over keys
     * nothing reads slipped ([SyllableGraph.readsSlipWithin]) reads there as in [graph], so is not read at all: reading
     * every word each key was most of the nine keys' slip cost. Words [graph] reads over no slip read along; ones it
     * does not read do not; only the rest are walked. Internal for HabitOrderReadsAlongTest.
     */
    internal fun readsAlong(graph: SyllableGraph, whole: SyllableGraph, c: Candidate, readings: Readings): Boolean {
        var from = whole.start
        // whether [from] was reached along [whole]
        var sure = true
        for (i in c.words.indices) {
            val word = c.words[i]
            val to = c.ends[i]
            if (word == NO_WORD) {
                sure = false
            } else if (!sure || graph.readsSlipWithin(from, to)) {
                // read only where it may not read along [whole]: past as-typed text or over a slip; a lone key is no slip
                // (grouped keyboards have one nearly everywhere)
                val r = readings.reading(from, to, word) { spelt(graph, from, to, word) } ?: return false
                val walked = r.slips.isNotEmpty() || !sure
                if (walked && !readings.along(from, to, word) { reading(whole, from, to, word, data.dictionary, ownOf(word)) { _, _ -> "" } != null }) return false
                sure = true
            }
            from = to
        }
        return true
    }

    // a word only imported: the decoder has it from the start alone, a one-word candidate
    private fun imported(c: Candidate) = c.words.size == 1 && user?.startOnly(c.words[0]) == true

    // whether each word reads its keys as typed, not fuzzy nor slipped: where an imported word goes after
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

    // a Latin run read as letters: never a pinyin syllable
    private fun String.isLatin() = isNotEmpty() && split(' ').all { s -> Syllables.id(s).let { it >= 0 && LatinWords.isLetter(it) } }

    /** As the record files them, each read along [graph] once in [readings]; null if one is not. */
    private fun words(graph: SyllableGraph, c: Candidate, readings: Readings): Words? {
        var from = graph.start
        val out = ArrayList<Word>(c.words.size)
        for (i in c.words.indices) {
            val word = c.words[i]
            if (word == NO_WORD) return null
            val r = readings.reading(from, c.ends[i], word) { spelt(graph, from, c.ends[i], word) } ?: return null
            out += Word(ownOf(word)?.text(word) ?: data.vocabulary.word(word), Syllables.spelled(r.syllables))
            from = c.ends[i]
        }
        return Words(out)
    }

    // [word] read along [graph] from [from] to [to], each syllable spelt
    private fun spelt(graph: SyllableGraph, from: Int, to: Int, word: Int) =
        reading(graph, from, to, word, data.dictionary, ownOf(word)) { _, s -> Syllables.spelling(s) }

    // the user's trie for the user's words
    private fun ownOf(word: Int) = user?.takeIf { word >= data.vocabulary.size }

    // what [readsAlong] and [words] found per word and place: [reading] on the graph, [along] on its whole part
    internal class Readings {
        private val along = HashMap<Long, Boolean>()
        private val readings = HashMap<Long, Reading?>()

        /** For the test that bounds the words [readsAlong] reads. */
        internal val read: Int get() = readings.size

        fun along(from: Int, to: Int, word: Int, read: () -> Boolean): Boolean = along.getOrPut(key(from, to, word), read)

        fun reading(from: Int, to: Int, word: Int, read: () -> Reading?): Reading? {
            val k = key(from, to, word)
            return if (readings.containsKey(k)) readings[k] else read().also { readings[k] = it }
        }

        // both ends under 2^16 (inputs are far shorter), and the word
        private fun key(from: Int, to: Int, word: Int): Long {
            require(from in 0 until PLACES && to in 0 until PLACES) { "a place past $PLACES keys" }
            return from.toLong() shl 48 or (to.toLong() shl 32) or (word.toLong() and 0xffffffffL)
        }

        private companion object {
            const val PLACES = 1 shl 16
        }
    }

    companion object {
        // hand-added words go after formed ones, in list order
        private const val HAND = Long.MAX_VALUE / 2

        // the longest word a pinned sentence is split into
        private const val MAX_PIECE = 8
        // imported words added per level: a page
        private const val IMPORTS_PER_LEVEL = 10
    }
}
