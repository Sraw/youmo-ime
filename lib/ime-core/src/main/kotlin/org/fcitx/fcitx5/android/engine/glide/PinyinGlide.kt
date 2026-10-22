/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * Pinyin glide typing (拼音滑行): the words a finger's path across the letters may be, a word's
 * syllables glided in one go. The words are walked as the dictionary's syllable trie, each
 * syllable's letters held to the path by [GlidePath]; each reading with a word is scored by its
 * likeliest word not blocked (its unigram, the user's counts in it ([User]), or its characters'
 * where the model lacks it; a rarer reading's weight added) less what fitting the path cost. The
 * user's own words, in a trie of theirs ([User.words]), are walked alongside: added, they glide too.
 * A word a glide: chained in one, the words' readings ran to thousands and the search to seconds.
 * Each pinyin input method glides its own keys ([Spelling]): full pinyin's and the nine keys' the
 * letters, 双拼's its scheme's two a syllable.
 */
class PinyinGlide(
    private val data: PinyinData,
    private val weights: GlidePath.Weights = WEIGHTS,
    private val user: () -> User = { User.NONE },
    /**
     * The decoder's: a word only imported goes [Penalties.imported] below the dictionary's here as
     * there. Every caller (Engines, ime-eval) takes the default; a seam for one tuned to pass its own.
     */
    penalties: Penalties = Penalties(),
) {
    private val importedPenalty = penalties.imported

    /**
     * The user's part in a glide's words, asked once a glide: [unigram], a word's score with the
     * user's counts and added words in it (the model's own where null); [blocked], a word the user
     * blocked at a dictionary node (-1 for one of [words]), never a reading's word; [words], the
     * words the user added, not the dictionary's; [learned], a word the user's counts are in, its
     * score worked out each glide; [version], what changes the other words' scores or what is
     * blocked or added (those worked out before are then worked out again). Counts only grow
     * between [version]'s changes (forgetting or halving them changes it), so a word's score worked
     * out before its first count is never above its score after.
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

    /**
     * How an input method types a syllable, the keys a glide passes for it: full pinyin's letters
     * ([FULL]), the nine keys' too (on their digits' keys, GlideLayout.sameKey), 双拼's
     * [shuangpin] two. [separated]: a reading's syllables typed apart by [SEPARATOR], as full
     * pinyin's must be (xi'an); 双拼's are two keys each.
     */
    class Spelling(val separated: Boolean, spell: (Int) -> String?) {
        private val spelt = Array(Syllables.count, spell)

        /** [syllable]'s keys; null for none: the scheme cannot type it. */
        fun of(syllable: Int): String? = spelt[syllable]

        companion object {
            val FULL = Spelling(true, Syllables::spelling)

            fun shuangpin(scheme: ShuangpinScheme) = Spelling(false, scheme::encode)
        }
    }

    // each node's likeliest word and its score, as first asked (NaN till then), of the words the user
    // had no count of then: a node's words are in no such order, and a glide after each learned word
    // worked them all out again (F2067)
    private val likeliest = FloatArray(data.dictionary.nodeCount) { Float.NaN }
    private val likeliestWord = IntArray(data.dictionary.nodeCount)

    // whether a node had a word the user has counts of when [likeliest] worked it out (they only
    // get their first count with a new [User.version]): only those nodes are gone through again
    // each glide, not yi's and shi's many words every time
    private val hasLearned = BooleanArray(data.dictionary.nodeCount)

    // the user's part the scores above were worked out with
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

    // the model's, or its characters' one after another where likelier, as the pinyin session's best
    // path takes them: 你好 is likelier as 你 then 好 (-5.2) than as a word (-6.5), the model's
    // text cut 你好 apart mostly
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

    /** The search steps ([GlidePath.steps]) the last decode took: for ime-eval to measure. */
    var lastSteps = 0
        private set

    /** The [limit] likeliest readings of [path] over [layout] as [spelling] types them, best first, each once. */
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
        // the words only imported: a glide is a word the input starts with, so these glide too (U-101)
        private val starts = words?.startWords
        private val learned = scoredFor.learned

        // the word [pick] found
        private var picked = NO_WORD

        // the [limit] best readings with a word so far, best first, as found earlier where as likely:
        // a scribble fits thousands, their keys put together only for those kept
        private val best = ArrayList<Found>()

        private inner class Found(val word: Int, val syllables: IntArray, val score: Float)

        fun run(): List<GlideReading> {
            visit(dictionary.root, words?.root ?: -1, starts?.root ?: -1, 0, 0)
            return best.map { reading(it) }
        }

        // the children of [node], the user's [userNode] and the imported [startNode] (-1 for none of
        // each), [depth] letters and [count] syllables in: a reading of all three is one reading
        private fun visit(node: Int, userNode: Int, startNode: Int, depth: Int, count: Int) {
            if (node >= 0) {
                val first = dictionary.firstChild(node)
                for (child in first until first + dictionary.childCount(node)) {
                    val s = dictionary.syllable(child)
                    reach(child, if (userNode >= 0) checkNotNull(words).child(userNode, s) else -1, startChild(startNode, s), s, depth, count)
                }
            }
            if (userNode >= 0 && checkNotNull(words).childCount(userNode) > 0) visitUsers(node, userNode, startNode, depth, count)
            if (startNode >= 0) visitStarts(node, userNode, startNode, depth, count)
        }

        private fun startChild(startNode: Int, s: Int) = if (startNode >= 0) checkNotNull(starts).child(startNode, s) else -1

        // the user's children of [userNode] the dictionary's [node] has not: the user's trie tells a
        // child by its syllable only, and it is small
        private fun visitUsers(node: Int, userNode: Int, startNode: Int, depth: Int, count: Int) {
            val own = checkNotNull(words)
            for (s in 0 until Syllables.count) {
                val userChild = own.child(userNode, s)
                if (userChild >= 0 && (node < 0 || dictionary.child(node, s) < 0)) reach(-1, userChild, startChild(startNode, s), s, depth, count)
            }
        }

        // the imported children of [startNode] neither the dictionary's [node] nor the user's [userNode] has
        private fun visitStarts(node: Int, userNode: Int, startNode: Int, depth: Int, count: Int) {
            val imported = checkNotNull(starts)
            val first = imported.firstChild(startNode)
            for (child in first until first + imported.childCount(startNode)) {
                val s = imported.syllable(child)
                val inDictionary = node >= 0 && dictionary.child(node, s) >= 0
                val inUserTrie = userNode >= 0 && checkNotNull(words).child(userNode, s) >= 0
                if (!inDictionary && !inUserTrie) reach(-1, -1, child, s, depth, count)
            }
        }

        // [node], [userNode] and [startNode] by [syllable], from [depth] letters and [count] syllables in
        private fun reach(node: Int, userNode: Int, startNode: Int, syllable: Int, depth: Int, count: Int) {
            val d = stepped(spelling.of(syllable), depth) ?: return
            syllables[count] = syllable
            val any = (node >= 0 && dictionary.wordCount(node) > 0) ||
                (userNode >= 0 && checkNotNull(words).wordCount(userNode) > 0) ||
                (startNode >= 0 && checkNotNull(starts).wordCount(startNode) > 0)
            if (any) glide.cost(d)?.let { keep(count + 1, pick(node, userNode, startNode) - it) }
            if (d < MAX_LETTERS) visit(node, userNode, startNode, d, count + 1)
        }

        // the likeliest word not blocked of [node]'s, [userNode]'s and [startNode]'s, into [picked]: the dictionary's
        // as worked out before but for those the user has counts of now, worked out again
        private fun pick(node: Int, userNode: Int, startNode: Int): Float {
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
                    // one the user made theirs is in their trie; below the dictionary's of the same reading (review-G S2)
                    if (!own.startOnly(word) || scoredFor.blocked(word, -1)) continue
                    val score = score(word) + importedPenalty
                    if (score > best) {
                        best = score
                        picked = word
                    }
                }
            }
            return best
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

        // a node whose words are all blocked is no reading
        private fun keep(count: Int, score: Float) {
            if (score == Float.NEGATIVE_INFINITY) return
            if (best.size == limit && score <= best.last().score) return
            var at = best.size
            while (at > 0 && best[at - 1].score < score) at--
            best.add(at, Found(picked, syllables.copyOf(count), score))
            if (best.size > limit) best.removeAt(best.size - 1)
        }

        // the keys of [spelling] stepped from [depth]: where they end, or null where the path does not pass them or there are none
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
            found.syllables.joinToString(" ") { Syllables.spelling(it) },
        )
    }

    companion object {
        /** Letters a glide spells at most. */
        const val MAX_LETTERS = 30

        /**
         * Pinyin's weights: the path weighed more against the model than English's (GlidePath.Weights),
         * a word's unigram lower in a vocabulary of many more (ime-eval pinyin-glide).
         */
        val WEIGHTS = GlidePath.Weights(shape = 5f, proximity = 6.25f, corner = 1.5f)

        /** Between syllables: the pinyin session reads it as their boundary (SyllableGraph.SEPARATOR). */
        const val SEPARATOR = '\''
    }
}
