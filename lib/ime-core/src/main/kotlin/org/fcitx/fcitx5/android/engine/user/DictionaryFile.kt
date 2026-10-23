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
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/**
 * The dictionaries the user merged into the base one, compiled once ([DictionaryCompiler]) and
 * read in place: mmapped, so 1.9 million words (rime-frost) cost the Java heap nothing. Before
 * this, 0.3.1 read the text whole at each start and listed each word as objects in [UserModel],
 * about 450 bytes a word, and a phone's 256 MB heap ran out on every focus.
 *
 * A [DataFile] of [DataFile.KIND_USER_DICTIONARY]. A word is a place, 0 until [size]: its [text],
 * its [syllables] (ids of [Syllables]; a file of another syllable table is refused, and the app
 * compiles it again) and its [score] (log10, 0 for its source's best word; a word no weight ranks
 * has the least score of any source, 0 when none is weighted). Those before [trieWords]
 * are in the trie ([ROOT], [child], [wordCount], [word]), laid out breadth first, so each node's
 * children are a run, and a node's words a run of places, best first; those from [trieWords] up
 * the base dictionary has ([base] names it), found by text and reading only ([find], [placesOf]).
 *
 * Version 2 is the first's at half the size (rime-frost 105.6 MiB to some 49): the words in the
 * nodes' breadth-first order, so a node's run is told by one start; a trie word's syllables are its
 * node's path, read walking up ([syllables]: for a word chosen or learned, never per arc), and only
 * those apart stored; scores as u16 codes into a book of the distinct ones (exact), or linear from
 * the least past 65,536 of them; the text index the places sorted by their text's slot, with
 * where each slot's run starts; the int arrays [BitPacked], the bucket starts [EliasFano].
 *
 * Absolute reads only: the fcitx thread and a table's coding thread read one file at once.
 */
class DictionaryFile private constructor(buffer: ByteBuffer, verify: Boolean) {

    /** How many words it has. */
    val size: Int

    /** How many of them, the first, are in the trie: the rest the base dictionary has. */
    val trieWords: Int

    /** How many nodes its trie has, the root among them. */
    val nodes: Int

    /** What the caller compiled it from, as it said. */
    val stamp: String

    /** The base dictionary its words were kept out of the trie for, as the caller named it. */
    val base: String

    private val textStart: BitPacked
    private val chars: CharBuffer
    // the syllables of the words apart (from trieWords) only: a trie word's are its node's path
    private val sylStart: BitPacked
    private val syllableIds: ShortBuffer
    private val scores: ShortBuffer
    private val book: FloatBuffer
    private val linear: Boolean
    private val floor: Float
    // the places by their text's slot, and where each slot's run of them starts
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

    // every index the reads follow in bounds, a pass beside the CRC's: a compiler's bug a refused
    // file, not an IndexOutOfBounds on every key (review NIT 5)
    private fun checkStructure() {
        // the invariant that does not hold named, for the log
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
        // breadth first: a node's children after it, so a walk up from a word ends at the root
        check("children") { firstChild[0] == minOf(1, nodes) && firstChild[nodes] == nodes && (0 until nodes).all { firstChild[it] in it + 1..firstChild[it + 1] } }
        wordStart.checkRuns("node words", 0, trieWords)
    }

    /** The text of the word at [place]. */
    fun text(place: Int): String {
        val from = textStart[place]
        return String(CharArray(textStart[place + 1] - from) { chars[from + it] })
    }

    /** How many syllables the word at [place] has: a trie word's by a walk up its path, as [syllables]. */
    fun length(place: Int): Int = if (place >= trieWords) sylStart[place - trieWords + 1] - sylStart[place - trieWords] else depth(nodeOf(place))

    /**
     * A word apart's syllables as stored; a trie word's its node's path, walked up: some hundred
     * reads, for a word chosen or learned, never per arc of a key.
     */
    fun syllables(place: Int): IntArray {
        if (place >= trieWords) {
            val from = sylStart[place - trieWords]
            return IntArray(sylStart[place - trieWords + 1] - from) { syllableIds[from + it].toInt() }
        }
        // one walk up (review F3624), leaf first; a node below ROOT, only in a file read unverified
        // (merged.ymd before its check), ends it too (F3622)
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

    /** log10 of the word's weight against its source's best word (so ≤ 0); never NaN. */
    fun score(place: Int): Float {
        val code = scores[place].toInt() and CODE_MASK
        // the best word's code is CODE_MASK: 0f exactly, as the codebook stores it, not floor * 0 = -0f (F3623)
        return if (!linear) book[code] else if (code == CODE_MASK) 0f else floor * (1 - code / CODE_MASK.toFloat())
    }

    /** The place of [text] read as [syllables], or -1. */
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

    /** Each place of [text], in every reading it has here. */
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

    /** @return the child of [node] reached by [syllable], or -1 */
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

    /** The first child of [node]: its children are [childCount] nodes from there, by syllable. */
    fun firstChild(node: Int): Int = firstChild[node]

    /** The syllable that leads to [node]. */
    fun nodeSyllable(node: Int): Int = nodeSyl[node]

    fun wordCount(node: Int): Int = wordStart[node + 1] - wordStart[node]

    /** The place of the [index]th word at [node], best first. */
    fun word(node: Int, index: Int): Int = wordStart[node] + index

    // the node whose run of words holds place: the last whose words start at or before it
    private fun nodeOf(place: Int): Int = lastAtOrBelow(wordStart, nodes, place)

    // the node whose run of children holds node: the last whose children start at or before it
    private fun parentOf(node: Int): Int = lastAtOrBelow(firstChild, node, node)

    private fun depth(node: Int): Int {
        var n = node
        var d = 0
        // parentOf is below its node, so this ends even in a damaged file read unverified, where it
        // can reach -1 rather than ROOT (review F3622)
        while (n > ROOT) {
            n = parentOf(n)
            d++
        }
        return d
    }

    // the last index below [end] of the non-decreasing [starts] whose value is at most [key]
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

    // of a word apart
    private fun sameSyllables(p: Int, syllables: IntArray): Boolean {
        val from = sylStart[p - trieWords]
        if (sylStart[p - trieWords + 1] - from != syllables.size) return false
        for (i in syllables.indices) if (syllableIds[from + i].toInt() != syllables[i]) return false
        return true
    }

    companion object {
        const val ROOT = 0
        internal const val VERSION = 2
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

        // how SCORES' u16 codes read: an index into SCORE_BOOK's floats, or a step of the
        // linear scale from SCORE_BOOK's one float (the floor) up to 0
        internal const val SCORES_BOOK = "book"
        internal const val SCORES_LINEAR = "linear"
        internal const val CODE_MASK = 0xffff

        /**
         * [file] mapped: read in place, never into the heap. [verify] checks every section's
         * checksum, reading the file through once: off the fcitx thread, once a file is new to
         * the process.
         */
        @Throws(IOException::class)
        fun open(file: File, verify: Boolean = true): DictionaryFile = RandomAccessFile(file, "r").use { f ->
            DictionaryFile(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()), verify)
        }

        /** One already in memory: tests. */
        @Throws(IOException::class)
        fun of(bytes: ByteBuffer, verify: Boolean = true): DictionaryFile = DictionaryFile(bytes, verify)

        /** How many words [file] has, unchecked: for the settings' list. */
        @Throws(IOException::class)
        fun wordCount(file: File): Int = open(file, verify = false).size
    }
}
