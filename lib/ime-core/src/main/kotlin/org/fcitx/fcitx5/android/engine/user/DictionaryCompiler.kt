/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.DataFormatException
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
 * section at a time; the largest thing held is the text index, 8 bytes a word. Rime-frost's 1.9
 * million words compile in 48 MB (DictionaryFileBoundedHeapTest proves 2 million in that).
 *
 * The words come out in their trie's order, so a node's words are a run of places: by syllables,
 * a node's words by score, best first. Those the base dictionary has (`inBase`) are kept apart,
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
     * Heap: a run (about 6 MB) and, as the file is written, its text index, 8 bytes a word up to
     * 16 (about 17 MB at 2 million words, 33 MB at [MAX_WORDS]): within 48 MB for rime-frost's 1.9
     * million (DictionaryFileBoundedHeapTest), not at the cap (review NIT 2).
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
    private class Stream(val file: File) {
        private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), BUFFER))
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

    // the words of one part (the trie's, or the base's), place by place
    private class Part(dir: File, name: String) {
        val textEnd = Stream(File(dir, "$name.textEnd"))
        val chars = Stream(File(dir, "$name.chars"))
        val sylEnd = Stream(File(dir, "$name.sylEnd"))
        val syls = Stream(File(dir, "$name.syls"))
        val scores = Stream(File(dir, "$name.scores"))
        val hashes = Stream(File(dir, "$name.hashes"))
        var size = 0
            private set

        fun add(w: Word, score: Float) {
            size++
            for (c in w.text) chars.char(c)
            textEnd.int(chars.count.toInt())
            for (s in w.syllables) syls.short(s)
            sylEnd.int(syls.count.toInt())
            scores.float(score)
            hashes.int(w.text.hashCode())
        }

        fun close() = listOf(textEnd, chars, sylEnd, syls, scores, hashes).forEach { it.close() }
    }

    // the merged words into the file's parts and trie, a reading at a time
    private class Output(private val dir: File, private val inBase: (String, IntArray) -> Boolean) {
        val trie = Part(dir, "trie")
        val base = Part(dir, "base")
        // a reading's words, least ranked at the head
        private val group = PriorityQueue<Pair<Word, Float>>(INITIAL_GROUP, compareBy<Pair<Word, Float>>({ it.second }).thenByDescending { it.first.text })
        var dropped = 0
            private set

        // the open path of the trie, by depth: each node's syllable, words and children so far
        private val pathSyl = IntArray(MAX_SYLLABLES + 1)
        private val pathFrom = IntArray(MAX_SYLLABLES + 1)
        private val pathWords = IntArray(MAX_SYLLABLES + 1)
        private val pathChildren = IntArray(MAX_SYLLABLES + 1)
        private var pathLength = 0
        private var lastReading = IntArray(0)
        private val depths = ArrayList<Stream>()

        fun add(w: Word, score: Float) {
            if (group.isNotEmpty() && !group.peek().first.sameReading(w)) flushGroup()
            group += w to score
            // past the cap, the least ranked goes (NIT 1): kept by score, not by the order they came
            if (group.size > MAX_PER_READING) {
                group.poll()
                dropped++
            }
        }

        private fun flushGroup() {
            val reading = group.peek().first.syllables
            val mine = ArrayList<Pair<Word, Float>>(group.size)
            for (g in group) if (inBase(g.first.text, reading)) base.add(g.first, g.second) else mine += g
            group.clear()
            capped()
            if (mine.isEmpty()) return
            // best first; then by text, so a compile is the same each time
            mine.sortWith(compareBy<Pair<Word, Float>>({ -it.second }).thenBy { it.first.text })
            enter(reading, trie.size, mine.size)
            for ((w, s) in mine) trie.add(w, s)
            capped()
        }

        // the trie's and the base's together: the cap is of the file's words, its index sized by them
        private fun capped() {
            if (trie.size + base.size > MAX_WORDS) throw IOException("more than $MAX_WORDS words")
        }

        // the trie's node of [reading] given its words: the path closed where it parts from the last
        private fun enter(reading: IntArray, from: Int, count: Int) {
            var common = 0
            while (common < minOf(reading.size, lastReading.size) && reading[common] == lastReading[common]) common++
            close(common)
            for (d in common + 1..reading.size) {
                pathChildren[d - 1]++
                pathSyl[d] = reading[d - 1]
                pathFrom[d] = 0
                pathWords[d] = 0
                pathChildren[d] = 0
            }
            pathLength = reading.size
            pathFrom[pathLength] = from
            pathWords[pathLength] = count
            lastReading = reading
        }

        // the nodes deeper than [depth] done: each to its depth's file, in the order of their readings
        private fun close(depth: Int) {
            for (d in pathLength downTo depth + 1) writeNode(d)
            pathLength = depth
        }

        private fun writeNode(d: Int) {
            while (depths.size <= d) depths += Stream(File(dir, "depth${depths.size}"))
            val s = depths[d]
            s.int(pathSyl[d])
            s.int(pathFrom[d])
            s.int(pathWords[d])
            s.int(pathChildren[d])
        }

        fun finish(): Made {
            if (group.isNotEmpty()) flushGroup()
            close(0)
            writeNode(0)
            trie.close()
            base.close()
            depths.forEach { it.close() }
            return Made(trie, base, depths.map { it.file to (it.count / 4).toInt() })
        }

        // a compile failed or cancelled: its files' descriptors let go now, not at a GC (review NIT 4)
        fun abandon() {
            runCatching { trie.close() }
            runCatching { base.close() }
            depths.forEach { runCatching { it.close() } }
        }
    }

    // what the file is made of, on disk, written into it a section at a time
    private class Made(private val trie: Part, private val base: Part, private val depths: List<Pair<File, Int>>) {
        val trieWords = trie.size
        val size = trie.size + base.size
        private val nodes = depths.sumOf { it.second }

        fun write(f: RandomAccessFile, stamp: String, baseOf: String, sources: List<Pair<String, Int>>, written: (Int, Int) -> Unit) {
            val hashSize = Integer.highestOneBit(maxOf(size, 1) * 2 - 1)
            // checked before a byte is written: a list of sources the reader would refuse fails the compile, not every open
            val header = try {
                DataFile.metaBytes(meta(stamp, baseOf, hashSize, sources))
            } catch (e: IllegalArgumentException) {
                throw IOException("dictionary header: ${e.message}", e)
            }
            val ids = listOf(
                DictionaryFile.META, DictionaryFile.TEXT_START, DictionaryFile.CHARS, DictionaryFile.SYL_START,
                DictionaryFile.SYLLABLES, DictionaryFile.SCORES, DictionaryFile.TEXT_HASH, DictionaryFile.TEXT_NEXT,
                DictionaryFile.FIRST_CHILD, DictionaryFile.NODE_SYLLABLE, DictionaryFile.WORD_FROM, DictionaryFile.WORD_COUNT,
            )
            // the steps of writing it: the text index, then each section
            val steps = ids.size + 1
            written(0, steps)
            val index = textIndex(hashSize)
            var step = 1
            DataFile.stream(f, DataFile.KIND_USER_DICTIONARY, DictionaryFile.VERSION, ids) { id, out ->
                written(step++, steps)
                val o = LittleEndian(out)
                when (id) {
                    DictionaryFile.META -> out.write(header)
                    DictionaryFile.TEXT_START -> {
                        o.int(0)
                        readInts(trie.textEnd.file) { o.int(it) }
                        val plus = trie.chars.count.toInt()
                        readInts(base.textEnd.file) { o.int(it + plus) }
                    }
                    DictionaryFile.CHARS -> listOf(trie.chars, base.chars).forEach { copy(it.file, out) }
                    DictionaryFile.SYL_START -> {
                        o.int(0)
                        readInts(trie.sylEnd.file) { o.int(it) }
                        val plus = trie.syls.count.toInt()
                        readInts(base.sylEnd.file) { o.int(it + plus) }
                    }
                    DictionaryFile.SYLLABLES -> listOf(trie.syls, base.syls).forEach { copy(it.file, out) }
                    DictionaryFile.SCORES -> listOf(trie.scores, base.scores).forEach { copy(it.file, out) }
                    DictionaryFile.TEXT_HASH -> index.first.forEach(o::int)
                    DictionaryFile.TEXT_NEXT -> index.second.forEach(o::int)
                    DictionaryFile.FIRST_CHILD, DictionaryFile.NODE_SYLLABLE, DictionaryFile.WORD_FROM, DictionaryFile.WORD_COUNT -> writeTrie(id, o)
                    else -> error("section $id")
                }
                o.flush()
            }
            written(steps, steps)
        }

        private fun meta(stamp: String, baseOf: String, hashSize: Int, sources: List<Pair<String, Int>>) = mapOf(
            "words" to size, "trieWords" to trieWords, "nodes" to nodes, "hashSize" to hashSize,
            "chars" to trie.chars.count + base.chars.count, "syllables" to trie.syls.count + base.syls.count,
            "syllableCount" to Syllables.count, "syllableChecksum" to Syllables.checksum(),
            "stamp" to stamp.replace('\n', ' ').replace('\r', ' '), "base" to baseOf.replace('\n', ' ').replace('\r', ' '),
            // a name holds no '/', a file's; one with a line break is kept with spaces, and found by none
            SOURCES to sources.joinToString("/") { "${it.first.replace('\n', ' ').replace('\r', ' ')}/${it.second}" },
        ).mapValues { it.value.toString() }

        // the one thing held whole (8 bytes a word): by the text's hash, its first place + 1, and each place's next
        private fun textIndex(hashSize: Int): Pair<IntArray, IntArray> {
            val heads = IntArray(hashSize)
            val next = IntArray(size)
            var p = 0
            for (file in listOf(trie.hashes.file, base.hashes.file)) {
                readInts(file) { h ->
                    val slot = slot(h, hashSize)
                    next[p] = heads[slot]
                    heads[slot] = ++p
                }
            }
            return heads to next
        }

        // the depths' nodes, root first, a depth after another: breadth first, each node's children a run
        private fun writeTrie(id: Int, o: LittleEndian) {
            var children = 1
            val row = IntArray(NODE_INTS)
            for ((file, _) in depths) {
                var i = 0
                readInts(file) {
                    row[i++] = it
                    if (i == NODE_INTS) {
                        i = 0
                        when (id) {
                            DictionaryFile.FIRST_CHILD -> o.int(children).also { children += row[3] }
                            DictionaryFile.NODE_SYLLABLE -> o.short(row[0])
                            DictionaryFile.WORD_FROM -> o.int(row[1])
                            DictionaryFile.WORD_COUNT -> o.int(row[2])
                            else -> error("section $id")
                        }
                    }
                }
            }
            if (id == DictionaryFile.FIRST_CHILD) o.int(nodes)
        }

        private fun copy(file: File, out: OutputStream) = FileInputStream(file).use { it.copyTo(out, BUFFER) }

        private fun readInts(file: File, each: (Int) -> Unit) {
            DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER)).use { input ->
                val n = file.length() / 4
                for (i in 0 until n) each(Integer.reverseBytes(input.readInt()))
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
    private const val NODE_INTS = 4
    private const val BUFFER = 1 shl 16
    private const val PROGRESS_EVERY = (1 shl 14) - 1
}
