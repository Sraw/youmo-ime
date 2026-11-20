/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishDecoder
import org.fcitx.fcitx5.android.engine.english.EnglishText

/** The English model's words by spelling: a rare variant counts as its group's commonest, a much rarer one an edit away is a slip. */
class EnglishVocabulary {

    // [elsewhere, first in a sentence] by spelling
    private val seen = HashMap<String, IntArray>()

    fun add(words: List<String>, sentence: Boolean) {
        tokens += words.size
        words.forEachIndexed { i, word -> seen.getOrPut(word) { IntArray(2) }[if (sentence && i == 0) 1 else 0]++ }
    }

    val spellings: Int get() = seen.size

    /** How many words were added: pass two, over the same pages, sizes its n-grams by it. */
    var tokens = 0L
        private set

    /** The [size] commonest words after [UNKNOWN] and [EnglishText.START]; ties in spelling order, so the same pages give the same words. */
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

    /** [group]'s spellings that are words of their own, into [kept]; returns its commonest forms. */
    private fun cut(group: List<String>, total: Map<String, Double>, kept: MutableList<String>): Triple<String, String?, String?> {
        val order = compareBy<String> { total.getValue(it) }.thenByDescending { it }
        val top = group.maxWith(order)
        val contraction = if ('\'' in top) top else group.filter { '\'' in it }.maxWithOrNull(order)
        // well's share would leave we'll out; a possessive is no such word, held to the group's commonest
        val alike = key(top) in EnglishDecoder.LIKE_CONTRACTIONS
        val plain = if ('\'' in top && alike) group.filter { '\'' !in it }.maxWithOrNull(order) else null
        // and in such a group a slip held to its own kind is under the top's hundredth all the same: we'l'l
        val least = CONTRACTION_SHARE * total.getValue(top)
        group.filterTo(kept) { word ->
            val of = if (!alike) top else (if ('\'' in word) contraction else plain) ?: top
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

    /** The words chosen, and how a page's spelling is counted ([id]). */
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
        const val KEEP_SHARE = 0.1

        /** A spelling held to another than its group's commonest is none under this share of the commonest (checked per `english-data` release). */
        const val CONTRACTION_SHARE = 0.01

        const val UNKNOWN = PinyinDataBuilder.UNKNOWN

        /** A group this many times as common as one an edit away from it makes that one a slip. */
        const val TYPO_RATIO = 300.0

        // shorter, an edit is a word of its own more often than not: of, on, or, if
        private const val MIN_SLIP = 3

        private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"

        fun edits(key: String): Sequence<String> = sequence {
            for (i in key.indices) yield(key.removeRange(i, i + 1))
            for (i in 0 until key.length - 1) yield(key.substring(0, i) + key[i + 1] + key[i] + key.substring(i + 2))
            for (i in key.indices) for (c in LETTERS) if (c != key[i]) yield(key.substring(0, i) + c + key.substring(i + 1))
            for (i in 0..key.length) for (c in LETTERS) yield(key.substring(0, i) + c + key.substring(i))
        }

        fun key(word: String): String = word.lowercase().replace("'", "")

        private fun capitalised(word: String) = word.replaceFirstChar { it.uppercaseChar() }
        private fun decapitalised(word: String) = word.replaceFirstChar { it.lowercaseChar() }
    }
}

/** Pass two: the n-grams of [words] over the pages' runs, a run that starts no sentence counted from [start]. */
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
        // made room for up front: grown to, a large corpus would rehash at every doubling
        const val MAX_BIGRAMS = 1 shl 22
        const val MAX_TRIGRAMS = 1 shl 24

        fun room(expected: Long, most: Int) = minOf(expected, most.toLong()).toInt()
    }
}
