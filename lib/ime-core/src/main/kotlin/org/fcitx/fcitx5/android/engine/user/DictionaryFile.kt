/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.BitPacked
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.EliasFano
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.LogFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * The user's merged dictionaries, compiled once ([DictionaryCompiler]) and mmapped, so 1.9 million words cost the
 * heap nothing (0.3.1's objects, ~450 bytes a word, ran a 256 MB heap out).
 *
 * A [DataFile] of [DataFile.KIND_USER_DICTIONARY]. A word is a place: [text], [syllables] (a file of another
 * syllable table is refused and recompiled) and [score] (log10, 0 for its source's best). Places below
 * [trieWords] are in a breadth-first trie (a node's words a run, best first); those above are words the [base]
 * has, found by text only. A trie word's syllables are its node's path, walked up; scores are u16 codes into a
 * book, or linear past 65,536 distinct ones.
 *
 * Absolute reads only: the fcitx thread and a table's coding thread read one file at once.
 */
class DictionaryFile private constructor(buffer: ByteBuffer, verify: Boolean) {

    val size: Int

    val trieWords: Int

    val nodes: Int

    val stamp: String

    val base: String

    private val textStart: BitPacked
    private val chars: CharBuffer
    // the syllables of the words apart only: a trie word's are its node's path
    private val sylStart: BitPacked
    private val syllableIds: ShortBuffer
    private val scores: ShortBuffer
    private val book: FloatBuffer
    private val linear: Boolean
    private val floor: Float
    private val textPlaces: BitPacked
    private val textBuckets: EliasFano
    private val firstChild: BitPacked
    private val nodeSyl: BitPacked
    private val wordStart: BitPacked
    private val hashSize: Int

    init {
        try {
            val file = DataFile.open(buffer, DataFile.KIND_USER_DICTIONARY, VERSION, verify)
            val meta = file.meta(META)
            fun int(key: String) = meta[key]?.toIntOrNull() ?: throw DataFormatException("no $key")
            if (!Syllables.matches(int("syllableCount"), meta["syllableChecksum"]?.toLongOrNull() ?: -1)) {
                throw DataFormatException("of other syllables")
            }
            size = int("words")
            trieWords = int("trieWords")
            nodes = int("nodes")
            hashSize = int("hashSize")
            stamp = meta["stamp"].orEmpty()
            base = meta["base"].orEmpty()
            val powerOfTwo = hashSize > 0 && hashSize and (hashSize - 1) == 0
            if (trieWords !in 0..size || nodes < 1 || !powerOfTwo) throw DataFormatException("bad counts")
            fun section(id: Int, bytes: Long): ByteBuffer = file.section(id).also {
                if (it.capacity().toLong() != bytes) throw DataFormatException("section $id of ${it.capacity()} bytes, not $bytes")
            }
            fun packed(id: Int, count: Int): BitPacked = file.bitPacked(id).also {
                if (it.size != count) throw DataFormatException("section $id of ${it.size} values, not $count")
            }
            textStart = packed(TEXT_START, size + 1)
            chars = section(CHARS, 2L * int("chars")).asCharBuffer()
            sylStart = packed(SYL_START, size - trieWords + 1)
            syllableIds = section(SYLLABLES, 2L * int("syllables")).asShortBuffer()
            scores = section(SCORES, 2L * size).asShortBuffer()
            linear = when (meta["scores"]) {
                SCORES_BOOK -> false
                SCORES_LINEAR -> true
                else -> throw DataFormatException("scores ${meta["scores"]}")
            }
            book = section(SCORE_BOOK, 4L * (if (linear) 1 else int("scoreCodes"))).asFloatBuffer()
            floor = if (linear) book[0] else 0f
            textPlaces = packed(TEXT_PLACES, size)
            textBuckets = file.eliasFano(TEXT_BUCKETS).also {
                if (it.size != hashSize + 1) throw DataFormatException("text buckets of ${it.size}, not ${hashSize + 1}")
            }
            firstChild = packed(FIRST_CHILD, nodes + 1)
            nodeSyl = packed(NODE_SYLLABLE, nodes)
            wordStart = packed(WORD_START, nodes + 1)
            if (verify) checkStructure()
        } catch (e: DataFormatException) {
            throw IOException("dictionary file: ${e.message}", e)
        }
    }

    // every index the reads follow checked in bounds: a compiler bug refuses the file, not an IndexOutOfBounds per key
    private fun checkStructure() {
        fun check(name: String, holds: () -> Boolean) {
            if (!holds()) throw DataFormatException("bad structure: $name")
        }
        textStart.checkRuns("text starts", 0, chars.capacity())
        sylStart.checkRuns("syllable starts", 0, syllableIds.capacity())
        check("syllables") { (0 until syllableIds.capacity()).all { syllableIds[it] in 0 until Syllables.count } }
        check("node syllables") { (1 until nodes).all { nodeSyl[it] < Syllables.count } }
        check("score codes") { linear || (0 until size).all { scores[it].toInt() and 0xffff < book.capacity() } }
        textPlaces.checkBelow("text places", size)
        textBuckets.checkRuns("text buckets", 0, size)
        // breadth first: a node's children after it, so a walk up ends at the root
        check("children") { firstChild[0] == minOf(1, nodes) && firstChild[nodes] == nodes && (0 until nodes).all { firstChild[it] in it + 1..firstChild[it + 1] } }
        wordStart.checkRuns("node words", 0, trieWords)
    }

    fun text(place: Int): String {
        val from = textStart[place]
        return String(CharArray(textStart[place + 1] - from) { chars[from + it] })
    }

    fun length(place: Int): Int = if (place >= trieWords) sylStart[place - trieWords + 1] - sylStart[place - trieWords] else depth(nodeOf(place))

    /** A word's syllables: stored for a word apart; a trie word's walked up its path (some hundred reads; only for words chosen or learned, or a stale file). */
    fun syllables(place: Int): IntArray {
        if (place >= trieWords) {
            val from = sylStart[place - trieWords]
            return IntArray(sylStart[place - trieWords + 1] - from) { syllableIds[from + it].toInt() }
        }
        // a node below ROOT, possible only in a file read unverified, ends the walk too
        var path = IntArray(DictionaryCompiler.MAX_SYLLABLES)
        var d = 0
        var node = nodeOf(place)
        while (node > ROOT) {
            if (d == path.size) path = path.copyOf(2 * d)
            path[d++] = nodeSyl[node]
            node = parentOf(node)
        }
        return IntArray(d) { path[d - 1 - it] }
    }

    fun entry(place: Int): UserModel.Entry = UserModel.Entry(text(place), syllables(place))

    /** log10 of the word's weight against its source's best (so ≤ 0); never NaN. */
    fun score(place: Int): Float {
        val code = scores[place].toInt() and CODE_MASK
        // the best word's code is 0f exactly, not floor * 0 = -0f
        return if (!linear) book[code] else if (code == CODE_MASK) 0f else floor * (1 - code / CODE_MASK.toFloat())
    }

    fun find(text: String, syllables: IntArray): Int {
        var node = ROOT
        for (s in syllables) {
            node = child(node, s)
            if (node < 0) break
        }
        if (node >= 0) {
            for (p in wordStart[node] until wordStart[node + 1]) if (sameText(p, text)) return p
        }
        var found = -1
        forEachOfText(text) { p -> if (found < 0 && p >= trieWords && sameSyllables(p, syllables)) found = p }
        return found
    }

    fun find(entry: UserModel.Entry): Int = find(entry.text, entry.syllables)

    fun forEachOfText(text: String, visit: (Int) -> Unit) {
        val bucket = textBuckets.run(DictionaryCompiler.slot(text.hashCode(), hashSize))
        for (i in bucket.start until bucket.end) {
            val p = textPlaces[i]
            if (sameText(p, text)) visit(p)
        }
    }

    fun placesOf(text: String): IntArray {
        var out = IntArray(0)
        forEachOfText(text) { out += it }
        return out
    }

    fun hasText(text: String): Boolean {
        var found = false
        forEachOfText(text) { found = true }
        return found
    }

    fun child(node: Int, syllable: Int): Int {
        var low = firstChild[node]
        var high = firstChild[node + 1] - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val s = nodeSyl[mid]
            when {
                s < syllable -> low = mid + 1
                s > syllable -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    fun childCount(node: Int): Int = firstChild[node + 1] - firstChild[node]

    fun firstChild(node: Int): Int = firstChild[node]

    fun nodeSyllable(node: Int): Int = nodeSyl[node]

    fun wordCount(node: Int): Int = wordStart[node + 1] - wordStart[node]

    fun word(node: Int, index: Int): Int = wordStart[node] + index

    private fun nodeOf(place: Int): Int = lastAtOrBelow(wordStart, nodes, place)

    private fun parentOf(node: Int): Int = lastAtOrBelow(firstChild, node, node)

    private fun depth(node: Int): Int {
        var n = node
        var d = 0
        // parentOf is below its node, so this ends even in a damaged unverified file, where it can reach -1
        while (n > ROOT) {
            n = parentOf(n)
            d++
        }
        return d
    }

    private fun lastAtOrBelow(starts: BitPacked, end: Int, key: Int): Int {
        var low = 0
        var high = end
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= key) low = mid + 1 else high = mid
        }
        return low - 1
    }

    private fun sameText(p: Int, text: String): Boolean {
        val from = textStart[p]
        if (textStart[p + 1] - from != text.length) return false
        for (i in text.indices) if (chars[from + i] != text[i]) return false
        return true
    }

    private fun sameSyllables(p: Int, syllables: IntArray): Boolean {
        val from = sylStart[p - trieWords]
        if (sylStart[p - trieWords + 1] - from != syllables.size) return false
        for (i in syllables.indices) if (syllableIds[from + i].toInt() != syllables[i]) return false
        return true
    }

    companion object {
        const val ROOT = 0
        const val VERSION = 2
        internal const val META = 0
        internal const val TEXT_START = 1
        internal const val CHARS = 2
        internal const val SYL_START = 3
        internal const val SYLLABLES = 4
        internal const val SCORES = 5
        internal const val TEXT_PLACES = 6
        internal const val TEXT_BUCKETS = 7
        internal const val FIRST_CHILD = 8
        internal const val NODE_SYLLABLE = 9
        internal const val WORD_START = 10
        internal const val SCORE_BOOK = 11

        // SCORES' u16 codes: an index into SCORE_BOOK, or a linear step from its one float (the floor) up to 0
        internal const val SCORES_BOOK = "book"
        internal const val SCORES_LINEAR = "linear"
        internal const val CODE_MASK = 0xffff

        /** [file] mapped; [verify] reads it through for every section's checksum: off the fcitx thread, once per process. */
        @Throws(IOException::class)
        fun open(file: LogFile, verify: Boolean = true): DictionaryFile = DictionaryFile(file.map(), verify)

        @Throws(IOException::class)
        fun of(bytes: ByteBuffer, verify: Boolean = true): DictionaryFile = DictionaryFile(bytes, verify)

        /** How many words [file] has, unchecked: for the settings' list. */
        @Throws(IOException::class)
        fun wordCount(file: LogFile): Int = open(file, verify = false).size
    }
}
