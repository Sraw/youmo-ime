/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.StartWords
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * Pinyin glide typing: one word's syllables per glide, walking the dictionary's syllable trie (and the user's
 * beside it), each syllable's keys held to the path by [GlidePath]; a reading scores by its likeliest unblocked
 * word less the fitting cost. One word per glide: chaining made thousands of readings and seconds of search.
 */
class PinyinGlide(
    private val data: PinyinData,
    private val weights: GlidePath.Weights = WEIGHTS,
    private val user: () -> User = { User.NONE },
    penalties: Penalties = Penalties(),
) {
    private val importedPenalty = penalties.imported

    /**
     * The user's part, asked once per glide; [version] changes with anything that changes scores, blocks or
     * additions. Counts only grow between [version] changes, so a score cached before a word's first count is never
     * above its later one.
     */
    class User(
        val version: Any?,
        val unigram: ((word: Int) -> Float)?,
        val words: UserWords?,
        val learned: ((word: Int) -> Boolean)?,
        val blocked: (word: Int, node: Int) -> Boolean,
    ) {
        constructor(version: Any?, unigram: ((word: Int) -> Float)?, blocked: (word: Int, node: Int) -> Boolean) :
            this(version, unigram, null, null, blocked)

        companion object {
            val NONE = User(null, null) { _, _ -> false }
        }
    }

    /** The keys a glide passes for a syllable per scheme; [separated]: syllables typed apart by [SEPARATOR] (xi'an). */
    class Spelling(val separated: Boolean, spell: (Int) -> String?) {
        private val spelt = Array(Syllables.count, spell)

        fun of(syllable: Int): String? = spelt[syllable]

        companion object {
            val FULL = Spelling(true, Syllables::spelling)

            fun shuangpin(scheme: ShuangpinScheme) = Spelling(false, scheme::encode)
        }
    }

    // each node's likeliest uncounted word score as first asked (NaN till then): recomputing all after each
    // learned word was too slow
    private val likeliest = FloatArray(data.dictionary.nodeCount) { Float.NaN }
    private val likeliestWord = IntArray(data.dictionary.nodeCount)

    // nodes with a counted word when [likeliest] was computed: only those are redone each glide
    private val hasLearned = BooleanArray(data.dictionary.nodeCount)

    private var scoredFor: User = User.NONE

    private fun likeliest(node: Int): Float {
        val known = likeliest[node]
        if (!known.isNaN()) return known
        val d = data.dictionary
        var best = Float.NEGATIVE_INFINITY
        val learned = scoredFor.learned
        var counted = false
        for (i in 0 until d.wordCount(node)) {
            val isLearned = learned?.invoke(d.word(node, i)) == true
            if (isLearned) counted = true
            if (isLearned || scoredFor.blocked(d.word(node, i), node)) continue
            val score = score(d.word(node, i)) + d.weight(node, i)
            if (score > best) {
                best = score
                likeliestWord[node] = d.word(node, i)
            }
        }
        likeliest[node] = best
        hasLearned[node] = counted
        return best
    }

    // or its characters' in sequence where likelier, as the session's best path takes them (你好 as 你 then 好)
    private fun score(word: Int): Float {
        val model = data.model
        val own = scoredFor.unigram?.invoke(word) ?: if (word < model.vocabularySize) model.score(word) else model.unknownScore
        val text = text(word)
        if (text.length < 2) return own
        var prev = NO_WORD
        var chars = 0f
        for (c in text) {
            val id = data.wordIndex.find(c.toString())
            chars += if (id == NO_WORD) model.unknownScore else model.score(prev, id)
            prev = id
        }
        return maxOf(own, chars)
    }

    private fun text(word: Int): String =
        if (word < data.vocabulary.size) data.vocabulary.word(word) else checkNotNull(scoredFor.words).text(word)

    var lastSteps = 0
        private set

    fun decode(path: FloatArray, layout: GlideLayout, limit: Int, spelling: Spelling = Spelling.FULL): List<GlideReading> {
        if (limit <= 0) return emptyList()
        val glide = GlidePath(path, layout, weights, MAX_LETTERS)
        if (glide.points == 0) return emptyList()
        val now = user()
        if (now.version != scoredFor.version) likeliest.fill(Float.NaN)
        scoredFor = now
        return Search(glide, spelling, limit).run().also { lastSteps = glide.steps }
    }

    private inner class Search(private val glide: GlidePath, private val spelling: Spelling, private val limit: Int) {
        private val dictionary = data.dictionary
        private val syllables = IntArray(MAX_LETTERS)
        private val words = scoredFor.words
        // words only imported glide too: a glide is a word the input starts with
        private val starts = words?.startWords
        private val merged = words?.mergedWords
        private val learned = scoredFor.learned

        private var picked = NO_WORD

        // ties keep the earlier; keys joined only for those kept
        private val best = ArrayList<Found>()

        private inner class Found(val word: Int, val syllables: IntArray, val score: Float)

        fun run(): List<GlideReading> {
            visit(Nodes(dictionary.root, words?.root ?: -1, starts?.root ?: -1, merged?.root ?: -1), 0, 0)
            return best.map { reading(it) }
        }

        private inner class Nodes(val node: Int, val user: Int, val start: Int, val merged: Int)

        private fun visit(at: Nodes, depth: Int, count: Int) {
            if (at.node >= 0) {
                val first = dictionary.firstChild(at.node)
                for (child in first until first + dictionary.childCount(at.node)) {
                    val s = dictionary.syllable(child)
                    reach(Nodes(child, userChild(at.user, s), startChild(at.start, s), mergedChild(at.merged, s)), s, depth, count)
                }
            }
            if (at.user >= 0 && checkNotNull(words).childCount(at.user) > 0) visitUsers(at, depth, count)
            if (at.start >= 0) visitOthers(at, checkNotNull(starts), at.start, depth, count)
            if (at.merged >= 0) visitOthers(at, checkNotNull(merged), at.merged, depth, count)
        }

        private fun userChild(userNode: Int, s: Int) = if (userNode >= 0) checkNotNull(words).child(userNode, s) else -1

        private fun startChild(startNode: Int, s: Int) = if (startNode >= 0) checkNotNull(starts).child(startNode, s) else -1

        private fun mergedChild(mergedNode: Int, s: Int) = if (mergedNode >= 0) checkNotNull(merged).child(mergedNode, s) else -1

        // the user's trie only tells a child by syllable, and is small
        private fun visitUsers(at: Nodes, depth: Int, count: Int) {
            val own = checkNotNull(words)
            for (s in 0 until Syllables.count) {
                val userChild = own.child(at.user, s)
                if (userChild >= 0 && (at.node < 0 || dictionary.child(at.node, s) < 0)) {
                    reach(Nodes(-1, userChild, startChild(at.start, s), mergedChild(at.merged, s)), s, depth, count)
                }
            }
        }

        private fun visitOthers(at: Nodes, trie: StartWords, trieNode: Int, depth: Int, count: Int) {
            val first = trie.firstChild(trieNode)
            for (child in first until first + trie.childCount(trieNode)) {
                val s = trie.syllable(child)
                val inDictionary = at.node >= 0 && dictionary.child(at.node, s) >= 0
                val inUserTrie = at.user >= 0 && checkNotNull(words).child(at.user, s) >= 0
                val inStarts = trie !== starts && at.start >= 0 && checkNotNull(starts).child(at.start, s) >= 0
                if (!inDictionary && !inUserTrie && !inStarts) {
                    val startNode = if (trie === starts) child else -1
                    reach(Nodes(-1, -1, startNode, mergedChild(at.merged, s)), s, depth, count)
                }
            }
        }

        private fun reach(at: Nodes, syllable: Int, depth: Int, count: Int) {
            val d = stepped(spelling.of(syllable), depth) ?: return
            syllables[count] = syllable
            val any = (at.node >= 0 && dictionary.wordCount(at.node) > 0) ||
                (at.user >= 0 && checkNotNull(words).wordCount(at.user) > 0) ||
                (at.start >= 0 && checkNotNull(starts).wordCount(at.start) > 0) ||
                (at.merged >= 0 && checkNotNull(merged).wordCount(at.merged) > 0)
            if (any) glide.cost(d)?.let { keep(count + 1, pick(at) - it) }
            if (d < MAX_LETTERS) visit(at, d, count + 1)
        }

        private fun pick(at: Nodes): Float {
            val node = at.node
            val userNode = at.user
            val startNode = at.start
            var best = Float.NEGATIVE_INFINITY
            picked = NO_WORD
            if (node >= 0) {
                best = likeliest(node)
                if (best > Float.NEGATIVE_INFINITY) picked = likeliestWord[node]
                if (learned != null && hasLearned[node]) best = pickLearned(node, best)
            }
            if (userNode >= 0) {
                val own = checkNotNull(words)
                for (i in 0 until own.wordCount(userNode)) {
                    val word = own.word(userNode, i)
                    if (scoredFor.blocked(word, -1)) continue
                    val score = score(word)
                    if (score > best) {
                        best = score
                        picked = word
                    }
                }
            }
            if (startNode >= 0) {
                val imported = checkNotNull(starts)
                val own = checkNotNull(words)
                for (i in 0 until imported.wordCount(startNode)) {
                    val word = imported.word(startNode, i)
                    if (!own.startOnly(word) || scoredFor.blocked(word, -1)) continue
                    val score = score(word) + importedPenalty
                    if (score > best) {
                        best = score
                        picked = word
                    }
                }
            }
            if (at.merged >= 0) pickMerged(at.merged, best)?.let { best = it }
            return best
        }

        // words come best first and rime-frost has hundreds per syllable, so the first is the one scored
        private fun pickMerged(mergedNode: Int, above: Float): Float? {
            val trie = checkNotNull(merged)
            val words = scoredFor.words
            val learned = scoredFor.learned
            // the user's chosen or learned words score above their file rank: look past the first
            val chosen = words?.anyMergedChosen() == true || (learned != null && words?.anyMergedCounted() != false)
            var top = above
            var best = NO_WORD
            var seen = false
            for (i in 0 until trie.wordCount(mergedNode)) {
                val word = trie.word(mergedNode, i)
                val theirs = words?.chosen(word) == true || learned?.invoke(word) == true
                if ((!seen || theirs) && !scoredFor.blocked(word, -1)) {
                    val score = score(word)
                    if (score > top) {
                        top = score
                        best = word
                    }
                    seen = true
                    if (!chosen) break
                }
            }
            if (best == NO_WORD) return null
            picked = best
            return top
        }

        private fun pickLearned(node: Int, above: Float): Float {
            val counted = checkNotNull(learned)
            var best = above
            for (i in 0 until dictionary.wordCount(node)) {
                val word = dictionary.word(node, i)
                if (!counted(word) || scoredFor.blocked(word, node)) continue
                val score = score(word) + dictionary.weight(node, i)
                if (score > best) {
                    best = score
                    picked = word
                }
            }
            return best
        }

        private fun keep(count: Int, score: Float) {
            if (score == Float.NEGATIVE_INFINITY) return
            if (best.size == limit && score <= best.last().score) return
            var at = best.size
            while (at > 0 && best[at - 1].score < score) at--
            best.add(at, Found(picked, syllables.copyOf(count), score))
            if (best.size > limit) best.removeAt(best.size - 1)
        }

        private fun stepped(spelling: String?, depth: Int): Int? {
            if (spelling == null) return null
            var d = depth
            for (ch in spelling) {
                if (d >= MAX_LETTERS || !glide.step(d, ch)) return null
                d++
            }
            return d
        }

        private fun reading(found: Found) = GlideReading(
            found.syllables.joinToString(if (spelling.separated) SEPARATOR.toString() else "") { spelling.of(it).orEmpty() },
            text(found.word),
            found.score,
            Syllables.spelled(found.syllables),
        )
    }

    companion object {
        const val MAX_LETTERS = 30

        /** The path weighs more than English's: unigrams are lower in a much larger vocabulary. */
        val WEIGHTS = GlidePath.Weights(shape = 5f, proximity = 6.25f, corner = 1.5f)

        /** The pinyin session reads it as a syllable boundary (SyllableGraph.SEPARATOR). */
        const val SEPARATOR = '\''
    }
}
