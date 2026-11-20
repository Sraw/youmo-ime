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
 * Reads a [SyllableGraph] as Chinese: every dictionary word along every path is a lattice arc; a beam
 * search keeps the [beam] best word sequences ending at each position, scored by [scorer] plus [penalties].
 *
 * Sequences are not merged on their last two words as exact trigram Viterbi would: that finds the same
 * best sentence but leaves runners-up differing only at the end, while users need ones differing anywhere
 * (在/再 early on). Whole sequences give a real N-best for candidates and rerankers, at the price of one
 * text via different words (你好, 你 + 好) taking several beam places.
 *
 * Each decode builds on the last: beams before the first position a changed arc ends at are kept, so a key
 * at the end of long input searches only its last syllables. Without this, nearly all of it [scorer]
 * time, a key's cost would grow with input length.
 *
 * Reuses its buffers between calls: not thread-safe, and meant to live as long as the engine.
 */
class PinyinDecoder(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val scorer: WordScorer,
    private val penalties: Penalties = Penalties(),
    private val beam: Int = DEFAULT_BEAM,
    /** Per reading past the first word, most probable first; the first word gets them all. */
    private val wordsPerReading: Int = DEFAULT_WORDS_PER_READING,
    /** Any change to them (learned, added, blocked, imported) needs a [reset]. */
    private val user: UserWords? = null,
    /** lookAhead's longer words per [dictionary] node, lazily: shared by decoders of the same dictionary and scores. */
    extensions: () -> Array<IntArray?> = { arrayOfNulls<IntArray>(dictionary.nodeCount) },
    /**
     * Whether words reading all the input go first; else all by score. Not on the nine keys: there digits
     * read as many meaningless whole-input words, and a syllable picked in the column narrows them anyway.
     */
    private val wholeFirst: Boolean = true,
    /** Dictionary words ranked [CoarseWords.PENALTY] lower. */
    private val coarse: CoarseWords = CoarseWords.NONE,
    /** How text kept as typed shows: on the nine keys, [T9Segmenter.asTyped][org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter.asTyped]. */
    private val asTyped: (String) -> String = { it },
    /** Scores for a word reading the whole input alone, if not [scorer]'s: same contexts, no user counts (LearningUse.Counts.MULTI). */
    private val whole: WordScorer? = null,
    /**
     * Scores for a word over a syllable typed short (initial, partial, or whole read as the start of a
     * longer: 简拼), if not [scorer]'s: no user counts, same contexts, so full typing never moves short typing.
     */
    private val uncounted: WordScorer? = null,
    /**
     * Whether a character read as a syllable may have tone 1-5 (ReadingTable.allows): a word with a
     * character lacking the typed tone costs [Penalties.tone], kept. Null, or no tone typed (all but
     * zhuyin keyboards): never asked.
     */
    private val toneAllows: ((codePoint: Int, syllable: Int, tone: Int) -> Boolean)? = null,
    /**
     * False: over keys alone each match is looked up as everywhere else, which [collectTyped] must equal
     * (tests only). Fixed for the decoder's life: arcs kept from an earlier decode were walked the other way.
     */
    internal val walksTyped: Boolean = true,
) {
    init {
        require(beam >= 1 && wordsPerReading >= 1) { "beam $beam, words per reading $wordsPerReading" }
    }

    private var arcs = Lattice()

    // per dictionary node reached by a partial syllable, see extensionsOf; built once (under a MB) and kept
    // when scores change: it only picks which longer word stands in, scored fresh on each key. Shared
    // when given, by decoders that score alike
    private val extensions: Array<IntArray?> by lazy(LazyThreadSafetyMode.NONE) {
        // checked once here: a wrong size would fail deep in lookAhead past some node
        extensions().also { require(it.size == dictionary.nodeCount) { "extensions for ${it.size} of ${dictionary.nodeCount} nodes" } }
    }

    // the beams came from these, or from nothing if lastEnd is -1
    private var lastArcs = Lattice()
    private var lastEnd = -1
    // the graph [arcs] came from (null till one is done and after [reset]), with how far collecting from a
    // position read edges and how far they went
    private var builtFrom: SyllableGraph? = null
    private var reachRead = 0
    private var reachTo = 0

    /** For tests: positions the last decode collected from anew. */
    internal var collectedAnew = 0
        private set

    /** For tests: positions it continued from the last decode's steps ([collectOn]), earlier arcs kept. */
    internal var collectedOn = 0
        private set

    /**
     * Deterministic work counts of the last decode, so a test can bound a key where time would not hold
     * still. Each guards one saving: [steps] incremental collecting, [scored] the search, [firstScored]
     * the kept first-word scores; [arcs] is the size of what they work on.
     */
    class Work internal constructor(
        /** Including those kept from the last decode. */
        val arcs: Int,
        /** Trie steps taken anew, one syllable each. */
        val steps: Int,
        /** Words scored after a sequence in the search. */
        val scored: Int,
        /** Arcs from the start scored anew for the first words, not kept. */
        val firstScored: Int,
    )

    val work: Work get() = Work(arcs.size, steps, scored, firstAnew)

    private var steps = 0
    private var scored = 0
    private var firstAnew = 0

    // per position collected: its steps onto [crossFrom] or past, for the next decode to continue; and where
    // imported-only words end from, of those collectStart adds
    private val crossings = Crossings()
    private var crossFrom = Int.MAX_VALUE
    private var startFrom = 0

    // words a pass over the arcs has taken, each once ([startWords], [arcsBetween], [rankedWords])
    private val seen = SeenWords()
    private var lastStart = 0
    private var lastPrev2 = NO_WORD
    private var lastPrev = NO_WORD

    // firstWords' scores for the arcs from the start, for the next decode to keep shared arcs'; count valid,
    // 0 until firstWords is done
    private var firstScores = FloatArray(0)
    private var firstScored = 0

    // those arcs as firstWords ranked them, of rankedGraph (null until done): further words read with no decode
    private var firstRanked = LongArray(0)
    private var rankedGraph: SyllableGraph? = null

    // per position up to [beam] sequences, best first; a sequence is its last word and a link to its
    // predecessor's slot, fixed once its position is searched
    private var beamSize = IntArray(0)
    private var stateScore = FloatArray(0)
    private var stateWord = IntArray(0)
    private var statePrev = IntArray(0)
    private var stateBack = IntArray(0) // -1 for the empty sequence at the start
    // the path collect is on
    private val path = Path()

    private val contexts = LongArray(beam) // of the beam being extended, from scorer
    private val runs = LongArray(beam) // the scorer's trigrams for each of contexts

    /**
     * @param prev2 the word before [prev] in text already committed, or [NO_WORD]
     * @param prev the last word committed, or [NO_WORD]
     * @param sentences how many readings of the whole input to return at most
     * @param words how many starting words to return at most; where the graph reads 双拼 keys alone and [words]
     * reaches [DEFAULT_WORDS], up to [TYPED_SLOTS] more: the user's typed words so read, right after the first
     * [DEFAULT_WORDS] (rankedWords)
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
            // nothing done yet: [work] is not the last decode's
            steps = 0
            scored = 0
            firstAnew = 0
            return Decoding(emptyList(), emptyList())
        }
        // the last decode is built on only in the same context: see search and firstWords
        val last = if (graph.start == lastStart && prev2 == lastPrev2 && prev == lastPrev) lastEnd else -1
        val firstKept = if (last >= 0) firstScored else 0
        // until the search is done: a throw would leave beams and arcs matching nothing
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
     * Forgets the last decode so the next starts from scratch: needed when [scorer]'s scores or [user]'s
     * words change (owned, added, blocked, imported). Arcs carried key to key hold the user's words as they
     * were: a missed reset shows a removed or blocked word, or hides a new one, till the input restarts.
     */
    fun reset() {
        lastEnd = -1
        builtFrom = null
    }

    /**
     * The first [words] starting words as [decode] gives them, where it gave fewer: continued from its
     * ranking if the last decode was of [graph] in this context, else decoded again. Plus up to
     * [TYPED_SLOTS] as [decode]; a shorter list is a prefix of a longer one.
     */
    fun words(graph: SyllableGraph, prev2: Int = NO_WORD, prev: Int = NO_WORD, words: Int): List<Candidate> {
        val ranked = graph === rankedGraph && lastEnd == graph.end && prev2 == lastPrev2 && prev == lastPrev
        return if (ranked) rankedWords(graph, words) else decode(graph, prev2, prev, words = words).words
    }

    // arc [a] is a word ending at [to], not over a slipped key where [holdsSlips]
    private fun takes(a: Int, to: Int, holdsSlips: Boolean): Boolean =
        arcs.to[a] == to && arcs.word[a] != NO_WORD && !(holdsSlips && arcs.flags[a] and SLIPPED != 0)

    private fun buildLattice(graph: SyllableGraph) {
        // enter and leave pair up; a decode thrown out halfway leaves no path behind
        path.clear()
        arcs.mark(path)
        val n = graph.end
        // arcs from a position whose collecting read only edges before the change are the last decode's: kept,
        // so a key at the end of long input collects from its last syllables only (in 双拼 every key may be
        // read alone, so collecting everything grew with input length). For a position whose collecting read
        // past the change, arcs ending before it are kept and only the steps that crossed it are redone (collectOn)
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
        // steps onto the last key and past it are kept: a following key changes the graph there (a lone 双拼 key
        // at the end becomes the first of a pair), and collectOn redoes only those. Not while a tone is typed:
        // a word's tones are asked of the whole path to it
        crossFrom = if (toneAllows == null) graph.end - 1 else Int.MAX_VALUE
        startFrom = 0
        crossings.clear()
        collect(graph, at, dictionary.root, user?.root ?: -1, user?.mergedWords?.root ?: -1, 0f, false, limitFrom(graph, at))
        finish(graph, at)
    }

    private fun limitFrom(graph: SyllableGraph, at: Int) = if (at == graph.start) Int.MAX_VALUE else wordsPerReading

    // what collecting from [at] ends with, anew or continuing
    private fun finish(graph: SyllableGraph, at: Int) {
        // nothing in the dictionary starts here: keep what was typed so every path goes on. Asked before the
        // imported-only words, from which no path goes on (and when continuing, one ending before the change is
        // among the arcs already)
        var raw = true
        for (a in arcs.start[at] until arcs.size) if (arcs.flags[a] and START_ONLY == 0) raw = false
        if (raw) {
            for (e in graph.edges(at)) arcs.add(graph.to(e), NO_WORD, penalties.raw)
        }
        if (at == graph.start) user?.startWords?.let { collectStart(graph, at, it, it.root, 0f, false) }
        arcs.sortByEnd(arcs.start[at], graph.end)
        arcs.reached(at, reachRead, reachTo)
        // input kept as typed exists for want of any word: gone when one is found, so never built on
        arcs.crossed(at, if (raw) Int.MAX_VALUE else crossFrom, crossings)
    }

    /**
     * The arcs from [at] where the last decode's collecting read past [changed], the first differing
     * position: its arcs ending before [changed] are kept, and its steps onto [changed] or past are redone.
     * Steps are taken in collecting's order, so arcs come out as [collectFrom]'s would (tests compare
     * them), except one order nothing reads: input kept as typed ([finish]) beside a kept start-only word
     * puts that word first here, after in [collectFrom]; the next decode's Lattice.same then rescores its first words.
     */
    private fun collectOn(graph: SyllableGraph, at: Int, changed: Int) {
        check(toneAllows == null) { "a word's tones are asked of the whole path to it" }
        // what it read then, some no longer read: only further than now, which keeps nothing wrongly
        reachRead = lastArcs.readFrom(at)
        reachTo = lastArcs.readTo(at)
        collectedOn++
        arcs.copyEndingBefore(lastArcs, at, changed)
        crossFrom = changed
        startFrom = changed
        crossings.clear()
        val limit = limitFrom(graph, at)
        lastArcs.forEachCrossing(at) { from, edge, match, node, userNode, mergedNode, cost, short, abbreviated ->
            // one from [changed] or past is of a walk a step before it redoes
            val e = graph.edges(from).first + edge
            if (from < changed && graph.to(e) >= changed) {
                read(graph, from, e)
                path.counted(abbreviated)
                arcs.mark(path)
                val child = if (node < 0) -1 else dictionary.child(node, graph.matches(e).syllable(match))
                step(graph, from, e, match, child, node, userNode, mergedNode, cost, short, limit)
            }
        }
        path.counted(0)
        arcs.mark(path)
        finish(graph, at)
    }

    /**
     * Adds the words of every path from [node] (dictionary), [userNode] (user words) and [mergedNode]
     * (merged dictionaries) that the graph spells from [at]; -1 for a trie the path has left. [short]: a
     * syllable on the path is typed short (简拼), scored by [uncounted].
     */
    private fun collect(graph: SyllableGraph, at: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, limit: Int) {
        for (e in graph.edges(at)) {
            read(graph, at, e)
            if (graph.kind(e) == Kind.RAW) continue
            // where every match reads only the user's typed words (a key alone here or before), walk the dictionary
            // children with one below instead of searching each of a score of matches
            val typedOnly = walksTyped && (path.alone > 0 || graph.isAbbreviation(e))
            // on one line: the class is at detekt's LargeClass bound
            if (node >= 0 && mergedNode < 0 && typedOnly) collectTyped(graph, at, e, node, userNode, cost, short, limit) else collectMatches(graph, at, e, node, userNode, mergedNode, cost, short, limit)
        }
    }

    /** [collect]'s steps over edge [e] from [node], each match looked up or walked along. */
    private fun collectMatches(graph: SyllableGraph, at: Int, e: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, limit: Int) {
        val matches = graph.matches(e)
        // a lone key stands for a score of syllables and most non-root nodes have few children: walk both in
        // syllable order once instead of searching the children for each match
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

    /**
     * [collect]'s steps over edge [e] from [node] where only user-typed words are read ([UserWords.typed])
     * and no merged dictionary is on the path: matches whose child has a typed word below
     * ([UserWords.nextTypedBelow], in syllable order like the matches); the rest step onto no node, except
     * where the user's own trie has children at [userNode]. The same steps in the same order as [collect]'s,
     * without searching a lone key's 20-60 matches one by one.
     */
    private fun collectTyped(graph: SyllableGraph, at: Int, e: Int, node: Int, userNode: Int, cost: Float, short: Boolean, limit: Int) {
        val matches = graph.matches(e)
        // no user, no typed word
        val user = user ?: return
        if (userNode >= 0 && user.childCount(userNode) > 0) {
            for (i in 0 until matches.size) step(graph, at, e, i, dictionary.child(node, matches.syllable(i)), node, userNode, -1, cost, short, limit)
            return
        }
        forEachTypedChild(dictionary, user, node, matches) { i, c -> step(graph, at, e, i, c, node, userNode, -1, cost, short, limit) }
    }

    /** One [collect] step from [at] over match [i] of edge [e] to dictionary [child] (-1 for none): its words, and the walk on. */
    private fun step(graph: SyllableGraph, at: Int, e: Int, i: Int, child: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, limit: Int) {
        val merged = user?.mergedWords
        val matches = graph.matches(e)
        val syllable = matches.syllable(i)
        // over a key read alone (here or earlier), only user-typed or chosen words (UserWords.typed) and the nodes
        // to them: a lone key stands for a score of syllables. A slip is not alone (Path.alone): it reads everything
        val typedOnly = path.alone > 0 || matches.flags(i) and SyllableMatches.ABBREV != 0
        @Suppress("NAME_SHADOWING")
        val child = if (typedOnly) typedChild(child) else child
        val userChild = if (userNode < 0 || user == null) -1 else user.child(userNode, syllable)
        val mergedChild = if (mergedNode < 0 || merged == null) -1 else merged.child(mergedNode, syllable).let { if (typedOnly) typedMergedChild(it) else it }
        if (child < 0 && userChild < 0 && mergedChild < 0) return
        steps++
        val kind = graph.kind(e)
        val to = graph.to(e)
        if (to >= crossFrom) crossings.add(at, e - graph.edges(at).first, i, node, userNode, mergedNode, cost, short, path.counts())
        val edgeShort = short || isShort(kind)
        val tone = toneOf(graph, e)
        val c = cost + penalties.ofKind(kind) + penalties.ofMatch(matches.flags(i)) + nasal(kind, syllable) + penalties.tap * graph.against(e, i) + graph.slipCost(e, i)
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

    // what collecting from a position read of the graph: see Lattice.readBefore
    private fun read(graph: SyllableGraph, at: Int, edge: Int) {
        if (at > reachRead) reachRead = at
        graph.to(edge).let { if (it > reachTo) reachTo = it }
    }

    // user words at [userNode] and merged ones at [mergedNode] (-1 for none); whether longer ones go on
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
     * Adds imported-only words along every path from [node] of [starts] spelt from [at], the input's start
     * only, marked so the search never continues from them: never mid-sentence. Those since made the user's
     * are in the user trie and skipped here, as are blocked ones.
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
                val c = cost + penalties.ofKind(kind) + penalties.ofMatch(matches.flags(i)) + nasal(kind, matches.syllable(i)) + penalties.tap * graph.against(e, i) + graph.slipCost(e, i)
                abbreviated(matches.flags(i), 1)
                for (w in 0 until starts.wordCount(child)) {
                    val word = starts.word(child, w)
                    // when continuing (collectOn), those ending before the restart point are there already
                    if (to >= startFrom && startOffered(user, word)) arcs.add(to, word, c + penalties.imported, short = edgeShort, startOnly = true)
                }
                if (to < graph.end && starts.childCount(child) > 0) collectStart(graph, to, starts, child, c, edgeShort)
                abbreviated(matches.flags(i), -1)
            }
        }
    }

    // whether imported-only [word] is offered from the start on the current path
    private fun startOffered(user: UserWords, word: Int) = user.startOnly(word) && !user.blocked(word, -1) && typedOver(word)

    // the first [words] at [node], blocked ones left out
    private fun addWords(to: Int, node: Int, words: Int, cost: Float, short: Boolean) {
        val first = dictionary.wordsOf(node)
        for (at in first until first + words) {
            val word = dictionary.wordAt(at)
            if (user?.blocked(word, node) != true && typedOver(word)) arcs.add(to, word, cost + dictionary.weightAt(at) + coarseness(word) + toneCost(word), short = short)
        }
    }

    // [child] if a typed or chosen word is at or below it, else -1 (no user: none)
    private fun typedChild(child: Int) = if (child >= 0 && user?.typedBelow(child) == true) child else -1

    private fun typedMergedChild(child: Int) = if (child >= 0 && user?.mergedTypedBelow(child) == true) child else -1

    // whether [word] is offered on the current path: over a lone key, only typed or chosen words
    private fun typedOver(word: Int) = path.alone == 0 || user?.typed(word) == true

    private fun coarseness(word: Int) = if (coarse.unasked(word, user, vocabulary.size)) CoarseWords.PENALTY else 0f

    // one reading each, so no weight; few enough to take them all
    private fun addUserWords(to: Int, user: UserWords, node: Int, cost: Float, short: Boolean) {
        for (w in 0 until user.wordCount(node)) {
            val word = user.word(node, w)
            if (!user.blocked(word, -1) && typedOver(word)) arcs.add(to, word, cost + coarseness(word) + toneCost(word), short = short)
        }
    }

    // the tone typed after [edge]'s syllable, 0 where none is asked
    private fun toneOf(graph: SyllableGraph, edge: Int) = if (toneAllows == null) 0 else graph.tone(edge)

    // pushes [syllable] and its typed [tone]; [flags] count arcs over a lone or slipped key, for the typed
    // words' slots (rankedWords) and slip holding (startWords, arcsBetween)
    private fun enter(syllable: Int, tone: Int, flags: Int) {
        path.enter(syllable, tone, flags)
        arcs.mark(path)
    }

    private fun leave(tone: Int, flags: Int) {
        path.leave(tone, flags)
        arcs.mark(path)
    }

    private fun abbreviated(flags: Int, by: Int) {
        path.count(flags, by)
        arcs.mark(path)
    }

    // [Penalties.tone] where a tone typed on the path is one a character of [word] lacks; read in place,
    // no String: runs on every arc while a tone is typed
    private fun toneCost(word: Int): Float {
        val allows = toneAllows
        if (allows == null || path.toned == 0) return 0f
        if (word < vocabulary.size) return path.toneCost(vocabulary.length(word), allows, penalties.tone) { vocabulary.char(word, it) }
        val text = user?.text(word) ?: return 0f
        return path.toneCost(text.length, allows, penalties.tone) { text[it] }
    }

    // the first [words] of a merged dictionary's at [node], best first, as many as the dictionary's own:
    // rime-frost has hundreds per syllable, each an arc scored on every key
    private fun addMergedWords(to: Int, user: UserWords, merged: StartWords, node: Int, words: Int, cost: Float, short: Boolean) {
        for (w in 0 until words) {
            val word = merged.word(node, w)
            if (!user.blocked(word, -1) && typedOver(word)) arcs.add(to, word, cost + coarseness(word) + toneCost(word), short = short)
        }
        // past the limit, still the user's chosen ones: a word they added, low in its dictionary
        if (words < merged.wordCount(node) && user.anyMergedChosen()) {
            for (w in words until merged.wordCount(node)) {
                val word = merged.word(node, w)
                if (user.chosen(word) && !user.blocked(word, -1)) arcs.add(to, word, cost + coarseness(word) + toneCost(word), short = short)
            }
        }
    }

    /**
     * The last decode's words from the start of [graph] to [to] that [take] accepts, each once, scored by
     * arc cost alone: for injecting user words a list may lack. Every word from the start is an arc, with
     * no limit. [holdsSlips]: none over a slipped key, for a code that [SyllableGraph.readsWhole] where the
     * graph has slips (HabitOrder); a lone key is no slip.
     */
    fun startWords(graph: SyllableGraph, to: Int, take: IntPredicate, holdsSlips: Boolean = false): List<Candidate> {
        if (lastEnd != graph.end || lastStart != graph.start) return emptyList()
        val out = ArrayList<Candidate>()
        seen.clear()
        for (a in arcs.start[graph.start] until arcs.start[graph.start + 1]) {
            val word = arcs.word[a]
            if (!takes(a, to, holdsSlips)) continue
            if (take.test(word) && seen.add(word)) out += Candidate(text(word), to, arcs.cost[a], intArrayOf(word), intArrayOf(to))
        }
        return out
    }

    /** Up to [limit] of the last decode's sentences, each text once: more than [decode] gave, so substituted words still leave enough. Backtracking only. */
    fun moreSentences(graph: SyllableGraph, limit: Int = beam): List<Candidate> =
        if (lastEnd != graph.end || lastStart != graph.start) emptyList() else sentences(graph, limit)

    /**
     * The last decode's words from [from] to [to], each once, as [startWords]: for an added word being the
     * only one. Read off the lattice, no search. [holdsSlips] as for [startWords]; [holdsAlone]: none over a
     * 双拼 key read alone where the keys read whole, else a typed word read so would displace the added one.
     */
    fun arcsBetween(graph: SyllableGraph, from: Int, to: Int, holdsSlips: Boolean = false, holdsAlone: Boolean = false): List<Candidate> {
        if (from !in graph.start until graph.end || lastEnd != graph.end || lastStart != graph.start) return emptyList()
        val out = ArrayList<Candidate>()
        seen.clear()
        for (a in arcs.start[from] until arcs.start[from + 1]) {
            val word = arcs.word[a]
            if (!takes(a, to, holdsSlips) || holdsAlone && arcs.flags[a] and ALONE != 0) continue
            if (seen.add(word)) out += Candidate(text(word), to - from, arcs.cost[a], intArrayOf(word), intArrayOf(to))
        }
        return out
    }

    /**
     * Rescores words at [node], reached by a partial syllable, as the likeliest longer word each starts:
     * `yibeik` is 一杯 + the start of 咖啡, likelier than 一杯可 though 咖 alone is rare. The text stays
     * as typed; the beam keeps the better score.
     */
    private fun lookAhead(kind: Kind, to: Int, node: Int, cost: Float, words: Int, short: Boolean) {
        // not over a lone key: a longer word never typed would raise the typed one that starts it
        if (path.alone > 0) return
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

    /** Extends sequences ending at [at] by arcs from there ending at [from] or later; one ending at [wholeEnd] reads all the input, scored by [whole] if given. */
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
                // once per state, not per arc: most of a word's scoring cost. Arcs only go forward, so this beam is stable
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

    // no user counts over a short syllable, or (MULTI) when reading all the input alone
    private fun scoring(a: Int, wholeInput: Boolean): WordScorer = when {
        arcs.flags[a] and SHORT != 0 -> uncounted ?: scorer
        wholeInput -> whole ?: scorer
        else -> scorer
    }

    /**
     * The first position whose beam may differ from the last decode's, or [SyllableGraph.start]. A beam
     * depends only on the arcs ending there, in order, and the beams they start from. Typed text is not
     * part of it: text kept as typed is read from the input only when candidates are made.
     */
    private fun unchangedUntil(graph: SyllableGraph, last: Int): Int =
        maxOf(arcs.sameUntil(lastArcs, graph.start, minOf(last, graph.end)), graph.start)

    /** Unless the beam is full of better ones. */
    private fun insert(at: Int, score: Float, word: Int, prev: Int, back: Int) {
        val offset = at * beam
        var size = beamSize[at]
        // most offers to a full beam fall below its last, and so below the same word from the same state:
        // rejected before looking for that
        if (size == beam && score <= stateScore[offset + beam - 1]) return
        // the same word from the same state by another reading of the same input: keep the best
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
     * Starting words, each once, best first; with [wholeFirst], whole-input ones first. An initial may start
     * thousands (`s` is every sh and s syllable), so text is made only for those returned. Arcs from the
     * start as in the last decode, of the [kept] it scored, keep their scores: a key changes arcs near the end.
     */
    private fun firstWords(graph: SyllableGraph, prev2: Int, prev: Int, limit: Int, kept: Int, last: Int): List<Candidate> {
        val from = arcs.start[graph.start]
        val until = arcs.start[graph.start + 1]
        val context = scorer.context(prev2, prev)
        val trigrams = scorer.trigrams(context)
        // the arcs from the start are the last decode's and none reads to the end then or now: its scores and
        // order stand (thousands of arcs per 双拼 key at the end of long input)
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
            // with [whole], an arc reading all the input then or now is rescored
            val sameUntil = if (whole == null) Int.MAX_VALUE else minOf(last, graph.end)
            while (same < minOf(kept, until - from) && lastArcs.same(lastFrom + same, arcs, from + same) && arcs.to[from + same] < sameUntil) same++
        }
        // score then arc in one sortable long: best first, ties in arc order
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
        // whole-input words first, then the rest by score: by score alone common characters of a part (几 家 加
        // of jiao) buried a rarer whole one (觉). Not longest first: a word picked piece by piece is mostly short
        firstRanked = if (!wholeFirst) order else LongArray(order.size).also { ranked ->
            var at = 0
            for (k in order) if (arcs.to[k.toInt()] == graph.end) ranked[at++] = k
            for (k in order) if (arcs.to[k.toInt()] != graph.end) ranked[at++] = k
        }
        rankedGraph = graph
        return rankedWords(graph, limit)
    }

    // whether arcs [from, until) from the start are the [kept] the last decode ranked, none reading to the end then or now
    private fun firstUntouched(graph: SyllableGraph, from: Int, until: Int, kept: Int, last: Int): Boolean =
        kept == until - from && firstRanked.size == kept && arcs.copiedAt(graph.start) && lastArcs.start[graph.start] == from &&
            (kept == 0 || arcs.to[until - 1] < minOf(last, graph.end))

    /**
     * The first [limit] words, each once, of the start arcs as [firstWords] ranked them. Where the graph
     * reads keys alone and [limit] reaches [DEFAULT_WORDS], up to [TYPED_SLOTS] of the user's typed whole-input,
     * slip-free words go right after the first [DEFAULT_WORDS]: on grouped keyboards a code of initials reads
     * hundreds of syllables' words that crowd out the meant one. There whatever [limit]: after the [limit]th,
     * a longer list read on (PinyinSession.readUntil) would move them.
     */
    private fun rankedWords(graph: SyllableGraph, limit: Int): List<Candidate> {
        val from = arcs.start[graph.start]
        var next = 0
        var kept = 0
        val out = ArrayList<Candidate>()
        seen.clear()
        fun rankTo(n: Int) {
            while (out.size - kept < n && next < firstRanked.size) {
                val a = firstRanked[next++].toInt()
                val word = arcs.word[a]
                if (word != NO_WORD && seen.add(word)) {
                    out += Candidate(text(word), arcs.to[a], firstScores[a - from], intArrayOf(word), intArrayOf(arcs.to[a]))
                }
            }
        }
        rankTo(minOf(limit, DEFAULT_WORDS))
        if (graph.hasAbbreviations && limit >= DEFAULT_WORDS) {
            // past the ranked words, which rankTo continues: those taken here are seen and skipped there
            var at = next
            while (kept < TYPED_SLOTS && at < firstRanked.size) {
                val a = firstRanked[at++].toInt()
                val to = arcs.to[a]
                // with [wholeFirst] whole-input words come first: stop at the first that is not. Grouped keyboards (not
                // wholeFirst) check every arc left: its end and flags each
                if (wholeFirst && to != graph.end) break
                val word = arcs.word[a]
                val alone = word != NO_WORD && to == graph.end && arcs.flags[a] and (ALONE or SLIPPED) == ALONE
                if (alone && user?.typed(word) == true && seen.add(word)) {
                    out += Candidate(text(word), to, firstScores[a - from], intArrayOf(word), intArrayOf(to))
                    kept++
                }
            }
        }
        rankTo(limit)
        return out
    }

    /**
     * The path [collect] is on: syllables and tones typed after them (0 none), [length] and [toned], and how
     * many a lone 双拼 key read as an initial ([SyllableMatches.ABBREV]) or a key read as its neighbour
     * ([SyllableMatches.NEIGHBOUR]): neither is the input as typed. [alone] counts the first kind only, over
     * which only user-typed words are read ([UserWords.typed]).
     */
    private class Path {
        var syllables = IntArray(PATH)
        var tones = IntArray(PATH)
        var length = 0
        var toned = 0
        var abbreviated = 0
            private set
        var alone = 0
            private set

        fun clear() {
            length = 0
            toned = 0
            counted(0)
        }

        /** Both counts as one number, for [counted] to restore. */
        fun counts() = abbreviated or (alone shl ALONE_SHIFT)

        fun counted(counts: Int) {
            abbreviated = counts and (1 shl ALONE_SHIFT) - 1
            alone = counts ushr ALONE_SHIFT
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
            if (flags and (SyllableMatches.ABBREV or SyllableMatches.NEIGHBOUR) != 0) abbreviated += by
            if (flags and SyllableMatches.ABBREV != 0) alone += by
        }

        /** [penalty] where a tone typed on the path is one a character of the word ([chars] long, [charAt]) lacks by [allows]. */
        inline fun toneCost(chars: Int, allows: (codePoint: Int, syllable: Int, tone: Int) -> Boolean, penalty: Float, charAt: (Int) -> Char): Float {
            // one code point per syllable, or no telling which character a tone is of
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
     * Arcs grouped by start (`start[at] until start[at + 1]`), each group in end order. That puts what a key
     * changes, arcs ending near the input's end, last in each group; in dictionary order a change deep in a
     * long word would come before the short arcs and hide that they are the same.
     */
    private class Lattice {
        var start = IntArray(0)
        val to = IntList()
        val word = IntList() // NO_WORD for input kept as typed
        val scored = IntList() // the word the model scores: word, or a longer one it starts
        val cost = FloatList()
        val flags = IntList() // SHORT: over a syllable typed short (no user counts); START_ONLY: a start-only word;
        // ALONE: over a lone 双拼 key read as an initial; SLIPPED: over a key read as its neighbour
        val size get() = to.size

        // per position: the furthest position collecting read edges of, and the furthest those went
        private var reachRead = IntArray(0)
        private var reachTo = IntArray(0)
        // per position: its arcs were copied from the last decode, so equal without comparing
        private var copied = BooleanArray(0)

        // per position: its collecting's steps onto its [crossFrom] or past, in order
        private var crossings = arrayOfNulls<Crossings>(0)
        private var crossFrom = IntArray(0)

        fun copiedAt(at: Int) = copied[at]

        /** Of the arcs from [from] on, the first end before [end] that differs from [last]; [end] for none. */
        fun sameUntil(last: Lattice, from: Int, end: Int): Int {
            var until = end
            var at = from
            while (at < until) {
                // copied from the last decode: the same
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
                // the groups differ from here; sorted by end, every arc ending before the first of these ends is in the same place in both
                for (i in a until aEnd) until = minOf(until, last.to[i])
                for (i in b until bEnd) until = minOf(until, to[i])
                at++
            }
            return until
        }

        // whether arcs added now are over a mid-input 简拼 or a slip, as the decoder's path is
        private var alone = false
        private var slipped = false

        /** Marks arcs added from now on as [path] is: over a lone key, over a slip. */
        fun mark(path: Path) {
            alone = path.alone > 0
            slipped = path.abbreviated > path.alone
        }

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

        /** Stably orders the arcs from [first] on, none ending past [end], by end. */
        fun sortByEnd(first: Int, end: Int) {
            var sorted = true
            for (a in first + 1 until size) {
                if (to[a] < to[a - 1]) {
                    sorted = false
                    break
                }
            }
            if (sorted) return
            // counting sort: ends[q] becomes where the arcs ending at q go
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
            this.flags += (if (short) SHORT else 0) or (if (startOnly) START_ONLY else 0) or (if (alone) ALONE else 0) or (if (slipped) SLIPPED else 0)
        }

        fun reached(at: Int, read: Int, to: Int) {
            reachRead[at] = read
            reachTo[at] = to
            copied[at] = false
        }

        /**
         * Whether the arcs from [at] were collected reading only edges before [changed], the first position
         * where old and new graph differ: then the new one has them too. An edge to [changed] counts only if
         * it is the end of both graphs ([wasEnd], [isEnd]) or of neither: a partial syllable reads on at the end.
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

        /** Adds [other]'s arcs from [at] ending before [end]: a prefix, as they are in end order. */
        fun copyEndingBefore(other: Lattice, at: Int, end: Int) {
            val from = other.start[at]
            val until = other.firstEndingAt(at, end)
            to.addAll(other.to, from, until)
            word.addAll(other.word, from, until)
            scored.addAll(other.scored, from, until)
            cost.addAll(other.cost, from, until)
            flags.addAll(other.flags, from, until)
        }

        /** The first arc from [at] ending at [end] or past. */
        fun firstEndingAt(at: Int, end: Int): Int {
            var lo = start[at]
            var hi = start[at + 1]
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (to[mid] < end) lo = mid + 1 else hi = mid
            }
            return lo
        }

        fun readFrom(at: Int) = reachRead[at]
        fun readTo(at: Int) = reachTo[at]

        /** Keeps a copy of the steps from [at] onto [from] or past (the caller reuses [steps]). */
        fun crossed(at: Int, from: Int, steps: Crossings) {
            crossFrom[at] = from
            crossings[at] = if (from == Int.MAX_VALUE) null else steps.copy()
        }

        /** Collecting can continue from [at]'s kept steps after a change at [changed]: same edges, every step onto [changed] or past kept. */
        fun goesOnFrom(at: Int, changed: Int): Boolean = at < changed && crossings[at] != null && crossFrom[at] <= changed

        /** In the order taken. */
        inline fun forEachCrossing(at: Int, visit: (from: Int, edge: Int, match: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, abbreviated: Int) -> Unit) {
            val c = crossings[at] ?: return
            for (k in 0 until c.size) c.read(k, visit)
        }

        fun same(arc: Int, other: Lattice, otherArc: Int) =
            to[arc] == other.to[otherArc] && word[arc] == other.word[otherArc] && scored[arc] == other.scored[otherArc] &&
                cost[arc] == other.cost[otherArc] && flags[arc] == other.flags[otherArc]
    }

    /**
     * [collect] steps, each [step]'s arguments: position, edge (from the position's first), match, the three
     * tries' nodes, cost so far, whether an earlier syllable was short, and [Path.counts].
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
        /** See [Penalties.nasal]. */
        private val NASALS = setOf("m", "n", "ng").map { Syllables.id(it) }.toIntArray()

        const val DEFAULT_BEAM = 16

        // initial room for the path's syllables
        private const val PATH = 16
        // children walked, not searched, up to this many per match: a search reads about log2 of them; Reading's walk the same
        internal const val WALKED_PER_MATCH = 2
        const val DEFAULT_WORDS = 64
        // user-typed lone-key words kept past the words asked for (rankedWords); 8 till measured on the 6T
        internal const val TYPED_SLOTS = 8
        // 16 words are as accurate as 256 at a third of the time; a beam of 64 gains little at four times the time
        const val DEFAULT_WORDS_PER_READING = 16
        const val DEFAULT_SENTENCES = 5

        // Lattice.flags bits
        private const val SHORT = 1
        private const val START_ONLY = 2
        private const val ALONE = 4
        private const val SLIPPED = 8

        // Path.counts: lone-key count above this bit; paths are far shorter than 2^15 keys
        private const val ALONE_SHIFT = 15
    }
}

/**
 * Input read as [text] from the start up to [end]; [words] are vocabulary ids ([NO_WORD] for text kept
 * as typed), [ends] where each ends in the input. A prediction reads no input: its ends are 0.
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

/** [sentences] read the whole input; [words] are every word it may start with, for piece-by-piece picking. Best first. */
class Decoding internal constructor(val sentences: List<Candidate>, val words: List<Candidate>)

/** Bits that order as the floats do. */
private fun sortable(value: Float): Int {
    val bits = java.lang.Float.floatToIntBits(value)
    return if (bits < 0) bits xor Int.MAX_VALUE else bits
}

// words at a node a longer word is looked for, and taken per longer reading (1-16 read the same)
private const val LOOK_AHEAD_WORDS = 16
private const val EXTENSIONS_PER_READING = 4

/**
 * Per first [LOOK_AHEAD_WORDS] words at [node], the likeliest word a syllable longer starting with it,
 * or [NO_WORD] if none beats the word itself. Once per node, words alone: too many pairs for a key.
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

/**
 * Each child of [node] with a [user]-typed word below ([UserWords.nextTypedBelow], syllable order) that
 * one of [matches] reads, with that match's index: PinyinDecoder.collectTyped's walk.
 */
private inline fun forEachTypedChild(dictionary: PinyinDictionary, user: UserWords, node: Int, matches: SyllableMatches, visit: (match: Int, child: Int) -> Unit) {
    val first = dictionary.firstChild(node)
    val end = first + dictionary.childCount(node)
    var c = user.nextTypedBelow(first)
    while (c in first until end) {
        val i = matches.indexOf(dictionary.syllable(c))
        if (i >= 0) visit(i, c)
        c = user.nextTypedBelow(c + 1)
    }
}

private fun Penalties.ofKind(kind: Kind) = when (kind) {
    Kind.SYLLABLE -> 0f
    Kind.INITIAL -> initial
    Kind.PARTIAL -> partial
    Kind.EXTENDED -> extended
    Kind.RAW -> raw
    Kind.LETTER -> letter
}

// by a match's flags (SyllableMatches)
private fun Penalties.ofMatch(flags: Int): Float {
    var p = 0f
    if (flags and SyllableMatches.FUZZY != 0) p += fuzzy
    if (flags and SyllableMatches.TYPO != 0) p += typo
    if (flags and SyllableMatches.NEIGHBOUR != 0) p += neighbour
    if (flags and SyllableMatches.ABBREV != 0) p += abbrev
    return p
}

// an initial, a partial, or a whole syllable read as the start of a longer one
private fun isShort(kind: Kind) = kind == Kind.INITIAL || kind == Kind.PARTIAL || kind == Kind.EXTENDED
