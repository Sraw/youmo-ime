/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
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

    private val textStart: IntBuffer
    private val chars: CharBuffer
    private val sylStart: IntBuffer
    private val syllableIds: ShortBuffer
    private val scores: FloatBuffer
    private val textHash: IntBuffer
    private val textNext: IntBuffer
    private val firstChild: IntBuffer
    private val nodeSyl: ShortBuffer
    private val wordFrom: IntBuffer
    private val wordCounts: IntBuffer
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
            textStart = section(TEXT_START, 4L * (size + 1)).asIntBuffer()
            chars = section(CHARS, 2L * int("chars")).asCharBuffer()
            sylStart = section(SYL_START, 4L * (size + 1)).asIntBuffer()
            syllableIds = section(SYLLABLES, 2L * int("syllables")).asShortBuffer()
            scores = section(SCORES, 4L * size).asFloatBuffer()
            textHash = section(TEXT_HASH, 4L * hashSize).asIntBuffer()
            textNext = section(TEXT_NEXT, 4L * size).asIntBuffer()
            firstChild = section(FIRST_CHILD, 4L * (nodes + 1)).asIntBuffer()
            nodeSyl = section(NODE_SYLLABLE, 2L * nodes).asShortBuffer()
            wordFrom = section(WORD_FROM, 4L * nodes).asIntBuffer()
            wordCounts = section(WORD_COUNT, 4L * nodes).asIntBuffer()
            if (verify) checkStructure()
        } catch (e: DataFormatException) {
            throw IOException("dictionary file: ${e.message}", e)
        }
    }

    // every index the reads follow in bounds, a pass beside the CRC's: a compiler's bug a refused
    // file, not an IndexOutOfBounds on every key (review NIT 5)
    private fun checkStructure() {
        fun runs(starts: IntBuffer, count: Int, end: Int) =
            starts[0] == 0 && starts[count] == end && (0 until count).all { starts[it] <= starts[it + 1] }
        // the invariant that does not hold named, for the log
        fun check(name: String, holds: () -> Boolean) {
            if (!holds()) throw DataFormatException("bad structure: $name")
        }
        check("text starts") { runs(textStart, size, chars.capacity()) }
        check("syllable starts") { runs(sylStart, size, syllableIds.capacity()) }
        check("syllables") { (0 until syllableIds.capacity()).all { syllableIds[it] in 0 until Syllables.count } }
        check("text hash") { (0 until hashSize).all { textHash[it] in 0..size } }
        check("text chain") { (0 until size).all { textNext[it] in 0..size } }
        check("children") { firstChild[nodes] == nodes && (0 until nodes).all { firstChild[it] in 1..firstChild[it + 1] } }
        check("node words") { (0 until nodes).all { wordFrom[it] >= 0 && wordCounts[it] >= 0 && wordFrom[it] + wordCounts[it] <= trieWords } }
    }

    /** The text of the word at [place]. */
    fun text(place: Int): String {
        val from = textStart[place]
        return String(CharArray(textStart[place + 1] - from) { chars[from + it] })
    }

    /** How many syllables the word at [place] has. */
    fun length(place: Int): Int = sylStart[place + 1] - sylStart[place]

    /** The [i]th syllable of the word at [place]. */
    fun syllable(place: Int, i: Int): Int = syllableIds[sylStart[place] + i].toInt()

    fun syllables(place: Int): IntArray = IntArray(length(place)) { syllable(place, it) }

    fun entry(place: Int): UserModel.Entry = UserModel.Entry(text(place), syllables(place))

    /** log10 of the word's weight against its source's best word (so ≤ 0); never NaN. */
    fun score(place: Int): Float = scores[place]

    /** The place of [text] read as [syllables], or -1. */
    fun find(text: String, syllables: IntArray): Int {
        var p = textHash[DictionaryCompiler.slot(text.hashCode(), hashSize)] - 1
        // at most every place once: a file opened unchecked (verify false) may have a chain that loops
        var steps = 0
        while (p >= 0 && steps++ < size) {
            if (sameText(p, text) && sameSyllables(p, syllables)) return p
            p = textNext[p] - 1
        }
        return -1
    }

    fun find(entry: UserModel.Entry): Int = find(entry.text, entry.syllables)

    /** Each place of [text], in every reading it has here. */
    fun forEachOfText(text: String, visit: (Int) -> Unit) {
        var p = textHash[DictionaryCompiler.slot(text.hashCode(), hashSize)] - 1
        var steps = 0
        while (p >= 0 && steps++ < size) {
            if (sameText(p, text)) visit(p)
            p = textNext[p] - 1
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
            val s = nodeSyl[mid].toInt()
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
    fun nodeSyllable(node: Int): Int = nodeSyl[node].toInt()

    fun wordCount(node: Int): Int = wordCounts[node]

    /** The place of the [index]th word at [node], best first. */
    fun word(node: Int, index: Int): Int = wordFrom[node] + index

    private fun sameText(p: Int, text: String): Boolean {
        val from = textStart[p]
        if (textStart[p + 1] - from != text.length) return false
        for (i in text.indices) if (chars[from + i] != text[i]) return false
        return true
    }

    private fun sameSyllables(p: Int, syllables: IntArray): Boolean {
        if (length(p) != syllables.size) return false
        val from = sylStart[p]
        for (i in syllables.indices) if (syllableIds[from + i].toInt() != syllables[i]) return false
        return true
    }

    companion object {
        const val ROOT = 0
        internal const val VERSION = 1
        internal const val META = 0
        internal const val TEXT_START = 1
        internal const val CHARS = 2
        internal const val SYL_START = 3
        internal const val SYLLABLES = 4
        internal const val SCORES = 5
        internal const val TEXT_HASH = 6
        internal const val TEXT_NEXT = 7
        internal const val FIRST_CHILD = 8
        internal const val NODE_SYLLABLE = 9
        internal const val WORD_FROM = 10
        internal const val WORD_COUNT = 11

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
