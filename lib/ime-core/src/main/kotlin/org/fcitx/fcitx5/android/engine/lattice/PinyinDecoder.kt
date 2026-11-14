/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.FloatList
import org.fcitx.fcitx5.android.engine.data.IntList
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.NgramModel.Trigrams
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.util.function.IntPredicate

/**
 * Reads a [SyllableGraph] as Chinese. Every dictionary word along every path of the graph
 * becomes an arc of a word lattice; a beam search then keeps the [beam] best word sequences
 * ending at each position, scored by [scorer] plus the [penalties] of how the input was read.
 *
 * Sequences are not merged on their last two words, as exact trigram Viterbi would: that finds
 * the same best sentence but leaves the runners-up differing only at the end, while the ones a
 * user needs differ anywhere (在/再 early on). Keeping whole sequences gives a real N-best for the
 * candidate list and for a reranker to rescore. The price: one text reached through different
 * words (你好, 你 + 好) takes several places in the beam, so fewer distinct sentences come out.
 *
 * Each decode builds on the last one: the beams before the first position a changed arc ends at
 * are kept, so a key typed at the end of long input searches only its last syllables. Nearly
 * all the time goes to [scorer], and without this the cost of a key would grow with the length
 * of the input.
 *
 * Reuses its buffers between calls: not thread-safe, and meant to live as long as the engine.
 */
class PinyinDecoder(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val scorer: WordScorer,
    private val penalties: Penalties = Penalties(),
    private val beam: Int = DEFAULT_BEAM,
    /** Words taken per reading past the first word, most probable first; the first gets them all. */
    private val wordsPerReading: Int = DEFAULT_WORDS_PER_READING,
    /** The user's own words, found along with the dictionary's; any change to them (learned, added, blocked, imported) needs a [reset]. */
    private val user: UserWords? = null,
    /** Where lookAhead keeps its longer words, an entry per [dictionary] node, asked for when first needed: one array for decoders of the same dictionary and scores to share. */
    extensions: () -> Array<IntArray?> = { arrayOfNulls<IntArray>(dictionary.nodeCount) },
    /**
     * Whether the words reading all the input go before the rest; else all by score. Not on the
     * nine keys: there the digits read as many words of all of them that mean nothing, and a
     * syllable picked in the column narrows the words as well (ime-eval ksc, 2026-10-10: of the
     * chat samples, 9 fewer of 983 picked within ten pages, against 2 fewer of 1064 in full pinyin).
     */
    private val wholeFirst: Boolean = true,
    /** Words ranked [CoarseWords.PENALTY] lower, the dictionary's: see [CoarseWords]. */
    private val coarse: CoarseWords = CoarseWords.NONE,
    /** How text kept as typed shows: on the nine keys, [T9Segmenter.asTyped][org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter.asTyped]. */
    private val asTyped: (String) -> String = { it },
    /**
     * The scores of a word reading all the input alone, where they differ from [scorer]'s: with the
     * same contexts and trigrams (the user's counts left out, LearningUse.Counts.MULTI). Null: [scorer]'s.
     */
    private val whole: WordScorer? = null,
    /**
     * The scores of a word read over a syllable typed short (an initial, the start of one, a whole
     * one read as the start of a longer: 简拼), where they differ from [scorer]'s: the user's
     * counts left out, with the same contexts and trigrams, so what is typed in full never moves
     * what is typed short (impl/design-1b.md D32, S4). Null: [scorer]'s.
     */
    private val uncounted: WordScorer? = null,
    /**
     * Whether a character (a code point) read as a syllable may have a tone, 1 to 5 (the reading
     * table's, ReadingTable.allows): a word with a character the tone typed after its syllable
     * does not allow costs [Penalties.tone], kept, not dropped (design 051 Q4 A). Null, or no tone
     * typed (any keyboard but zhuyin's), and nothing is asked.
     */
    private val toneAllows: ((codePoint: Int, syllable: Int, tone: Int) -> Boolean)? = null,
) {
    init {
        require(beam >= 1 && wordsPerReading >= 1) { "beam $beam, words per reading $wordsPerReading" }
    }

    private var arcs = Lattice()

    // per dictionary node reached by a syllable still being typed, see extensionsOf; made once
    // (a reference a node, under a megabyte) and kept when the scores change: they only pick
    // which longer word stands in, and the model scores it as it is when a key is typed; shared
    // when given, by decoders that score alike
    private val extensions: Array<IntArray?> by lazy(LazyThreadSafetyMode.NONE) {
        // checked here, once: one of the wrong size would fail deep in lookAhead, at some node past its end
        extensions().also { require(it.size == dictionary.nodeCount) { "extensions for ${it.size} of ${dictionary.nodeCount} nodes" } }
    }

    // what the beams hold now came from these, or from nothing if lastEnd is -1
    private var lastArcs = Lattice()
    private var lastEnd = -1
    // the graph [arcs] were collected from, null till one is done and after a [reset]; and, as one is,
    // the furthest position the collecting from a position read the edges of, and the furthest they went to
    private var builtFrom: SyllableGraph? = null
    private var reachRead = 0
    private var reachTo = 0

    /** How many positions the last decode collected words from anew, not kept from the one before: for tests. */
    internal var collectedAnew = 0
        private set

    /** How many it collected from going on from the last decode's steps ([collectOn]), their earlier arcs kept: for tests. */
    internal var collectedOn = 0
        private set

    /**
     * What the last decode took, counted: the same for the same input typed the same way, whatever
     * the machine, so a test can hold a key to it where a time would not hold still. Each count
     * guards one saving: [steps] the collecting that goes on from the last decode's, [scored] the
     * search, [firstScored] the first words' scores kept; [arcs] is the size of what they work on.
     */
    class Work internal constructor(
        /** The arcs of the lattice, those kept from the decode before among them. */
        val arcs: Int,
        /** The steps collecting took along the tries anew, a syllable of a reading each. */
        val steps: Int,
        /** The times a word was scored after a sequence, in the search. */
        val scored: Int,
        /** The arcs from the start scored anew for the first words, not kept from the decode before. */
        val firstScored: Int,
    )

    /** [Work] of the last decode. */
    val work: Work get() = Work(arcs.size, steps, scored, firstAnew)

    private var steps = 0
    private var scored = 0
    private var firstAnew = 0

    // collecting from a position: its steps to [crossFrom] or past it, kept for the next decode to go
    // on from; and where the words only imported end from, of those collectStart adds
    private val crossings = Crossings()
    private var crossFrom = Int.MAX_VALUE
    private var startFrom = 0

    // the words a pass over the arcs has taken, each once ([startWords], [arcsBetween], [rankedWords])
    private val seen = SeenWords()
    private var lastStart = 0
    private var lastPrev2 = NO_WORD
    private var lastPrev = NO_WORD

    // the scores firstWords gave the arcs from the start, for the next decode to keep those of the
    // arcs it shares; how many hold, 0 until firstWords is done with them
    private var firstScores = FloatArray(0)
    private var firstScored = 0

    // those arcs as firstWords ranked them, of rankedGraph (null until it is done): more of its
    // words are those further on, read with no decode
    private var firstRanked = LongArray(0)
    private var rankedGraph: SyllableGraph? = null

    // per position, up to [beam] word sequences, best first; a sequence is its last word and a
    // link to the slot of the one it goes on from, which stays put once its position is searched
    private var beamSize = IntArray(0)
    private var stateScore = FloatArray(0)
    private var stateWord = IntArray(0)
    private var statePrev = IntArray(0)
    private var stateBack = IntArray(0) // -1 for the empty sequence at the start
    // the path collect is on
    private val path = Path()

    private val contexts = LongArray(beam) // of the beam being extended, from scorer
    private val runs = LongArray(beam) // the scorer's trigrams of each of contexts, by their bits

    /**
     * @param prev2 the word before [prev] in text already committed, or [NO_WORD]
     * @param prev the last word committed, or [NO_WORD]
     * @param sentences how many readings of the whole input to return at most
     * @param words how many words the input may start with to return at most
     */
    fun decode(
        graph: SyllableGraph,
        prev2: Int = NO_WORD,
        prev: Int = NO_WORD,
        sentences: Int = DEFAULT_SENTENCES,
        words: Int = DEFAULT_WORDS,
    ): Decoding {
        if (graph.start == graph.end) {
            reset()
            return Decoding(emptyList(), emptyList())
        }
        // what the last decode left is built on only in the same context: see search and firstWords
        val last = if (graph.start == lastStart && prev2 == lastPrev2 && prev == lastPrev) lastEnd else -1
        val firstKept = if (last >= 0) firstScored else 0
        // until the search is done: one that threw would leave beams and arcs that match nothing
        lastEnd = -1
        firstScored = 0
        rankedGraph = null
        lastArcs = arcs.also { arcs = lastArcs }
        buildLattice(graph)
        search(graph, prev2, prev, last)
        lastEnd = graph.end
        lastStart = graph.start
        lastPrev2 = prev2
        lastPrev = prev
        return Decoding(sentences(graph, sentences), firstWords(graph, prev2, prev, words, firstKept, last))
    }

    /**
     * Forgets the last decode, so the next searches and collects from scratch: needed when [scorer]'s
     * scores change, and when [user]'s words do (one owned, added, blocked or imported). The words
     * collected from key to key carry the user's words, their blocks and the imported ones as they
     * were: a reset missed shows a word removed or blocked, or leaves out one just added, till the
     * input starts again.
     */
    fun reset() {
        lastEnd = -1
        builtFrom = null
    }

    /**
     * The first [words] words [graph] may start with, as [decode]'s are, where it gave fewer: read
     * on from those it ranked, if the last decode was of [graph] in this context, else decoded again.
     */
    fun words(graph: SyllableGraph, prev2: Int = NO_WORD, prev: Int = NO_WORD, words: Int): List<Candidate> {
        val ranked = graph === rankedGraph && lastEnd == graph.end && prev2 == lastPrev2 && prev == lastPrev
        return if (ranked) rankedWords(graph, words) else decode(graph, prev2, prev, words = words).words
    }

    // whether the arc [a] is a word that ends at [to], not over a key read alone where [wholeSyllables]
    private fun takes(a: Int, to: Int, wholeSyllables: Boolean): Boolean =
        arcs.to[a] == to && arcs.word[a] != NO_WORD && !(wholeSyllables && arcs.flags[a] and ABBREVIATED != 0)

    private fun buildLattice(graph: SyllableGraph) {
        // collect's enter and leave pair up; a decode thrown out of half way leaves no path behind
        path.clear()
        arcs.abbreviated = false
        val n = graph.end
        // the arcs from a position whose collecting read only edges before where the graph changed
        // are those of the last decode, found again: kept, so a key typed at the end of long input
        // collects from its last syllables only (in 双拼 every key may be read alone, 简拼 in the
        // middle, and collecting from every position grew a key's time with the input's length)
        // and of a position whose collecting read past there, the arcs ending before it are the last
        // decode's too: only the steps that crossed it are taken again (collectOn)
        val was = builtFrom?.takeIf { it.start == graph.start }
        builtFrom = null
        val changed = was?.let { graph.sameUntil(it) } ?: -1
        arcs.clear(n)
        collectedAnew = 0
        collectedOn = 0
        steps = 0
        for (at in 0..n) {
            arcs.start[at] = arcs.size
            if (graph.edges(at).isEmpty()) continue
            when {
                was == null -> collectFrom(graph, at)
                lastArcs.readBefore(at, changed, changed == was.end, changed == n) -> arcs.copy(lastArcs, at)
                lastArcs.goesOnFrom(at, changed) -> collectOn(graph, at, changed)
                else -> collectFrom(graph, at)
            }
        }
        arcs.start[n + 1] = arcs.size
        builtFrom = graph
    }

    // the arcs from [at], anew
    private fun collectFrom(graph: SyllableGraph, at: Int) {
        reachRead = at
        reachTo = at
        collectedAnew++
        // the steps onto the last key and past it are kept: a key typed after it changes the graph there
        // (a 双拼 key alone at the end is a syllable begun, then the first of a pair), and collectOn takes
        // only those again. Not while a tone is typed: a word's tones are asked of the whole path to it
        crossFrom = if (toneAllows == null) graph.end - 1 else Int.MAX_VALUE
        startFrom = 0
        crossings.clear()
        collect(graph, at, dictionary.root, user?.root ?: -1, user?.mergedWords?.root ?: -1, 0f, false, limitFrom(graph, at))
        finish(graph, at)
    }

    private fun limitFrom(graph: SyllableGraph, at: Int) = if (at == graph.start) Int.MAX_VALUE else wordsPerReading

    // what collecting from [at] ends with, anew or going on
    private fun finish(graph: SyllableGraph, at: Int) {
        // nothing in the dictionary starts here: keep what was typed, so every path goes on. Asked
        // before the words only imported: no path goes on from those (review-G S2)
        // (a start-only word is no word here: going on, one ending before the change is among the arcs already)
        var raw = true
        for (a in arcs.start[at] until arcs.size) if (arcs.flags[a] and START_ONLY == 0) raw = false
        if (raw) {
            for (e in graph.edges(at)) arcs.add(graph.to(e), NO_WORD, penalties.raw)
        }
        if (at == graph.start) user?.startWords?.let { collectStart(graph, at, it, it.root, 0f, false) }
        arcs.sortByEnd(arcs.start[at], graph.end)
        arcs.reached(at, reachRead, reachTo)
        // input kept as typed is there for want of any word: gone when one is found, so never built on
        arcs.crossed(at, if (raw) Int.MAX_VALUE else crossFrom, crossings)
    }

    /**
     * The arcs from [at] where the last decode's collecting from it read past [changed], the first
     * position whose edges differ: its arcs ending before [changed], which only edges before it made,
     * and those of its steps onto [changed] or past it taken again, each the walk on from there.
     * The steps are in the order collecting took them and each is taken as collecting takes it, so
     * the arcs come out as [collectFrom]'s would, in their order (the tests compare the two).
     */
    private fun collectOn(graph: SyllableGraph, at: Int, changed: Int) {
        check(toneAllows == null) { "a word's tones are asked of the whole path to it" }
        // what it read then, though some of that is read no more: further than now only, which keeps nothing wrongly
        reachRead = lastArcs.readFrom(at)
        reachTo = lastArcs.readTo(at)
        collectedOn++
        arcs.copyEndingBefore(lastArcs, at, changed)
        crossFrom = changed
        startFrom = changed
        crossings.clear()
        val limit = limitFrom(graph, at)
        lastArcs.forEachCrossing(at) { from, edge, match, node, userNode, mergedNode, cost, short, abbreviated ->
            // one from a position at [changed] or past it is of a walk a step before it takes again
            val e = graph.edges(from).first + edge
            if (from < changed && graph.to(e) >= changed) {
                read(graph, from, e)
                path.abbreviated = abbreviated
                arcs.abbreviated = abbreviated > 0
                val child = if (node < 0) -1 else dictionary.child(node, graph.matches(e).syllable(match))
                step(graph, from, e, match, child, node, userNode, mergedNode, cost, short, limit)
            }
        }
        path.abbreviated = 0
        arcs.abbreviated = false
        finish(graph, at)
    }

    /**
     * Adds the words of every path from [node] in the dictionary, [userNode] in the user's words
     * and [mergedNode] in the dictionaries they merged, that the graph spells from [at]; -1 for a
     * trie the path has left. [short]: a syllable
     * of the path typed short (简拼, the start of one), whose words are scored by [uncounted].
     */
    private fun collect(graph: SyllableGraph, at: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, limit: Int) {
        for (e in graph.edges(at)) {
            read(graph, at, e)
            if (graph.kind(e) == Kind.RAW) continue
            val matches = graph.matches(e)
            // a key read alone stands for every syllable it begins, a score of them, and most nodes past
            // the root have a few children: those are walked once along the matches, both in the syllables'
            // order, where looking each match up would search the children a score of times
            val children = if (node < 0) 0 else dictionary.childCount(node)
            val walked = node >= 0 && matches.size > 1 && children <= matches.size * WALKED_PER_MATCH
            var next = if (walked) dictionary.firstChild(node) else 0
            val childrenEnd = next + children
            for (i in 0 until matches.size) {
                val child = when {
                    node < 0 -> -1
                    walked -> {
                        next = dictionary.childFrom(next, childrenEnd, matches.syllable(i))
                        if (next < childrenEnd && dictionary.syllable(next) == matches.syllable(i)) next else -1
                    }
                    else -> dictionary.child(node, matches.syllable(i))
                }
                step(graph, at, e, i, child, node, userNode, mergedNode, cost, short, limit)
            }
        }
    }

    /**
     * One step of [collect]: from [at] with [node], [userNode] and [mergedNode] over the match [i] of
     * the edge [e], to [child] in the dictionary (-1 for none): the words there, and the walk on.
     */
    private fun step(graph: SyllableGraph, at: Int, e: Int, i: Int, child: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, limit: Int) {
        val merged = user?.mergedWords
        val matches = graph.matches(e)
        val syllable = matches.syllable(i)
        val userChild = if (userNode < 0 || user == null) -1 else user.child(userNode, syllable)
        val mergedChild = if (mergedNode < 0 || merged == null) -1 else merged.child(mergedNode, syllable)
        if (child < 0 && userChild < 0 && mergedChild < 0) return
        steps++
        val kind = graph.kind(e)
        val to = graph.to(e)
        if (to >= crossFrom) crossings.add(at, e - graph.edges(at).first, i, node, userNode, mergedNode, cost, short, path.abbreviated)
        val edgeShort = short || isShort(kind)
        val tone = toneOf(graph, e)
        val c = cost + penalties.ofKind(kind) + penalties.ofMatch(matches.flags(i)) + nasal(kind, syllable) + penalties.tap * graph.against(e, i)
        var more = false
        enter(syllable, tone, matches.flags(i))
        if (child >= 0) {
            val words = minOf(dictionary.wordCount(child), limit)
            addWords(to, child, words, c, edgeShort)
            more = dictionary.childCount(child) > 0
            if (to == graph.end) lookAhead(kind, to, child, c, words, edgeShort)
        }
        if (addOthers(to, userChild, mergedChild, c, edgeShort, limit)) more = true
        if (to < graph.end && more) collect(graph, to, child, userChild, mergedChild, c, edgeShort, limit)
        leave(tone, matches.flags(i))
    }

    // what collecting from a position has read of the graph: see Lattice.readBefore
    private fun read(graph: SyllableGraph, at: Int, edge: Int) {
        if (at > reachRead) reachRead = at
        graph.to(edge).let { if (it > reachTo) reachTo = it }
    }

    // the user's words at [userNode] and the merged dictionaries' at [mergedNode] (-1 for none): whether longer ones go on
    private fun addOthers(to: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, limit: Int): Boolean {
        val user = user ?: return false
        var more = false
        if (userNode >= 0) {
            addUserWords(to, user, userNode, cost, short)
            more = user.childCount(userNode) > 0
        }
        val merged = user.mergedWords
        if (mergedNode >= 0 && merged != null) {
            addMergedWords(to, user, merged, mergedNode, minOf(merged.wordCount(mergedNode), limit), cost, short)
            more = more || merged.childCount(mergedNode) > 0
        }
        return more
    }

    /**
     * Adds the words only imported along every path from [node] of [starts] the graph spells from
     * [at], the input's start alone, marked so the search never goes on from them: never inside a
     * sentence nor after a word (U-101, C19). Those the user made theirs since are in the user's
     * trie, whole words, and left out here; so are those blocked.
     */
    private fun collectStart(graph: SyllableGraph, at: Int, starts: StartWords, node: Int, cost: Float, short: Boolean) {
        val user = user ?: return
        for (e in graph.edges(at)) {
            read(graph, at, e)
            val kind = graph.kind(e)
            if (kind == Kind.RAW) continue
            val to = graph.to(e)
            val matches = graph.matches(e)
            val edgeShort = short || isShort(kind)
            for (i in 0 until matches.size) {
                val child = starts.child(node, matches.syllable(i))
                if (child < 0) continue
                val c = cost + penalties.ofKind(kind) + penalties.ofMatch(matches.flags(i)) + nasal(kind, matches.syllable(i)) + penalties.tap * graph.against(e, i)
                abbreviated(matches.flags(i), 1)
                for (w in 0 until starts.wordCount(child)) {
                    val word = starts.word(child, w)
                    // going on (collectOn), those ending before where it goes on from are there already
                    if (to >= startFrom && user.startOnly(word) && !user.blocked(word, -1)) arcs.add(to, word, c + penalties.imported, short = edgeShort, startOnly = true)
                }
                if (to < graph.end && starts.childCount(child) > 0) collectStart(graph, to, starts, child, c, edgeShort)
                abbreviated(matches.flags(i), -1)
            }
        }
    }

    // the first [words] of the dictionary's at [node], those the user blocked left out
    private fun addWords(to: Int, node: Int, words: Int, cost: Float, short: Boolean) {
        val first = dictionary.wordsOf(node)
        for (at in first until first + words) {
            val word = dictionary.wordAt(at)
            if (user?.blocked(word, node) != true) arcs.add(to, word, cost + dictionary.weightAt(at) + coarseness(word) + toneCost(word), short = short)
        }
    }

    private fun coarseness(word: Int) = if (coarse.unasked(word, user, vocabulary.size)) CoarseWords.PENALTY else 0f

    // one reading each, so no weight; and few enough to take them all
    private fun addUserWords(to: Int, user: UserWords, node: Int, cost: Float, short: Boolean) {
        for (w in 0 until user.wordCount(node)) {
            val word = user.word(node, w)
            if (!user.blocked(word, -1)) arcs.add(to, word, cost + coarseness(word) + toneCost(word), short = short)
        }
    }

    // the tone typed after [edge]'s syllable, 0 where none is asked of the words
    private fun toneOf(graph: SyllableGraph, edge: Int) = if (toneAllows == null) 0 else graph.tone(edge)

    // [syllable] and its typed [tone] onto the path collect is on, its match's [flags] counted: the arcs
    // over a key read alone are marked so, for what reads the input in whole syllables (startWords, arcsBetween)
    private fun enter(syllable: Int, tone: Int, flags: Int) {
        path.enter(syllable, tone, flags)
        arcs.abbreviated = path.abbreviated > 0
    }

    private fun leave(tone: Int, flags: Int) {
        path.leave(tone, flags)
        arcs.abbreviated = path.abbreviated > 0
    }

    private fun abbreviated(flags: Int, by: Int) {
        path.count(flags, by)
        arcs.abbreviated = path.abbreviated > 0
    }

    // [Penalties.tone] where a tone typed on the path to [word] is one a character of it does not have
    // a dictionary word read in place, no String made: this is on every arc while a tone is typed
    private fun toneCost(word: Int): Float {
        val allows = toneAllows
        if (allows == null || path.toned == 0) return 0f
        if (word < vocabulary.size) return path.toneCost(vocabulary.length(word), allows, penalties.tone) { vocabulary.char(word, it) }
        val text = user?.text(word) ?: return 0f
        return path.toneCost(text.length, allows, penalties.tone) { text[it] }
    }

    // the first [words] of a merged dictionary's at [node], best first, as many as the dictionary's
    // own: rime-frost has hundreds at a syllable, each an arc the scorer would weigh on every key
    private fun addMergedWords(to: Int, user: UserWords, merged: StartWords, node: Int, words: Int, cost: Float, short: Boolean) {
        for (w in 0 until words) {
            val word = merged.word(node, w)
            if (!user.blocked(word, -1)) arcs.add(to, word, cost + coarseness(word) + toneCost(word), short = short)
        }
        // past the limit, the user's chosen ones still: a word they added, low in its dictionary (review S5)
        if (words < merged.wordCount(node) && user.anyMergedChosen()) {
            for (w in words until merged.wordCount(node)) {
                val word = merged.word(node, w)
                if (user.chosen(word) && !user.blocked(word, -1)) arcs.add(to, word, cost + coarseness(word) + toneCost(word), short = short)
            }
        }
    }

    /**
     * The words of the last decode's arcs from the start of [graph] to [to] that [take] says, each
     * once, as candidates scored by their arcs' costs alone: those a list may lack that the user
     * has (impl/design-1b.md §1, injection). Every word from the start is an arc, with no limit.
     * [wholeSyllables]: none over a 双拼 key read alone where it and the next make a syllable, for a code that
     * [SyllableGraph.readsWhole] (HabitOrder).
     */
    fun startWords(graph: SyllableGraph, to: Int, take: IntPredicate, wholeSyllables: Boolean = false): List<Candidate> {
        if (lastEnd != graph.end || lastStart != graph.start) return emptyList()
        val out = ArrayList<Candidate>()
        seen.clear()
        for (a in arcs.start[graph.start] until arcs.start[graph.start + 1]) {
            val word = arcs.word[a]
            if (!takes(a, to, wholeSyllables)) continue
            if (take.test(word) && seen.add(word)) out += Candidate(text(word), to, arcs.cost[a], intArrayOf(word), intArrayOf(to))
        }
        return out
    }

    /**
     * Up to [limit] of the last decode's sentences of [graph], each text once, as [decode] gives
     * them: more than it gave, for words put in place of theirs to leave as many apart
     * (review-design-1c S4). The beams' backtracking only, no search.
     */
    fun moreSentences(graph: SyllableGraph, limit: Int = beam): List<Candidate> =
        if (lastEnd != graph.end || lastStart != graph.start) emptyList() else sentences(graph, limit)

    /**
     * The words of the last decode's arcs of [graph] from [from] to [to], each once, as [startWords]
     * gives them: what a word added by hand is the only one of (impl/design-1c.md §1). Read off the
     * lattice the decode built, no search. [wholeSyllables] as for [startWords].
     */
    fun arcsBetween(graph: SyllableGraph, from: Int, to: Int, wholeSyllables: Boolean = false): List<Candidate> {
        if (from !in graph.start until graph.end || lastEnd != graph.end || lastStart != graph.start) return emptyList()
        val out = ArrayList<Candidate>()
        seen.clear()
        for (a in arcs.start[from] until arcs.start[from + 1]) {
            val word = arcs.word[a]
            if (!takes(a, to, wholeSyllables)) continue
            if (seen.add(word)) out += Candidate(text(word), to - from, arcs.cost[a], intArrayOf(word), intArrayOf(to))
        }
        return out
    }

    /**
     * The words at [node], reached by a syllable still being typed, scored again as the likeliest
     * longer word each starts: `yibeik` is 一杯 and the start of 咖啡 more likely than 一杯可, though
     * 咖 alone is rare. The text stays what was typed; the beam keeps the better of the two scores.
     */
    private fun lookAhead(kind: Kind, to: Int, node: Int, cost: Float, words: Int, short: Boolean) {
        if (kind != Kind.INITIAL && kind != Kind.PARTIAL || dictionary.childCount(node) == 0) return
        val cache = extensions
        val longer = cache[node] ?: extensionsOf(dictionary, vocabulary, scorer, node).also { cache[node] = it }
        for (w in 0 until minOf(words, longer.size)) {
            val word = dictionary.word(node, w)
            if (longer[w] != NO_WORD && user?.blocked(word, node) != true) {
                arcs.add(to, word, cost + dictionary.weight(node, w) + penalties.lookAhead + coarseness(word), longer[w], short)
            }
        }
    }

    private fun nasal(kind: Kind, syllable: Int) = if (kind == Kind.SYLLABLE && syllable in NASALS) penalties.nasal else 0f

    private fun search(graph: SyllableGraph, prev2: Int, prev: Int, last: Int) {
        val n = graph.end
        if (beamSize.size < n + 1) {
            beamSize = beamSize.copyOf(n + 1)
            stateScore = stateScore.copyOf((n + 1) * beam)
            stateWord = stateWord.copyOf((n + 1) * beam)
            statePrev = statePrev.copyOf((n + 1) * beam)
            stateBack = stateBack.copyOf((n + 1) * beam)
        }
        val from = if (last >= 0) unchangedUntil(graph, last) else graph.start
        beamSize.fill(0, from, n + 1)
        if (from == graph.start) insert(graph.start, 0f, prev, prev2, -1)
        scored = 0
        for (at in graph.start until n) {
            if (beamSize[at] > 0) extend(at, from, if (at == graph.start) n else -1)
        }
    }

    /**
     * Extends the sequences ending at [at] by the arcs from there, those ending at [from] or later;
     * one ending at [wholeEnd] reads all the input alone, scored by [whole] if given.
     */
    private fun extend(at: Int, from: Int, wholeEnd: Int) {
        val first = at * beam
        val size = beamSize[at]
        var looked = false
        var asked = 0
        // those ending before [from] come first, and their beams are kept
        for (a in arcs.firstEndingAt(at, from) until arcs.start[at + 1]) {
            val to = arcs.to[a]
            if (arcs.flags[a] and START_ONLY != 0) continue // a start-only word begins no sentence
            if (!looked) {
                // once per state rather than per arc: most of what scoring a word takes. Arcs only
                // go forward, so this beam does not change while it is extended
                for (k in 0 until size) {
                    contexts[k] = scorer.context(statePrev[first + k], stateWord[first + k])
                    runs[k] = scorer.trigrams(contexts[k]).bits
                }
                looked = true
            }
            val word = arcs.word[a]
            val scored = arcs.scored[a]
            val scoring = scoring(a, to == wholeEnd)
            for (k in 0 until size) {
                val s = first + k
                val base = stateScore[s] + arcs.cost[a]
                // scores only fall from here, and the states come best first
                if (beamSize[to] == beam && base <= stateScore[to * beam + beam - 1]) break
                val lm = if (word == NO_WORD) 0f else scoring.scoreAfter(contexts[k], Trigrams(runs[k]), scored)
                insert(to, base + lm, word, stateWord[s], s)
                asked++
            }
        }
        this.scored += asked
    }

    // the scorer of arc [a]: without the user's counts over a syllable typed short, or (MULTI) reading all the input alone
    private fun scoring(a: Int, wholeInput: Boolean): WordScorer = when {
        arcs.flags[a] and SHORT != 0 -> uncounted ?: scorer
        wholeInput -> whole ?: scorer
        else -> scorer
    }

    /**
     * The first position whose beam may differ from the last decode's, or [SyllableGraph.start]
     * to search it all. A beam depends only on the arcs ending there, in their order, and on the
     * beams they start from; so beams before the first arc that differs are as the last decode
     * left them. The typed text is not among that: text kept as typed is read from the input
     * only when the candidates are made.
     */
    private fun unchangedUntil(graph: SyllableGraph, last: Int): Int =
        maxOf(arcs.sameUntil(lastArcs, graph.start, minOf(last, graph.end)), graph.start)

    /** Puts a new state into the beam at [at], unless the beam is full of better ones. */
    private fun insert(at: Int, score: Float, word: Int, prev: Int, back: Int) {
        val offset = at * beam
        var size = beamSize[at]
        // most of what is offered a full beam is below its last, and so below the same word on from the
        // same state if the beam has it: turned away before looking for that one
        if (size == beam && score <= stateScore[offset + beam - 1]) return
        // the same word on from the same state, by another reading of the same input: keep the best
        for (s in offset until offset + size) {
            if (stateBack[s] == back && stateWord[s] == word) {
                if (score <= stateScore[s]) return
                for (t in s until offset + size - 1) move(t + 1, t)
                size--
                break
            }
        }
        var s = offset + minOf(size, beam - 1)
        while (s > offset && stateScore[s - 1] < score) {
            move(s - 1, s)
            s--
        }
        stateScore[s] = score
        stateWord[s] = word
        statePrev[s] = prev
        stateBack[s] = back
        beamSize[at] = minOf(size + 1, beam)
    }

    private fun move(from: Int, to: Int) {
        stateScore[to] = stateScore[from]
        stateWord[to] = stateWord[from]
        statePrev[to] = statePrev[from]
        stateBack[to] = stateBack[from]
    }

    private fun sentences(graph: SyllableGraph, limit: Int): List<Candidate> {
        val end = graph.end
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (last in end * beam until end * beam + beamSize[end]) {
            if (out.size >= limit) break
            var count = 0
            var s = last
            while (stateBack[s] >= 0) {
                count++
                s = stateBack[s]
            }
            val path = IntArray(count)
            s = last
            while (count > 0) {
                path[--count] = s
                s = stateBack[s]
            }
            val text = path.joinToString("") { textOf(graph, it) }
            if (seen.add(text)) {
                out += Candidate(text, end, stateScore[last], IntArray(path.size) { stateWord[path[it]] }, IntArray(path.size) { path[it] / beam })
            }
        }
        return out
    }

    private fun textOf(graph: SyllableGraph, state: Int): String {
        val word = stateWord[state]
        return if (word == NO_WORD) asTyped(graph.text(stateBack[state] / beam, state / beam)) else text(word)
    }

    private fun text(word: Int) = if (word < vocabulary.size || user == null) vocabulary.word(word) else user.text(word)

    /**
     * Words the input may start with, each once, best first: those reading all of it before the
     * rest, if [wholeFirst]. An initial alone may start with thousands (`s` is
     * every sh and s syllable), so text is made only for those returned. Arcs that begin the group
     * as they began the last decode's, of the [kept] it scored, keep their scores: a key changes
     * the arcs ending near the end, and those come last.
     */
    private fun firstWords(graph: SyllableGraph, prev2: Int, prev: Int, limit: Int, kept: Int, last: Int): List<Candidate> {
        val from = arcs.start[graph.start]
        val until = arcs.start[graph.start + 1]
        val context = scorer.context(prev2, prev)
        val trigrams = scorer.trigrams(context)
        // the arcs from the start the last decode's, none reading to the end then or now: its scores and
        // its order stand (typing on at the end of long input, thousands of arcs a 双拼 key each)
        if (firstUntouched(graph, from, until, kept, last)) {
            firstScored = kept
            firstAnew = 0
            rankedGraph = graph
            return rankedWords(graph, limit)
        }
        if (firstScores.size < until - from) firstScores = firstScores.copyOf(until - from)
        val scores = firstScores
        var same = 0
        if (kept > 0) {
            val lastFrom = lastArcs.start[graph.start]
            // with [whole], an arc that read all of the input then, or does now, is scored anew
            val sameUntil = if (whole == null) Int.MAX_VALUE else minOf(last, graph.end)
            while (same < minOf(kept, until - from) && lastArcs.same(lastFrom + same, arcs, from + same) && arcs.to[from + same] < sameUntil) same++
        }
        // score, then arc, in one sortable long: best first, ties in arc order
        val order = LongArray(until - from) { i ->
            val a = from + i
            val word = arcs.word[a]
            val scoring = scoring(a, arcs.to[a] == graph.end)
            if (i >= same) scores[i] = if (word == NO_WORD) Float.NEGATIVE_INFINITY else arcs.cost[a] + scoring.scoreAfter(context, trigrams, arcs.scored[a])
            (sortable(-scores[i]).toLong() shl Int.SIZE_BITS) or a.toLong()
        }
        firstScored = until - from
        firstAnew = until - from - same
        order.sort()
        // those reading all the input first, the rest after them, in the order of the scores: by
        // score alone the common characters of a part of it (几 家 加 of jiao) came before a
        // rarer one of all of it (觉), far down the list. Not longest first: a word picked piece
        // by piece is mostly short
        firstRanked = if (!wholeFirst) order else LongArray(order.size).also { ranked ->
            var at = 0
            for (k in order) if (arcs.to[k.toInt()] == graph.end) ranked[at++] = k
            for (k in order) if (arcs.to[k.toInt()] != graph.end) ranked[at++] = k
        }
        rankedGraph = graph
        return rankedWords(graph, limit)
    }

    // whether the arcs [from] until [until], from the start, are the [kept] the last decode ranked, none reading to the end then or now
    private fun firstUntouched(graph: SyllableGraph, from: Int, until: Int, kept: Int, last: Int): Boolean =
        kept == until - from && firstRanked.size == kept && arcs.copiedAt(graph.start) && lastArcs.start[graph.start] == from &&
            (kept == 0 || arcs.to[until - 1] < minOf(last, graph.end))

    /** The first [limit] words, each once, of the arcs from the start of [graph] as [firstWords] ranked them. */
    private fun rankedWords(graph: SyllableGraph, limit: Int): List<Candidate> {
        val from = arcs.start[graph.start]
        var next = 0
        val out = ArrayList<Candidate>()
        seen.clear()
        while (out.size < limit && next < firstRanked.size) {
            val a = firstRanked[next++].toInt()
            val word = arcs.word[a]
            if (word != NO_WORD && seen.add(word)) {
                out += Candidate(text(word), arcs.to[a], firstScores[a - from], intArrayOf(word), intArrayOf(arcs.to[a]))
            }
        }
        return out
    }

    /**
     * The path [collect] is on: its syllables and the tones typed after them (0 none), [length] of them
     * and [toned] with a tone, and how many of them a 双拼 key read alone where it and the next make a
     * syllable ([SyllableMatches.ABBREV]).
     */
    private class Path {
        var syllables = IntArray(PATH)
        var tones = IntArray(PATH)
        var length = 0
        var toned = 0
        var abbreviated = 0

        fun clear() {
            length = 0
            toned = 0
            abbreviated = 0
        }

        fun enter(syllable: Int, tone: Int, flags: Int) {
            if (length == syllables.size) {
                syllables = syllables.copyOf(2 * length)
                tones = tones.copyOf(2 * length)
            }
            syllables[length] = syllable
            tones[length] = tone
            length++
            if (tone > 0) toned++
            count(flags, 1)
        }

        fun leave(tone: Int, flags: Int) {
            length--
            if (tone > 0) toned--
            count(flags, -1)
        }

        fun count(flags: Int, by: Int) {
            if (flags and SyllableMatches.ABBREV != 0) abbreviated += by
        }

        /** [penalty] where a tone typed on the path is one a character of the word of [chars] chars, [charAt] each, does not have by [allows]. */
        inline fun toneCost(chars: Int, allows: (codePoint: Int, syllable: Int, tone: Int) -> Boolean, penalty: Float, charAt: (Int) -> Char): Float {
            // one code point a syllable, or no telling which character a tone is of
            var count = 0
            var at = 0
            while (at < chars) {
                at += if (charAt(at).isHighSurrogate() && at + 1 < chars && charAt(at + 1).isLowSurrogate()) 2 else 1
                count++
            }
            if (count != length) return 0f
            at = 0
            for (k in 0 until length) {
                val c = charAt(at)
                val pair = c.isHighSurrogate() && at + 1 < chars && charAt(at + 1).isLowSurrogate()
                val cp = if (pair) Character.toCodePoint(c, charAt(at + 1)) else c.code
                if (tones[k] > 0 && !allows(cp, syllables[k], tones[k])) return penalty
                at += if (pair) 2 else 1
            }
            return 0f
        }
    }

    /**
     * Arcs grouped by where they start: those from `at` are `start[at] until start[at + 1]`, in
     * the order they end. That puts what a key changes, the arcs ending near the end of the
     * input, last in each group; in the order the dictionary gives them, a change deep in a long
     * word would come before the short arcs from the same place and hide that they are the same.
     */
    private class Lattice {
        var start = IntArray(0)
        val to = IntList()
        val word = IntList() // NO_WORD for input kept as typed
        val scored = IntList() // the word the model scores: word, or a longer one it starts
        val cost = FloatList()
        val flags = IntList() // SHORT for an arc over a syllable typed short (scored without the user's counts), START_ONLY for a start-only word,
        // ABBREVIATED for one over a 双拼 key read alone where it and the next make a syllable
        val size get() = to.size

        // per position, what collecting from it read: the furthest position it read the edges of, the furthest they went
        private var reachRead = IntArray(0)
        private var reachTo = IntArray(0)
        // per position, whether its arcs are the last decode's, copied: the same, with no comparing
        private var copied = BooleanArray(0)

        // per position, the steps of its collecting onto [crossFrom] of it or past it, in the order taken
        private var crossings = arrayOfNulls<Crossings>(0)
        private var crossFrom = IntArray(0)

        /** Whether the arcs from [at] were [copy]'s, the other lattice's as they were. */
        fun copiedAt(at: Int) = copied[at]

        /** The first position before [end] an arc ends at that is not [last]'s, of the arcs from [from] on; [end] for none. */
        fun sameUntil(last: Lattice, from: Int, end: Int): Int {
            var until = end
            var at = from
            while (at < until) {
                // those copied from the last decode's are the same
                if (copied[at]) {
                    at++
                    continue
                }
                var a = last.start[at]
                var b = start[at]
                val aEnd = last.start[at + 1]
                val bEnd = start[at + 1]
                while (a < aEnd && b < bEnd && last.same(a, this, b)) {
                    a++
                    b++
                }
                // the groups differ from here on; being sorted by end, every arc ending before the
                // first of these ends is in the same place in both
                for (i in a until aEnd) until = minOf(until, last.to[i])
                for (i in b until bEnd) until = minOf(until, to[i])
                at++
            }
            return until
        }

        /** Whether the arcs added are over a 简拼 in the middle: the decoder's path, as it collects. */
        var abbreviated = false

        // for sorting
        private var ends = IntArray(0)
        private val toCopy = IntList()
        private val wordCopy = IntList()
        private val scoredCopy = IntList()
        private val costCopy = FloatList()
        private val flagsCopy = IntList()

        fun clear(end: Int) {
            if (start.size < end + 2) {
                start = IntArray(end + 2)
                ends = IntArray(end + 2)
                reachRead = IntArray(end + 2)
                reachTo = IntArray(end + 2)
                copied = BooleanArray(end + 2)
                crossings = arrayOfNulls(end + 2)
                crossFrom = IntArray(end + 2)
            }
            copied.fill(false, 0, end + 2)
            crossings.fill(null, 0, end + 2)
            to.clear()
            word.clear()
            scored.clear()
            cost.clear()
            flags.clear()
        }

        /** Orders the arcs from [first] on, none ending past [end], by where they end, keeping the order of those ending together. */
        fun sortByEnd(first: Int, end: Int) {
            var sorted = true
            for (a in first + 1 until size) {
                if (to[a] < to[a - 1]) {
                    sorted = false
                    break
                }
            }
            if (sorted) return
            // a counting sort: ends[q] becomes where the arcs ending at q go
            toCopy.clear()
            wordCopy.clear()
            scoredCopy.clear()
            costCopy.clear()
            flagsCopy.clear()
            ends.fill(0, 0, end + 1)
            toCopy.addAll(to, first, size)
            wordCopy.addAll(word, first, size)
            scoredCopy.addAll(scored, first, size)
            costCopy.addAll(cost, first, size)
            flagsCopy.addAll(flags, first, size)
            for (a in first until size) ends[to[a]]++
            var next = first
            for (q in 0..end) next += ends[q].also { ends[q] = next }
            for (i in 0 until toCopy.size) {
                val a = ends[toCopy[i]]++
                to[a] = toCopy[i]
                word[a] = wordCopy[i]
                scored[a] = scoredCopy[i]
                cost[a] = costCopy[i]
                flags[a] = flagsCopy[i]
            }
        }

        fun add(to: Int, word: Int, cost: Float, scored: Int = word, short: Boolean = false, startOnly: Boolean = false) {
            this.to += to
            this.word += word
            this.scored += scored
            this.cost += cost
            this.flags += (if (short) SHORT else 0) or (if (startOnly) START_ONLY else 0) or (if (abbreviated) ABBREVIATED else 0)
        }

        fun reached(at: Int, read: Int, to: Int) {
            reachRead[at] = read
            reachTo[at] = to
            copied[at] = false
        }

        /**
         * Whether the arcs from [at] were collected reading only the edges before [changed], the first
         * position where the graph they were collected from and the next differ: then the next has
         * them all the same. An edge going to [changed] too, where it is the end of both graphs
         * ([wasEnd], [isEnd]) or of neither: the end is where a syllable still being typed reads on.
         */
        fun readBefore(at: Int, changed: Int, wasEnd: Boolean, isEnd: Boolean): Boolean =
            at < changed && reachRead[at] < changed && (reachTo[at] < changed || reachTo[at] == changed && wasEnd == isEnd)

        /** Adds [other]'s arcs from [at], with what collecting them read. */
        fun copy(other: Lattice, at: Int) {
            val from = other.start[at]
            val until = other.start[at + 1]
            to.addAll(other.to, from, until)
            word.addAll(other.word, from, until)
            scored.addAll(other.scored, from, until)
            cost.addAll(other.cost, from, until)
            flags.addAll(other.flags, from, until)
            reached(at, other.reachRead[at], other.reachTo[at])
            copied[at] = true
            crossings[at] = other.crossings[at]
            crossFrom[at] = other.crossFrom[at]
        }

        /** Adds [other]'s arcs from [at] that end before [end]: the first of them, in the order they end. */
        fun copyEndingBefore(other: Lattice, at: Int, end: Int) {
            val from = other.start[at]
            val until = other.firstEndingAt(at, end)
            to.addAll(other.to, from, until)
            word.addAll(other.word, from, until)
            scored.addAll(other.scored, from, until)
            cost.addAll(other.cost, from, until)
            flags.addAll(other.flags, from, until)
        }

        /** The first arc from [at] that ends at [end] or past it; the arcs from [at] are in the order they end. */
        fun firstEndingAt(at: Int, end: Int): Int {
            var lo = start[at]
            var hi = start[at + 1]
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (to[mid] < end) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** The furthest position collecting from [at] read the edges of, and the furthest those went to. */
        fun readFrom(at: Int) = reachRead[at]
        fun readTo(at: Int) = reachTo[at]

        /** Keeps the steps of collecting from [at] onto [from] or past it, [steps] (copied: the caller's are its own to fill again). */
        fun crossed(at: Int, from: Int, steps: Crossings) {
            crossFrom[at] = from
            crossings[at] = if (from == Int.MAX_VALUE) null else steps.copy()
        }

        /**
         * Whether collecting can go on from the steps kept of [at] where the graph changed at
         * [changed]: its edges the same, and every step onto [changed] or past it kept.
         */
        fun goesOnFrom(at: Int, changed: Int): Boolean = at < changed && crossings[at] != null && crossFrom[at] <= changed

        /** The steps kept of collecting from [at], in the order taken. */
        inline fun forEachCrossing(at: Int, visit: (from: Int, edge: Int, match: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, abbreviated: Int) -> Unit) {
            val c = crossings[at] ?: return
            for (k in 0 until c.size) c.read(k, visit)
        }

        fun same(arc: Int, other: Lattice, otherArc: Int) =
            to[arc] == other.to[otherArc] && word[arc] == other.word[otherArc] && scored[arc] == other.scored[otherArc] &&
                cost[arc] == other.cost[otherArc] && flags[arc] == other.flags[otherArc]
    }

    /**
     * Steps of [collect], each what [step] is called with: the position, the edge (counted from the
     * position's first), the match, the three tries' nodes, the cost so far, whether a syllable before
     * was typed short, and how many keys before were read alone ([Path.abbreviated]).
     */
    private class Crossings {
        private var ints = IntArray(0)
        private var costs = FloatArray(0)
        var size = 0
            private set

        fun clear() {
            size = 0
        }

        fun add(from: Int, edge: Int, match: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, abbreviated: Int) {
            if (size == costs.size) {
                costs = costs.copyOf(maxOf(GROW, size * 2))
                ints = ints.copyOf(costs.size * FIELDS)
            }
            val k = size * FIELDS
            ints[k] = from
            ints[k + 1] = edge
            ints[k + 2] = match
            ints[k + 3] = node
            ints[k + 4] = userNode
            ints[k + 5] = mergedNode
            ints[k + 6] = if (short) -abbreviated - 1 else abbreviated
            costs[size++] = cost
        }

        inline fun read(index: Int, visit: (from: Int, edge: Int, match: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, abbreviated: Int) -> Unit) {
            val k = index * FIELDS
            val packed = ints[k + 6]
            visit(ints[k], ints[k + 1], ints[k + 2], ints[k + 3], ints[k + 4], ints[k + 5], costs[index], packed < 0, if (packed < 0) -packed - 1 else packed)
        }

        fun copy() = Crossings().also {
            it.ints = ints.copyOf(size * FIELDS)
            it.costs = costs.copyOf(size)
            it.size = size
        }

        private companion object {
            const val FIELDS = 7
            const val GROW = 64
        }
    }

    companion object {
        /** The syllables of a nasal alone: see [Penalties.nasal]. */
        private val NASALS = setOf("m", "n", "ng").map { Syllables.id(it) }.toIntArray()

        const val DEFAULT_BEAM = 16

        // syllables of the path collect is on, room for at first
        private const val PATH = 16
        // children walked rather than searched up to this many a match: a search reads about log2 of them
        private const val WALKED_PER_MATCH = 2
        const val DEFAULT_WORDS = 64
        // on the 256-sample evaluation set, 16 words are as accurate as 256 at a third of the
        // time, and a beam of 64 gains one sample of 256 at four times the time
        const val DEFAULT_WORDS_PER_READING = 16
        const val DEFAULT_SENTENCES = 5

        // the bits of Lattice.flags
        private const val SHORT = 1
        private const val START_ONLY = 2
        private const val ABBREVIATED = 4
    }
}

/**
 * Input read as [text], from the start of the input up to [end]; [words] are its vocabulary ids,
 * [NO_WORD] for text kept as typed, and [ends] where in the input each of them ends. A prediction
 * reads no input: its end, and its word's, is 0.
 */
class Candidate internal constructor(
    val text: String,
    val end: Int,
    val score: Float,
    val words: IntArray,
    val ends: IntArray,
) {
    override fun toString() = "$text($end, $score)"
}

/**
 * [sentences] read the whole input, best first; [words] are every word it may start with, best
 * first, each the start of a candidate list the user picks from piece by piece.
 */
class Decoding internal constructor(val sentences: List<Candidate>, val words: List<Candidate>)

/** [value]'s bits as an int that orders as the floats do. */
private fun sortable(value: Float): Int {
    val bits = java.lang.Float.floatToIntBits(value)
    return if (bits < 0) bits xor Int.MAX_VALUE else bits
}

// the words at a node a longer word is looked for, and those taken of each longer reading:
// on the evaluation set 1 to 16 of the latter read the same
private const val LOOK_AHEAD_WORDS = 16
private const val EXTENSIONS_PER_READING = 4

/**
 * For the first [LOOK_AHEAD_WORDS] words at [node], the likeliest word a syllable longer that
 * starts with it, or [NO_WORD] where none is likelier than the word itself. Worked out once a
 * node, by the words alone: every word there against every one after it, too many for a key.
 */
private fun extensionsOf(dictionary: PinyinDictionary, vocabulary: Vocabulary, scorer: WordScorer, node: Int): IntArray {
    val n = minOf(dictionary.wordCount(node), LOOK_AHEAD_WORDS)
    val out = IntArray(n) { NO_WORD }
    val context = scorer.context(NO_WORD, NO_WORD)
    val trigrams = scorer.trigrams(context)
    val prefixes = Array(n) { vocabulary.word(dictionary.word(node, it)) }
    val best = FloatArray(n) { scorer.scoreAfter(context, trigrams, dictionary.word(node, it)) }
    val first = dictionary.firstChild(node)
    for (next in first until first + dictionary.childCount(node)) {
        for (i in 0 until minOf(dictionary.wordCount(next), EXTENSIONS_PER_READING)) {
            val word = dictionary.word(next, i)
            val score = scorer.scoreAfter(context, trigrams, word)
            var text: String? = null
            for (w in 0 until n) {
                if (score <= best[w]) continue
                if (text == null) text = vocabulary.word(word)
                if (text.startsWith(prefixes[w])) {
                    best[w] = score
                    out[w] = word
                }
            }
        }
    }
    return out
}

private fun Penalties.ofKind(kind: Kind) = when (kind) {
    Kind.SYLLABLE -> 0f
    Kind.INITIAL -> initial
    Kind.PARTIAL -> partial
    Kind.EXTENDED -> extended
    Kind.RAW -> raw
    Kind.LETTER -> letter
}

// of a match's flags (SyllableMatches)
private fun Penalties.ofMatch(flags: Int): Float {
    var p = 0f
    if (flags and SyllableMatches.FUZZY != 0) p += fuzzy
    if (flags and SyllableMatches.TYPO != 0) p += typo
    if (flags and SyllableMatches.NEIGHBOUR != 0) p += neighbour
    if (flags and SyllableMatches.ABBREV != 0) p += abbrev
    return p
}

// a syllable typed short: an initial, the start of one, one read as the start of a longer
private fun isShort(kind: Kind) = kind == Kind.INITIAL || kind == Kind.PARTIAL || kind == Kind.EXTENDED
