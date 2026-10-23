/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.BitPacked
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.EliasFano
import org.fcitx.fcitx5.android.engine.data.splitValues
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import kotlin.math.log10

/**
 * Compiles the dictionaries the user merged into the base one into one [DictionaryFile], once
 * (at import, at a change of which are on, at a new base dictionary). It runs in the keyboard's
 * process beside typing, so its heap is bounded whatever the size: the lines are streamed and
 * sorted on disk in runs ([RUN_WORDS] words in the heap at a time), merged, and written a
 * section at a time; the largest thing held is the text index as it is sorted, 8 bytes a word. Rime-frost's 1.9
 * million words compile in 48 MB (DictionaryFileBoundedHeapTest proves 2 million in that).
 *
 * The words come out in their trie's breadth-first order, so a node's words are a run of places:
 * by depth, then by syllables, a node's words by score, best first. Those the base dictionary has (`inBase`) are kept apart,
 * past [DictionaryFile.trieWords], out of the trie: the decoder finds them in the base already,
 * and a second arc would double them (and bring back one the user blocked there).
 */
object DictionaryCompiler {

    /** More than this many words is no dictionary for a phone: the import says so. */
    const val MAX_WORDS = 4_000_000

    /**
     * More lines than this (the same word twice counted twice) is no source of at most [MAX_WORDS]
     * words: read no further. Twice the words, so a file listed with itself still compiles.
     */
    const val MAX_LINES = MAX_WORDS * 2

    /** More syllables than this is no word: the line does not read. */
    const val MAX_SYLLABLES = 32

    /**
     * More words than this of one reading are left out (counted unread): a reading's words are held
     * together to rank them, and no dictionary has a reading near so many (rime-frost's most is
     * under a thousand); a file of millions at one reading would otherwise take them all in the heap.
     */
    const val MAX_PER_READING = 16_384

    // the sources' names and words read, name/count/name/count…
    private const val SOURCES = "sources"

    /**
     * How many words each source of [file] read, by the name it was compiled under; none for a
     * file of no names, or one that cannot be read. Its header only, unchecked: for the settings' list.
     */
    fun sourceWords(file: File): Map<String, Int> = try {
        RandomAccessFile(file, "r").use { f ->
            val buffer = f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
            val parts = DataFile.open(buffer, DataFile.KIND_USER_DICTIONARY, DictionaryFile.VERSION, verify = false)
                .meta(DictionaryFile.META)[SOURCES].orEmpty().split('/')
            (0 until parts.size / 2).associate { parts[2 * it] to (parts[2 * it + 1].toIntOrNull() ?: 0) }
        }
    } catch (_: IOException) {
        emptyMap()
    } catch (_: DataFormatException) {
        emptyMap()
    }

    /** The steps of a compile, in order: the lines read and sorted in runs, the runs merged, the file written. */
    enum class Stage { READ, MERGE, WRITE }

    /**
     * Told how far a compile is: [done] of [of] in [stage] (lines read, of none known; words merged;
     * sections written). It may throw to stop the compile (the user cancelled): the file there stays.
     */
    fun interface Progress {
        fun at(stage: Stage, done: Int, of: Int)
    }

    /** What a compile made: the words written, how many the base has, the lines that did not read as one. */
    class Result(val words: Int, val inBase: Int, val unread: Int)

    /**
     * Whether [name] is what a compile here or a table's index build (CodedIndex) leaves while it
     * writes: `<dest>.tmp` of a `.ymd` or `.ymc`, or the `.<dest>.build` folder of its runs. Half
     * made: left out of a backup, deleted by a sweep. The app asks here, not its own copy of these
     * names, so a rename here cannot quietly break its filter (review F3373).
     */
    fun isWorkFile(name: String): Boolean =
        name.endsWith(".ymd$TEMP") || name.endsWith(".ymc$TEMP") || (name.startsWith(".") && name.endsWith(WORK))

    private const val TEMP = ".tmp"
    private const val WORK = ".build"

    /**
     * The words of [sources] (each a dictionary, in libime's text format: `text pin'yin [weight]`,
     * as [LibimeImport.dictionaryEntry] reads a line) into [dest], replaced whole or not at all.
     * A source's weights are read as their kind, decided over the whole source: all in (0, 1]
     * probabilities (rime-frost), all > 1 counts (Sogou), all < 0 log10 probabilities; a 0 is
     * none, as 0.3.1 wrote one missing; signs mixed are none. Each source's best word scores 0,
     * the rest below it as their weight says: so what a source ranks above what is kept, and no
     * source outranks another by its scale. A word in two sources, or twice, keeps its best.
     *
     * [stamp] and [base] are kept in the file: what it was compiled from, and the base dictionary
     * [inBase] answered for. [progress] hears the lines read. [names] are the sources', kept
     * with how many words each read ([sourceWords]), for the settings' list.
     *
     * Heap: a run (about 6 MB), the streams of the trie's depths (16 KB each, some 2 MB) and the
     * scores' book (512 KB); as the file is written, its text index, 4 bytes a slot and a place
     * (about 16 MB at 2 million words, 32 MB at [MAX_WORDS]), let go before the other sections:
     * within 48 MB for rime-frost's 1.9 million (DictionaryFileBoundedHeapTest), not at the cap (review NIT 2).
     * Files: up to some 140 open at once (three streams a depth's words, one its nodes, up to
     * [MAX_SYLLABLES] deep, the base's and a merge's runs); far below Android's 32K, opened per depth
     * lazily if a ROM's limit is ever found low (review F3628).
     *
     * @throws IOException if [dest] cannot be written, more than [MAX_WORDS] words come, or the heap runs out
     */
    @Throws(IOException::class)
    fun compile(
        sources: List<Sequence<String>>,
        dest: File,
        stamp: String = "",
        base: String = "",
        inBase: (String, IntArray) -> Boolean = { _, _ -> false },
        progress: Progress = Progress { _, _, _ -> },
        names: List<String> = emptyList(),
    ): Result = try {
        compileInto(sources, dest, stamp, base, inBase, progress, names)
    } catch (e: OutOfMemoryError) {
        // a compile is bounded, but a heap a phone has nearly filled already is not: told as a
        // failed compile, which the caller records for this stamp, never a crash it retries at
        // every start (0.3.1's loop)
        throw IOException("out of memory compiling $dest", e)
    }

    @Suppress("LongParameterList")
    private fun compileInto(
        sources: List<Sequence<String>>,
        dest: File,
        stamp: String,
        base: String,
        inBase: (String, IntArray) -> Boolean,
        progress: Progress,
        names: List<String>,
    ): Result {
        val work = File(dest.absoluteFile.parentFile, ".${dest.name}$WORK")
        work.deleteRecursively()
        if (!work.mkdirs()) throw IOException("could not make $work")
        val temp = File(dest.path + TEMP)
        var output: Output? = null
        var done = false
        try {
            val runs = Runs(work)
            val kinds = ArrayList<Kind>()
            val counts = IntArray(sources.size)
            var lines = 0
            var unread = 0
            for ((i, source) in sources.withIndex()) {
                val kind = Kind()
                for (line in source) {
                    if (++lines and PROGRESS_EVERY == 0) progress.at(Stage.READ, lines, 0)
                    if (line.isBlank() || line.startsWith('#')) continue
                    if (runs.addLine(line, i, kind)) counts[i]++ else unread++
                }
                kinds += kind
            }
            progress.at(Stage.READ, lines, 0)
            runs.finish()
            val out = Output(work, inBase).also { output = it }
            val words = runs.size
            var merged = 0
            progress.at(Stage.MERGE, 0, words)
            // counted as the runs give them, each copy of a word too: [words] counts them so
            // a word no weight ranks is put with the least any source ranks (MUST-2): below every word
            // weighted, as the trie orders them, and alike whatever the source
            val floor = kinds.minOfOrNull { it.worst() }?.takeIf { it.isFinite() } ?: 0f
            val score = { w: Word -> kinds[w.source].score(w.weight).let { if (it.isNaN()) floor else it } }
            runs.merge(score, { if (++merged and PROGRESS_EVERY == 0) progress.at(Stage.MERGE, merged, words) }) { w, s -> out.add(w, s) }
            progress.at(Stage.MERGE, words, words)
            val made = out.finish()
            done = true
            unread += out.dropped
            temp.delete()
            RandomAccessFile(temp, "rw").use { f ->
                made.write(f, stamp, base, names.zip(counts.asList())) { written, of -> progress.at(Stage.WRITE, written, of) }
                f.fd.sync()
            }
            if (!temp.renameTo(dest)) throw IOException("could not write $dest")
            return Result(made.size, made.size - made.trieWords, unread)
        } finally {
            if (!done) output?.abandon()
            temp.delete()
            work.deleteRecursively()
        }
    }

    /** Whether [data]'s dictionary has a word read so: the `inBase` of [compile], for the dictionary typing reads. */
    fun inBase(data: PinyinData): (String, IntArray) -> Boolean = { text, syllables ->
        val dictionary = data.dictionary
        val node = dictionary.find(syllables)
        // compared in place, not each word made a String: a node of one syllable has hundreds,
        // asked for every word of a 1.9-million-word compile
        val vocabulary = data.vocabulary
        node >= 0 && (0 until dictionary.wordCount(node)).any { i ->
            val id = dictionary.word(node, i)
            vocabulary.length(id) == text.length && text.indices.all { vocabulary.char(id, it) == text[it] }
        }
    }

    // the slot of a text's hash in the text index: String.hashCode, as the JLS fixes it, spread
    internal fun slot(hash: Int, hashSize: Int): Int {
        var h = hash * -0x61c88647
        h = h xor (h ushr 16)
        return h and (hashSize - 1)
    }

    // what a source's weights are, from all of them
    private class Kind {
        var some = 0
        var logs = 0
        var best = Float.NEGATIVE_INFINITY
        private var least = Float.POSITIVE_INFINITY

        fun see(w: Float) {
            if (w == 0f) return
            some++
            if (w < 0f) logs++
            if (w > best) best = w
            if (w < least) least = w
        }

        // the score of the source's least weighted word: +inf for a source no weight ranks
        fun worst(): Float = if (some == 0) Float.POSITIVE_INFINITY else score(least).let { if (it.isNaN()) Float.POSITIVE_INFINITY else it }

        // log10 of the weight against the source's best: 0 for its best, NaN for none (the floor, in compile)
        fun score(w: Float): Float = when {
            w == 0f || some == 0 -> Float.NaN
            logs == some -> w - best
            logs == 0 -> log10(w / best)
            // signs mixed: no kind it could be
            else -> Float.NaN
        }
    }

    // words read, sorted on disk a run at a time
    private class Runs(private val dir: File) {
        private val files = ArrayList<File>()
        private var n = 0
        private val sylFrom = IntArray(RUN_WORDS + 1)
        private val textFrom = IntArray(RUN_WORDS + 1)
        // a run's syllables and characters: full, it is spilled, never grown (review NIT 3: long
        // words doubled them past the heap the compile is bounded by)
        private val syls = ShortArray(RUN_WORDS * 4)
        private val chars = CharArray(RUN_WORDS * 4)
        private val weights = FloatArray(RUN_WORDS)
        private val sources = IntArray(RUN_WORDS)
        private var total = 0

        /** The words read so far, the same word twice counted twice: what the merge goes through. */
        val size get() = total

        fun addLine(line: String, source: Int, kind: Kind): Boolean {
            val fields = splitValues(line)
            if (fields.size !in 2..3) return false
            val entry = LibimeImport.entry(fields[0], fields[1]) ?: return false
            val weight = (if (fields.size == 3) fields[2].toFloatOrNull() else 0f)?.takeIf { it.isFinite() } ?: return false
            if (entry.syllables.size > MAX_SYLLABLES || entry.text.length > MAX_SYLLABLES * 2) return false
            if (++total > MAX_LINES) throw IOException("more than $MAX_LINES lines")
            kind.see(weight)
            val text = entry.text
            val s = entry.syllables
            if (sylFrom[n] + s.size > syls.size || textFrom[n] + text.length > chars.size) spill()
            for (i in s.indices) syls[sylFrom[n] + i] = s[i].toShort()
            text.toCharArray(chars, textFrom[n])
            sylFrom[n + 1] = sylFrom[n] + s.size
            textFrom[n + 1] = textFrom[n] + text.length
            weights[n] = weight
            sources[n] = source
            if (++n == RUN_WORDS) spill()
            return true
        }

        private fun compare(a: Int, b: Int): Int {
            val la = sylFrom[a + 1] - sylFrom[a]
            val lb = sylFrom[b + 1] - sylFrom[b]
            for (i in 0 until minOf(la, lb)) {
                val c = syls[sylFrom[a] + i].compareTo(syls[sylFrom[b] + i])
                if (c != 0) return c
            }
            if (la != lb) return la.compareTo(lb)
            val ta = textFrom[a + 1] - textFrom[a]
            val tb = textFrom[b + 1] - textFrom[b]
            for (i in 0 until minOf(ta, tb)) {
                val c = chars[textFrom[a] + i].compareTo(chars[textFrom[b] + i])
                if (c != 0) return c
            }
            return ta.compareTo(tb)
        }

        private fun spill() {
            if (n == 0) return
            val order = sortIndices(n, ::compare)
            val file = File(dir, "run${files.size}")
            DataOutputStream(BufferedOutputStream(FileOutputStream(file), BUFFER)).use { out ->
                for (i in order) {
                    out.writeByte(sylFrom[i + 1] - sylFrom[i])
                    for (k in sylFrom[i] until sylFrom[i + 1]) out.writeShort(syls[k].toInt())
                    out.writeShort(textFrom[i + 1] - textFrom[i])
                    for (k in textFrom[i] until textFrom[i + 1]) out.writeChar(chars[k].code)
                    out.writeFloat(weights[i])
                    out.writeShort(sources[i])
                }
            }
            files += file
            n = 0
        }

        fun finish() = spill()

        // the runs merged: each word once, in order, scored the best of every time it came. One
        // score kept, not each time's: a line repeated millions of times is no more heap (MUST-1)
        fun merge(score: (Word) -> Float, taken: () -> Unit, each: (Word, Float) -> Unit) {
            val heads = PriorityQueue<Reader>(maxOf(files.size, 1)) { a, b -> a.word.compareTo(b.word) }
            files.forEach { f -> Reader(f).also { if (it.next()) heads += it else it.close() } }
            var last: Word? = null
            var best = 0f
            try {
                while (heads.isNotEmpty()) {
                    val r = heads.poll()
                    taken()
                    val w = r.word
                    val s = score(w)
                    if (last != null && last.compareTo(w) == 0) {
                        if (s > best) best = s
                    } else {
                        last?.let { each(it, best) }
                        last = w
                        best = s
                    }
                    if (r.next()) heads += r else r.close()
                }
                last?.let { each(it, best) }
            } finally {
                heads.forEach { it.close() }
            }
        }
    }

    private class Word(val syllables: IntArray, val text: String, val weight: Float, val source: Int) : Comparable<Word> {
        override fun compareTo(other: Word): Int {
            val n = minOf(syllables.size, other.syllables.size)
            for (i in 0 until n) {
                val c = syllables[i].compareTo(other.syllables[i])
                if (c != 0) return c
            }
            if (syllables.size != other.syllables.size) return syllables.size.compareTo(other.syllables.size)
            return text.compareTo(other.text)
        }

        fun sameReading(other: Word) = syllables.contentEquals(other.syllables)
    }

    private class Reader(file: File) {
        private val input = DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER))
        lateinit var word: Word

        fun next(): Boolean {
            val len = try {
                input.readUnsignedByte()
            } catch (_: EOFException) {
                return false
            }
            val s = IntArray(len) { input.readShort().toInt() }
            val t = CharArray(input.readUnsignedShort()) { input.readChar() }
            word = Word(s, String(t), input.readFloat(), input.readShort().toInt())
            return true
        }

        fun close() = input.close()
    }

    // a section written as it comes, little-endian, to a file of its own
    private class Stream(val file: File, buffer: Int = BUFFER) {
        private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), buffer))
        var count = 0L
            private set

        fun int(v: Int) {
            out.writeInt(Integer.reverseBytes(v))
            count++
        }

        fun short(v: Int) {
            out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            count++
        }

        fun char(v: Char) {
            out.writeChar(Character.reverseBytes(v).code)
            count++
        }

        fun float(v: Float) = int(java.lang.Float.floatToRawIntBits(v))

        fun close() = out.close()
    }

    // the words of one part (a depth of the trie's, or the base's), place by place: each one's
    // text length, characters and score; the base's syllables too, a trie word's being its node's
    private class Part(dir: File, name: String, withSyllables: Boolean, buffer: Int) {
        val lengths = Stream(File(dir, "$name.lengths"), buffer)
        val chars = Stream(File(dir, "$name.chars"), buffer)
        val scores = Stream(File(dir, "$name.scores"), buffer)
        val sylLengths = if (withSyllables) Stream(File(dir, "$name.sylLengths"), buffer) else null
        val syls = if (withSyllables) Stream(File(dir, "$name.syls"), buffer) else null
        var size = 0
            private set

        fun add(w: Word, score: Float) {
            size++
            for (c in w.text) chars.char(c)
            lengths.short(w.text.length)
            sylLengths?.short(w.syllables.size)
            syls?.let { s -> for (y in w.syllables) s.short(y) }
            scores.float(score)
        }

        fun close() = listOfNotNull(lengths, chars, scores, sylLengths, syls).forEach { it.close() }
    }

    // the distinct scores, up to what a u16 code tells apart: raw bits in a set of open addressing
    // (512 KB). Past that, none kept: the codes go linear (DictionaryFile's SCORES_LINEAR)
    private class ScoreSet {
        private val slots = IntArray(SCORE_SLOTS).also { it.fill(EMPTY) }
        var size = 0
            private set
        var overflow = false
            private set
        var least = 0f
            private set

        fun add(score: Float) {
            if (score < least) least = score
            if (overflow) return
            val bits = java.lang.Float.floatToRawIntBits(score)
            var i = (bits * -0x61c88647) ushr (Int.SIZE_BITS - SCORE_SLOT_BITS)
            while (true) {
                val at = slots[i]
                if (at == bits) return
                if (at == EMPTY) break
                i = (i + 1) and (SCORE_SLOTS - 1)
            }
            if (size == MAX_CODES) {
                overflow = true
                return
            }
            slots[i] = bits
            size++
        }

        // the codebook, ascending: a score's code its index
        fun book(): FloatArray {
            val out = FloatArray(size)
            var n = 0
            for (b in slots) if (b != EMPTY) out[n++] = java.lang.Float.intBitsToFloat(b)
            out.sort()
            return out
        }

        private companion object {
            // a NaN's bits: no score is NaN
            const val EMPTY = 0x7fc00001
        }
    }

    // the merged words into the file's parts and trie, a reading at a time
    private class Output(private val dir: File, private val inBase: (String, IntArray) -> Boolean) {
        // the trie's words a part per depth, so they come out breadth first, as its nodes; and the base's
        val depthWords = ArrayList<Part>()
        val base = Part(dir, "base", withSyllables = true, buffer = BUFFER)
        val scoreSet = ScoreSet()
        var trieWords = 0
            private set
        // a reading's words, least ranked at the head
        private val group = PriorityQueue<Pair<Word, Float>>(INITIAL_GROUP, compareBy<Pair<Word, Float>>({ it.second }).thenByDescending { it.first.text })
        var dropped = 0
            private set

        // the open path of the trie, by depth: each node's syllable, words and children so far
        private val pathSyl = IntArray(MAX_SYLLABLES + 1)
        private val pathWords = IntArray(MAX_SYLLABLES + 1)
        private val pathChildren = IntArray(MAX_SYLLABLES + 1)
        private var pathLength = 0
        private var lastReading = IntArray(0)
        private val depths = ArrayList<Stream>()

        fun add(w: Word, score: Float) {
            if (group.isNotEmpty() && !group.peek().first.sameReading(w)) flushGroup()
            // -0f as 0f: one code for both
            group += w to (score + 0f)
            // past the cap, the least ranked goes (NIT 1): kept by score, not by the order they came
            if (group.size > MAX_PER_READING) {
                group.poll()
                dropped++
            }
        }

        private fun flushGroup() {
            val reading = group.peek().first.syllables
            val mine = ArrayList<Pair<Word, Float>>(group.size)
            for (g in group) {
                if (inBase(g.first.text, reading)) {
                    base.add(g.first, g.second)
                    scoreSet.add(g.second)
                } else {
                    mine += g
                }
            }
            group.clear()
            capped()
            if (mine.isEmpty()) return
            // best first; then by text, so a compile is the same each time
            mine.sortWith(compareBy<Pair<Word, Float>>({ -it.second }).thenBy { it.first.text })
            enter(reading, mine.size)
            while (depthWords.size <= reading.size) {
                depthWords += Part(dir, "depth${depthWords.size}", withSyllables = false, buffer = SMALL_BUFFER)
            }
            val part = depthWords[reading.size]
            for ((w, s) in mine) {
                part.add(w, s)
                scoreSet.add(s)
            }
            trieWords += mine.size
            capped()
        }

        // the trie's and the base's together: the cap is of the file's words, its index sized by them
        private fun capped() {
            if (trieWords + base.size > MAX_WORDS) throw IOException("more than $MAX_WORDS words")
        }

        // the trie's node of [reading] given its words: the path closed where it parts from the last
        private fun enter(reading: IntArray, count: Int) {
            var common = 0
            while (common < minOf(reading.size, lastReading.size) && reading[common] == lastReading[common]) common++
            close(common)
            for (d in common + 1..reading.size) {
                pathChildren[d - 1]++
                pathSyl[d] = reading[d - 1]
                pathWords[d] = 0
                pathChildren[d] = 0
            }
            pathLength = reading.size
            pathWords[pathLength] = count
            lastReading = reading
        }

        // the nodes deeper than [depth] done: each to its depth's file, in the order of their
        // readings, which is the order of their words in the depth's part
        private fun close(depth: Int) {
            for (d in pathLength downTo depth + 1) writeNode(d)
            pathLength = depth
        }

        private fun writeNode(d: Int) {
            while (depths.size <= d) depths += Stream(File(dir, "nodes${depths.size}"), SMALL_BUFFER)
            val s = depths[d]
            s.int(pathSyl[d])
            s.int(pathWords[d])
            s.int(pathChildren[d])
        }

        fun finish(): Made {
            if (group.isNotEmpty()) flushGroup()
            close(0)
            writeNode(0)
            depthWords.forEach { it.close() }
            base.close()
            depths.forEach { it.close() }
            return Made(depthWords + base, trieWords, base.size, depths.map { it.file to (it.count / NODE_INTS).toInt() }, scoreSet)
        }

        // a compile failed or cancelled: its files' descriptors let go now, not at a GC (review NIT 4)
        fun abandon() {
            depthWords.forEach { runCatching { it.close() } }
            runCatching { base.close() }
            depths.forEach { runCatching { it.close() } }
        }
    }

    // what the file is made of, on disk, written into it a section at a time: [parts] in place
    // order (the trie's depths, breadth first, then the base's, the last)
    private class Made(
        private val parts: List<Part>,
        val trieWords: Int,
        apart: Int,
        private val depths: List<Pair<File, Int>>,
        private val scoreSet: ScoreSet,
    ) {
        val size = trieWords + apart
        private val base = parts.last()
        private val nodes = depths.sumOf { it.second }
        private val chars = parts.sumOf { it.chars.count }

        fun write(f: RandomAccessFile, stamp: String, baseOf: String, sources: List<Pair<String, Int>>, written: (Int, Int) -> Unit) {
            val hashSize = Integer.highestOneBit(maxOf(size, 1) * 2 - 1)
            val book = if (scoreSet.overflow) floatArrayOf(scoreSet.least) else scoreSet.book()
            // checked before a byte is written: a list of sources the reader would refuse fails the compile, not every open
            val header = try {
                DataFile.metaBytes(meta(stamp, baseOf, hashSize, sources, book.size))
            } catch (e: IllegalArgumentException) {
                throw IOException("dictionary header: ${e.message}", e)
            }
            // the text index first: its arrays, the most the write holds, let go before the rest
            val ids = listOf(
                DictionaryFile.META, DictionaryFile.TEXT_PLACES, DictionaryFile.TEXT_BUCKETS, DictionaryFile.TEXT_START,
                DictionaryFile.CHARS, DictionaryFile.SYL_START, DictionaryFile.SYLLABLES, DictionaryFile.SCORE_BOOK,
                DictionaryFile.SCORES, DictionaryFile.FIRST_CHILD, DictionaryFile.NODE_SYLLABLE, DictionaryFile.WORD_START,
            )
            val steps = ids.size
            var step = 0
            DataFile.stream(f, DataFile.KIND_USER_DICTIONARY, DictionaryFile.VERSION, ids) { id, raw ->
                written(step++, steps)
                val out = BufferedOutputStream(raw, BUFFER)
                if (id == DictionaryFile.META) out.write(header) else writeSection(id, out, hashSize, book)
                out.flush()
            }
            written(steps, steps)
        }

        // the text index's bucket ends, from TEXT_PLACES to TEXT_BUCKETS, then let go
        private var buckets: IntArray? = null

        private fun writeSection(id: Int, out: OutputStream, hashSize: Int, book: FloatArray) {
            when (id) {
                DictionaryFile.TEXT_PLACES -> buckets = writeTextPlaces(hashSize, out)
                DictionaryFile.TEXT_BUCKETS -> {
                    val b = checkNotNull(buckets)
                    buckets = null
                    EliasFano.Writer(out, hashSize + 1, size).apply {
                        add(0)
                        for (slot in 0 until hashSize) add(b[slot])
                    }.finish()
                }
                DictionaryFile.TEXT_START -> writeStarts(out, chars.toInt(), size, parts.map { it.lengths })
                DictionaryFile.CHARS -> parts.forEach { copy(it.chars.file, out) }
                DictionaryFile.SYL_START -> writeStarts(out, checkNotNull(base.syls).count.toInt(), size - trieWords, listOf(checkNotNull(base.sylLengths)))
                DictionaryFile.SYLLABLES -> copy(checkNotNull(base.syls).file, out)
                DictionaryFile.SCORE_BOOK -> LittleEndian(out).apply { book.forEach { int(java.lang.Float.floatToRawIntBits(it)) } }.flush()
                DictionaryFile.SCORES -> LittleEndian(out).apply {
                    for (part in parts) readInts(part.scores.file) { short(code(java.lang.Float.intBitsToFloat(it), book)) }
                }.flush()
                DictionaryFile.FIRST_CHILD, DictionaryFile.NODE_SYLLABLE, DictionaryFile.WORD_START -> writeTrie(id, out)
                else -> error("section $id")
            }
        }

        // where each of [count] runs starts, their lengths in [lengths], and where the last ends ([end])
        private fun writeStarts(out: OutputStream, end: Int, count: Int, lengths: List<Stream>) {
            val w = BitPacked.Writer(out, BitPacked.widthFor(end), count + 1)
            var at = 0
            w.add(at)
            for (l in lengths) readShorts(l.file) { at += it; w.add(at) }
            w.finish()
        }

        // a score's u16 code: its place in the book, or its step from the floor (the book's one) up to 0
        private fun code(score: Float, book: FloatArray): Int = if (!scoreSet.overflow) {
            java.util.Arrays.binarySearch(book, score).also { check(it >= 0) { "score $score not in the book" } }
        } else {
            val floor = book[0]
            Math.round((1 - score / floor) * DictionaryFile.CODE_MASK).coerceIn(0, DictionaryFile.CODE_MASK)
        }

        private fun meta(stamp: String, baseOf: String, hashSize: Int, sources: List<Pair<String, Int>>, codes: Int) = mapOf(
            "words" to size, "trieWords" to trieWords, "nodes" to nodes, "hashSize" to hashSize,
            "chars" to chars, "syllables" to checkNotNull(base.syls).count,
            "scores" to if (scoreSet.overflow) DictionaryFile.SCORES_LINEAR else DictionaryFile.SCORES_BOOK, "scoreCodes" to codes,
            "syllableCount" to Syllables.count, "syllableChecksum" to Syllables.checksum(),
            "stamp" to stamp.replace('\n', ' ').replace('\r', ' '), "base" to baseOf.replace('\n', ' ').replace('\r', ' '),
            // a name holds no '/', a file's; one with a line break is kept with spaces, and found by none
            SOURCES to sources.joinToString("/") { "${it.first.replace('\n', ' ').replace('\r', ' ')}/${it.second}" },
        ).mapValues { it.value.toString() }

        // the places by the slot of their text's hash, each slot's in place order; returns where
        // each slot's run ends. The one thing held whole: 4 bytes a slot and a place (about 16 MB
        // at 2 million words), let go once the buckets are written
        private fun writeTextPlaces(hashSize: Int, out: OutputStream): IntArray {
            val next = IntArray(hashSize)
            forEachHash { _, h -> next[slot(h, hashSize)]++ }
            var start = 0
            for (s in 0 until hashSize) start += next[s].also { next[s] = start }
            val places = IntArray(size)
            forEachHash { p, h -> places[next[slot(h, hashSize)]++] = p }
            BitPacked.Writer(out, BitPacked.widthFor(maxOf(size - 1, 0)), size).apply { places.forEach(::add) }.finish()
            return next
        }

        // each place with its text's String.hashCode, from the parts' lengths and characters
        private fun forEachHash(each: (Int, Int) -> Unit) {
            var p = 0
            for (part in parts) {
                DataInputStream(BufferedInputStream(FileInputStream(part.chars.file), BUFFER)).use { chars ->
                    readShorts(part.lengths.file) { n ->
                        var h = 0
                        repeat(n) { h = 31 * h + Character.reverseBytes(chars.readChar()).code }
                        each(p++, h)
                    }
                }
            }
        }

        // the depths' nodes, root first, a depth after another: breadth first, each node's children a run
        private fun writeTrie(id: Int, out: OutputStream) {
            val (width, count) = when (id) {
                DictionaryFile.FIRST_CHILD -> BitPacked.widthFor(nodes) to nodes + 1
                DictionaryFile.NODE_SYLLABLE -> BitPacked.widthFor(Syllables.count - 1) to nodes
                else -> BitPacked.widthFor(trieWords) to nodes + 1
            }
            val w = BitPacked.Writer(out, width, count)
            var children = 1
            var words = 0
            val row = IntArray(NODE_INTS)
            for ((file, _) in depths) {
                var i = 0
                readInts(file) {
                    row[i++] = it
                    if (i == NODE_INTS) {
                        i = 0
                        when (id) {
                            DictionaryFile.FIRST_CHILD -> w.add(children).also { children += row[2] }
                            DictionaryFile.NODE_SYLLABLE -> w.add(row[0])
                            else -> w.add(words).also { words += row[1] }
                        }
                    }
                }
            }
            when (id) {
                DictionaryFile.FIRST_CHILD -> w.add(nodes)
                DictionaryFile.WORD_START -> w.add(words)
            }
            w.finish()
        }

        private fun copy(file: File, out: OutputStream) = FileInputStream(file).use { it.copyTo(out, BUFFER) }

        private fun readInts(file: File, each: (Int) -> Unit) {
            DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER)).use { input ->
                val n = file.length() / 4
                for (i in 0 until n) each(Integer.reverseBytes(input.readInt()))
            }
        }

        private fun readShorts(file: File, each: (Int) -> Unit) {
            DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER)).use { input ->
                val n = file.length() / 2
                for (i in 0 until n) each(java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff)
            }
        }
    }

    private class LittleEndian(out: OutputStream) {
        private val out = DataOutputStream(BufferedOutputStream(out, BUFFER))
        fun int(v: Int) = out.writeInt(Integer.reverseBytes(v))
        fun short(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        fun flush() = out.flush()
    }

    // a bottom-up merge sort of 0 until n: no boxing, one array of scratch
    private fun sortIndices(n: Int, compare: (Int, Int) -> Int): IntArray {
        var a = IntArray(n) { it }
        var t = IntArray(n)
        var width = 1
        while (width < n) {
            var lo = 0
            while (lo < n) {
                val mid = minOf(lo + width, n)
                val hi = minOf(lo + 2 * width, n)
                var i = lo
                var j = mid
                var k = lo
                while (i < mid && j < hi) t[k++] = if (compare(a[i], a[j]) <= 0) a[i++] else a[j++]
                while (i < mid) t[k++] = a[i++]
                while (j < hi) t[k++] = a[j++]
                lo = hi
            }
            val s = a
            a = t
            t = s
            width *= 2
        }
        return a
    }

    // words sorted in the heap at a time: about 4 MB of them
    private const val RUN_WORDS = 1 shl 17
    private const val INITIAL_GROUP = 16
    private const val NODE_INTS = 3
    private const val BUFFER = 1 shl 16
    // the streams kept one a depth (up to 33 of words', 33 of nodes'): smaller, so all of them stay a MB or two
    private const val SMALL_BUFFER = 1 shl 14
    // a u16 code tells this many scores apart; the set that finds them has twice the slots
    private const val MAX_CODES = 1 shl 16
    private const val SCORE_SLOT_BITS = 17
    private const val SCORE_SLOTS = 1 shl SCORE_SLOT_BITS
    private const val PROGRESS_EVERY = (1 shl 14) - 1
}
