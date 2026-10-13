/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishDecoder
import org.fcitx.fcitx5.android.engine.english.EnglishText

/**
 * The English model's words, chosen from how often each spelling is seen (pass one): the words
 * the keyboard offers, as they are written. A word is the spelling with its letters' case and its
 * apostrophes; spellings of the same letters (the, The, THE; don't, dont; us, US) are one group, and
 * a spelling seen less than [KEEP_SHARE] as often as its group's commonest is none of its own:
 * text with it is counted as the commonest (dont as don't, i as I), as the user typing it means.
 * A spelling with an apostrophe is held to the group's commonest with one, not to a spelling
 * without, and to [CONTRACTION_SHARE] of the group's commonest: we'll is a word beside well a
 * fifteenth as common, th'e seen once beside the's 300 is none, and We'll is counted as we'll. A
 * real word with a contraction's letters ([EnglishDecoder.LIKE_CONTRACTIONS]) is held to its
 * group's commonest without one alike: wed is a word beside we'd, and WED is counted as wed; dont,
 * a slip, as don't.
 *
 * A sentence's first word is capitalised whatever it is: seen there, it is counted as the
 * spellings it may be (The as the, Apple as Apple or apple) in the shares they have elsewhere.
 *
 * Pages have their slips too (teh, becuase): a group one edit from another seen [TYPO_RATIO] times
 * as often is a slip of it, no word, and counted as that group's commonest spelling. A word the
 * model had would never be corrected. Words [known] to a dictionary are none (currant, zines).
 */
class EnglishVocabulary {

    // [elsewhere, first in a sentence] by spelling
    private val seen = HashMap<String, IntArray>()

    fun add(words: List<String>, sentence: Boolean) {
        tokens += words.size
        words.forEachIndexed { i, word -> seen.getOrPut(word) { IntArray(2) }[if (sentence && i == 0) 1 else 0]++ }
    }

    val spellings: Int get() = seen.size

    /** How many words were added: pass two, over the same pages, counts no more n-grams. */
    var tokens = 0L
        private set

    /**
     * The [size] commonest words, groups' spellings kept as above, after [UNKNOWN] and
     * [EnglishText.START]; ties in spelling order, so the same pages give the same words.
     */
    fun words(size: Int, known: Set<String> = emptySet()): Words {
        val groups = HashMap<String, MutableList<String>>()
        for (word in seen.keys) groups.getOrPut(key(word)) { ArrayList() } += word
        val total = HashMap<String, Double>()
        for (group in groups.values) {
            for (word in group) total[word] = seen.getValue(word)[0].toDouble()
            // a sentence's first word: shared among what it may be, as often as each is seen elsewhere
            for (word in group) {
                val first = seen.getValue(word)[1]
                if (first == 0) continue
                val may = group.filter { capitalised(it) == word && seen.getValue(it)[0] > 0 }
                val elsewhere = may.sumOf { seen.getValue(it)[0] }
                if (elsewhere == 0) total[word] = total.getValue(word) + first
                else for (m in may) total[m] = total.getValue(m) + first.toDouble() * seen.getValue(m)[0] / elsewhere
            }
        }
        val kept = ArrayList<String>()
        val commonest = HashMap<String, String>()
        val contracted = HashMap<String, String>()
        val uncontracted = HashMap<String, String>()
        for ((key, group) in groups) {
            val (top, contraction, plain) = cut(group, total, kept)
            commonest[key] = top
            if (contraction != null) contracted[key] = contraction
            if (plain != null) uncontracted[key] = plain
        }
        kept.sortWith(compareByDescending<String> { total.getValue(it) }.thenBy { it })
        val groupTotal = HashMap<String, Double>(groups.size * 2)
        for ((key, group) in groups) groupTotal[key] = group.sumOf { total.getValue(it) }
        val chosen = ArrayList<String>(size)
        val slips = HashMap<String, String>()
        for (word in kept) {
            if (chosen.size == size) break
            val key = key(word)
            val of = if (key in known) null else slipOf(key, groupTotal)
            if (of != null) slips[key] = commonest.getValue(of) else chosen += word
        }
        val exempted = chosen.count { total.getValue(it) < KEEP_SHARE * total.getValue(commonest.getValue(key(it))) }
        return Words(listOf(UNKNOWN, EnglishText.START) + chosen, commonest, slips, contracted, uncontracted, exempted)
    }

    /**
     * Those of [group]'s spellings, by their [total]s, that are words of their own, into [kept];
     * returns its commonest, its commonest with an apostrophe if that is another, and, if it is of
     * [EnglishDecoder.LIKE_CONTRACTIONS] and its commonest has one, its commonest without.
     */
    private fun cut(group: List<String>, total: Map<String, Double>, kept: MutableList<String>): Triple<String, String?, String?> {
        val order = compareBy<String> { total.getValue(it) }.thenByDescending { it }
        val top = group.maxWith(order)
        // well's share would leave we'll out, and the keyboard could never offer it
        val contraction = if ('\'' in top) top else group.filter { '\'' in it }.maxWithOrNull(order)
        // and we'd's would leave wed out
        val plain = if ('\'' in top && key(top) in EnglishDecoder.LIKE_CONTRACTIONS) group.filter { '\'' !in it }.maxWithOrNull(order) else null
        // the top's hundredth all the same, or a group with no contraction keeps its commonest slip: th'e, i's
        val least = CONTRACTION_SHARE * total.getValue(top)
        group.filterTo(kept) { word ->
            val of = (if ('\'' in word) contraction else plain) ?: top
            total.getValue(word) >= maxOf(KEEP_SHARE * total.getValue(of), least) && total.getValue(word) > 0
        }
        return Triple(top, contraction?.takeIf { it != top }, plain)
    }

    // the group [key] is a slip of, one edit from it and [TYPO_RATIO] times as common; null for none
    private fun slipOf(key: String, groupTotal: Map<String, Double>): String? {
        if (key.length < MIN_SLIP) return null
        val least = TYPO_RATIO * groupTotal.getValue(key)
        var best: String? = null
        var bestTotal = least
        for (other in edits(key)) {
            val t = groupTotal[other] ?: continue
            if (t >= bestTotal) {
                best = other
                bestTotal = t
            }
        }
        return best
    }

    /**
     * The words chosen, and how a spelling of the pages is counted ([id]): itself if it is one,
     * else its group's commonest spelling if that is; one with an apostrophe the commonest lacks as
     * the group's commonest with one ([contracted], by key), if that is one; one without, of a
     * group of [EnglishDecoder.LIKE_CONTRACTIONS] whose commonest has one, as its commonest without
     * ([uncontracted]).
     */
    class Words(
        val list: List<String>,
        commonest: Map<String, String>,
        val slips: Map<String, String> = emptyMap(),
        contracted: Map<String, String> = emptyMap(),
        uncontracted: Map<String, String> = emptyMap(),
        /** How many of [list] are under [KEEP_SHARE] of their group's commonest, held to another (we'll beside well). */
        val exempted: Int = 0,
    ) {
        private val ids = HashMap<String, Int>(list.size * 2).apply { list.forEachIndexed { i, w -> put(w, i) } }
        private val byKey = HashMap<String, Int>().apply {
            for ((key, word) in commonest) ids[word]?.let { put(key, it) }
            for ((key, word) in slips) ids[word]?.let { put(key, it) }
        }
        private val byContraction = HashMap<String, Int>().apply { for ((key, word) in contracted) ids[word]?.let { put(key, it) } }
        private val byPlain = HashMap<String, Int>().apply { for ((key, word) in uncontracted) ids[word]?.let { put(key, it) } }

        /** [word]'s id, as written [first] in a sentence or not; -1 for none. */
        fun id(word: String, first: Boolean): Int {
            if (first) {
                // the commoner of the two it may be: The as the, Apple as Apple where that is commoner
                val lower = decapitalised(word)
                val a = ids[word]
                val b = ids[lower]
                if (a != null && b != null) return minOf(a, b)
                (a ?: b)?.let { return it }
            }
            val key = key(word)
            return ids[word] ?: (if ('\'' in word) byContraction[key] else byPlain[key]) ?: byKey[key] ?: -1
        }
    }

    companion object {
        /** A spelling this share as common as its group's commonest is a word of its own. */
        const val KEEP_SHARE = 0.1

        /**
         * A spelling held to another than its group's commonest is none under this share of the
         * commonest. Not tuned on FineWeb: an `english-data` release checks what it keeps and drops
         * (specs/data-and-build.md).
         */
        const val CONTRACTION_SHARE = 0.01

        const val UNKNOWN = PinyinDataBuilder.UNKNOWN

        /** A group this many times as common as one an edit away from it makes that one a slip. */
        const val TYPO_RATIO = 300.0

        // shorter, an edit is a word of its own more often than not: of, on, or, if
        private const val MIN_SLIP = 3

        private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"

        /** [key] one letter deleted, two side by side swapped, one replaced or one inserted. */
        fun edits(key: String): Sequence<String> = sequence {
            for (i in key.indices) yield(key.removeRange(i, i + 1))
            for (i in 0 until key.length - 1) yield(key.substring(0, i) + key[i + 1] + key[i] + key.substring(i + 2))
            for (i in key.indices) for (c in LETTERS) if (c != key[i]) yield(key.substring(0, i) + c + key.substring(i + 1))
            for (i in 0..key.length) for (c in LETTERS) yield(key.substring(0, i) + c + key.substring(i))
        }

        /** Its group: the letters, lower case, without apostrophes. */
        fun key(word: String): String = word.lowercase().replace("'", "")

        private fun capitalised(word: String) = word.replaceFirstChar { it.uppercaseChar() }
        private fun decapitalised(word: String) = word.replaceFirstChar { it.lowercaseChar() }
    }
}

/**
 * Pass two: the n-grams of [words], each run of the pages as [EnglishText] splits it, a sentence's
 * from [EnglishText.START]. A word that is none breaks the run there. A run that starts no sentence
 * (after a number, a symbol or a word that is none) is counted from [start], a virtual start as
 * [ChatCounts]' is: no n-gram is kept from it, but its words get the continuation counts of words
 * after another, which pm before EST in 3 pm EST would otherwise never have.
 *
 * [expected] is how many words pass two reads ([EnglishVocabulary.tokens] of the same pages): the
 * n-grams are made room for up front, no more than that many, at most FineWeb's tens of millions.
 */
class EnglishCounts(private val words: EnglishVocabulary.Words, expected: Long) : NgramCounts {

    init {
        require(words.list.size < NgramKey.MAX_WORDS) { "${words.list.size} words leave no id for the start" }
    }

    override val start = words.list.size
    override val bigrams = LongIndex(room(expected, MAX_BIGRAMS))
    override val bigramCount = IntColumn(room(expected, MAX_BIGRAMS))
    override val trigrams = LongIndex(room(expected, MAX_TRIGRAMS))
    override val trigramCount = IntColumn(room(expected, MAX_TRIGRAMS))

    var tokens = 0L
        private set

    override val sentenceStart = words.id(EnglishText.START, first = false)
    private var ids = IntArray(64)

    fun add(run: List<String>, sentence: Boolean) {
        if (ids.size <= run.size) ids = IntArray(run.size * 2 + 1)
        ids[0] = if (sentence) sentenceStart else start
        var n = 1
        for ((i, word) in run.withIndex()) {
            val id = words.id(word, first = sentence && i == 0)
            tokens++
            if (id < 0) {
                count(n)
                ids[0] = start
                n = 1
                continue
            }
            ids[n++] = id
        }
        count(n)
    }

    private fun count(n: Int) {
        for (i in 1 until n) {
            bigramCount.add(bigrams.add(NgramKey.of(ids[i - 1], ids[i])), 1)
            if (i >= 2) trigramCount.add(trigrams.add(NgramKey.of(ids[i - 2], ids[i - 1], ids[i])), 1)
        }
    }

    private companion object {
        // grown to rather than made room for, FineWeb's would be hashed again at every doubling
        const val MAX_BIGRAMS = 1 shl 22
        const val MAX_TRIGRAMS = 1 shl 24

        fun room(expected: Long, most: Int) = minOf(expected, most.toLong()).toInt()
    }
}
