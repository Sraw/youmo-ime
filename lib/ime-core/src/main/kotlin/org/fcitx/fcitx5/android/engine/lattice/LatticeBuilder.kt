/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.lattice.Lattice.Companion.START_ONLY
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder.Companion.WALKED_PER_MATCH
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * [PinyinDecoder]'s lattice: every dictionary, user and merged word along every path of a [SyllableGraph] as an
 * arc, built on the last graph's where the new one starts the same (see [build]). Over a 双拼 key read alone only
 * the user's typed words are read ([collectTyped]). The parameters are [PinyinDecoder]'s.
 */
internal class LatticeBuilder(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val scorer: WordScorer,
    private val penalties: Penalties,
    private val wordsPerReading: Int,
    private val user: UserWords?,
    extensions: () -> Array<IntArray?>,
    private val coarse: CoarseWords,
    private val toneAllows: ((codePoint: Int, syllable: Int, tone: Int) -> Boolean)?,
    private val walksTyped: Boolean,
) {
    var arcs = Lattice()
        private set

    // per dictionary node reached by a partial syllable, see extensionsOf; built once (under a MB) and kept
    // when scores change: it only picks which longer word stands in, scored fresh on each key. Shared
    // when given, by decoders that score alike
    private val extensions: Array<IntArray?> by lazy(LazyThreadSafetyMode.NONE) {
        // checked once here: a wrong size would fail deep in lookAhead past some node
        extensions().also { require(it.size == dictionary.nodeCount) { "extensions for ${it.size} of ${dictionary.nodeCount} nodes" } }
    }

    // the last build's arcs
    var lastArcs = Lattice()
        private set
    // the graph [arcs] came from (null till one is done and after [reset]), with how far collecting from a
    // position read edges and how far they went
    private var builtFrom: SyllableGraph? = null
    private var reachRead = 0
    private var reachTo = 0

    /** Positions the last build collected from anew: [PinyinDecoder.collectedAnew]. */
    var collectedAnew = 0
        private set

    /** Positions it continued from the last build's steps ([collectOn]): [PinyinDecoder.collectedOn]. */
    var collectedOn = 0
        private set

    /** Trie steps the last build took anew: [PinyinDecoder.Work.steps]. */
    var steps = 0
        private set

    // per position collected: its steps onto [crossFrom] or past, for the next build to continue; and where
    // imported-only words end from, of those collectStart adds
    private val crossings = Crossings()
    private var crossFrom = Int.MAX_VALUE
    private var startFrom = 0

    // the path collect is on
    private val path = Path()

    /** Builds the next graph from scratch: see [PinyinDecoder.reset]. */
    fun reset() {
        builtFrom = null
    }

    /** No build done: [steps] is not the last one's. */
    fun clearWork() {
        steps = 0
    }

    /** Makes [arcs] those of [graph], the last ones becoming [lastArcs] and kept where [graph] did not change them. */
    fun build(graph: SyllableGraph) {
        lastArcs = arcs.also { arcs = lastArcs }
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
            // one from [changed] or past is of a walk a step before it redoes, and may lie past a shorter input's end
            if (from >= changed) return@forEachCrossing
            val e = graph.edges(from).first + edge
            if (graph.to(e) >= changed) {
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
     * syllable on the path is typed short (简拼), scored without user counts ([BeamSearch.scoring]).
     */
    private fun collect(graph: SyllableGraph, at: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, limit: Int) {
        for (e in graph.edges(at)) {
            read(graph, at, e)
            if (graph.kind(e) == Kind.RAW) continue
            // where every match reads only the user's typed words (a key alone here or before), walk the dictionary
            // children with one below instead of searching each of a score of matches
            val typedOnly = walksTyped && (path.alone > 0 || graph.isAbbreviation(e))
            if (node >= 0 && mergedNode < 0 && typedOnly) {
                collectTyped(graph, at, e, node, userNode, cost, short, limit)
            } else {
                collectMatches(graph, at, e, node, userNode, mergedNode, cost, short, limit)
            }
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
    // words' slots (PinyinDecoder.rankedWords) and slip holding (its startWords, arcsBetween)
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

    private companion object {
        /** See [Penalties.nasal]. */
        val NASALS = setOf("m", "n", "ng").map { Syllables.id(it) }.toIntArray()
    }
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
 * one of [matches] reads, with that match's index: [LatticeBuilder.collectTyped]'s walk.
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
