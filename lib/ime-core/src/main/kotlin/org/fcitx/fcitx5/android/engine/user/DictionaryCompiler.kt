/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.BitPacked
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.EliasFano
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.splitValues
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.imports.WordText
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
 * Compiles the dictionaries the user merged into one [DictionaryFile], once per change. It runs in the keyboard's
 * process, so its heap is bounded whatever the size: lines are sorted on disk in runs ([RUN_WORDS] in the heap),
 * merged, and written a section at a time (rime-frost's 1.9 million words in 48 MB, DictionaryFileBoundedHeapTest).
 *
 * Words come out in the trie's breadth-first order, a node's words by score. Those the base dictionary has
 * (`inBase`) are kept past [DictionaryFile.trieWords], out of the trie: a second arc would double them and bring
 * back one the user blocked.
 */
object DictionaryCompiler {

    const val MAX_WORDS = 4_000_000

    /** More lines than this is no source of [MAX_WORDS]; twice, so a file listed with itself still compiles. */
    const val MAX_LINES = MAX_WORDS * 2

    const val MAX_SYLLABLES = 32

    /** More words than this of one reading are left out: a reading's words are held together to rank them (rime-frost's most is under a thousand). */
    const val MAX_PER_READING = 16_384

    private const val SOURCES = "sources"

    /** How many words each source of [file] read, by name, from its unchecked header; none if unreadable. */
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

    enum class Stage { READ, MERGE, WRITE }

    /** Told how far a compile is; it may throw to cancel, leaving the existing file. */
    fun interface Progress {
        fun at(stage: Stage, done: Int, of: Int)
    }

    class Result(val words: Int, val inBase: Int, val unread: Int)

    /** Whether [name] is a half-made compile or CodedIndex build (`<dest>.tmp`, `.<dest>.build`): left out of backups, swept. The app asks here so a rename cannot break its filter. */
    fun isWorkFile(name: String): Boolean =
        name.endsWith(".ymd$TEMP") || name.endsWith(".ymc$TEMP") || (name.startsWith(".") && name.endsWith(WORK))

    private const val TEMP = ".tmp"
    private const val WORK = ".build"

    /**
     * The words of [sources] (libime's text format, as [WordText.dictionaryEntry] reads) into [dest], replaced whole.
     * A source's weights are read as one kind over the whole source: all in (0, 1] probabilities, all > 1 counts,
     * all < 0 log10; 0 is none, mixed signs are none. Each source's best word scores 0, so no source outranks another
     * by scale; a word in two sources keeps its best. [names] are kept with their word counts for the settings.
     *
     * Heap: a run (~6 MB), the depth streams (~2 MB), the score book (512 KB), and the text index while written
     * (~16 MB at 2 million words). Up to ~140 files open at once, far below Android's 32K.
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
        // a phone's heap may be nearly full already: a failed compile, recorded for this stamp, not a crash retried at
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
            // a word no weight ranks gets the least any source ranks: below every weighted word, whatever the source
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

    fun inBase(data: PinyinData): (String, IntArray) -> Boolean = { text, syllables ->
        val dictionary = data.dictionary
        val node = dictionary.find(syllables)
        // compared in place, not as Strings: asked for every word of a 1.9-million-word compile
        val vocabulary = data.vocabulary
        node >= 0 && (0 until dictionary.wordCount(node)).any { i ->
            val id = dictionary.word(node, i)
            vocabulary.length(id) == text.length && text.indices.all { vocabulary.char(id, it) == text[it] }
        }
    }

    // String.hashCode, as the JLS fixes it, spread
    internal fun slot(hash: Int, hashSize: Int): Int {
        var h = hash * -0x61c88647
        h = h xor (h ushr 16)
        return h and (hashSize - 1)
    }

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

        fun worst(): Float = if (some == 0) Float.POSITIVE_INFINITY else score(least).let { if (it.isNaN()) Float.POSITIVE_INFINITY else it }

        fun score(w: Float): Float = when {
            w == 0f || some == 0 -> Float.NaN
            logs == some -> w - best
            logs == 0 -> log10(w / best)
            else -> Float.NaN
        }
    }

    private class Runs(private val dir: File) {
        private val files = ArrayList<File>()
        private var n = 0
        private val sylFrom = IntArray(RUN_WORDS + 1)
        private val textFrom = IntArray(RUN_WORDS + 1)
        // full, a run is spilled, never grown: long words doubled them past the bounded heap
        private val syls = ShortArray(RUN_WORDS * 4)
        private val chars = CharArray(RUN_WORDS * 4)
        private val weights = FloatArray(RUN_WORDS)
        private val sources = IntArray(RUN_WORDS)
        private var total = 0

        val size get() = total

        fun addLine(line: String, source: Int, kind: Kind): Boolean {
            val fields = splitValues(line)
            if (fields.size !in 2..3) return false
            val entry = WordText.entry(fields[0], fields[1]) ?: return false
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

        // each word once, scored its best; one score kept, so a line repeated millions of times costs no heap
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

    // a part's words place by place: text length, characters, score; the base's syllables too (a trie word's are its node's)
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

    // the distinct scores, up to what a u16 code tells apart (512 KB); past that the codes go linear (SCORES_LINEAR)
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

    private class Output(private val dir: File, private val inBase: (String, IntArray) -> Boolean) {
        // a part per depth, so words come out breadth first as the nodes do; and the base's
        val depthWords = ArrayList<Part>()
        val base = Part(dir, "base", withSyllables = true, buffer = BUFFER)
        val scoreSet = ScoreSet()
        var trieWords = 0
            private set
        private val group = PriorityQueue<Pair<Word, Float>>(INITIAL_GROUP, compareBy<Pair<Word, Float>>({ it.second }).thenByDescending { it.first.text })
        var dropped = 0
            private set

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
            // past the cap the least ranked goes, not the latest
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
            // best first; then by text, so a compile is deterministic
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

        private fun capped() {
            if (trieWords + base.size > MAX_WORDS) throw IOException("more than $MAX_WORDS words")
        }

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

        // failed or cancelled: descriptors let go now, not at a GC
        fun abandon() {
            depthWords.forEach { runCatching { it.close() } }
            runCatching { base.close() }
            depths.forEach { runCatching { it.close() } }
        }
    }

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
            // checked before a byte is written: a source list the reader would refuse fails the compile, not every open
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
                DictionaryFile.SCORE_BOOK -> BufferedLittleEndian(out).apply { book.forEach { int(java.lang.Float.floatToRawIntBits(it)) } }.flush()
                DictionaryFile.SCORES -> BufferedLittleEndian(out).apply {
                    for (part in parts) readInts(part.scores.file) { short(code(java.lang.Float.intBitsToFloat(it), book)) }
                }.flush()
                DictionaryFile.FIRST_CHILD, DictionaryFile.NODE_SYLLABLE, DictionaryFile.WORD_START -> writeTrie(id, out)
                else -> error("section $id")
            }
        }

        private fun writeStarts(out: OutputStream, end: Int, count: Int, lengths: List<Stream>) {
            val w = BitPacked.Writer(out, BitPacked.widthFor(end), count + 1)
            var at = 0
            w.add(at)
            for (l in lengths) readShorts(l.file) { at += it; w.add(at) }
            w.finish()
        }

        // a score's u16 code: its book index, or its step from the floor up to 0
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
            // a name holds no '/'; a line break becomes a space
            SOURCES to sources.joinToString("/") { "${it.first.replace('\n', ' ').replace('\r', ' ')}/${it.second}" },
        ).mapValues { it.value.toString() }

        // the places by text-hash slot; the one thing held whole (~16 MB at 2 million words), let go after
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

        private fun writeTrie(id: Int, out: OutputStream) {
            val (width, count) = when (id) {
                DictionaryFile.FIRST_CHILD -> BitPacked.widthFor(nodes) to nodes + 1
                DictionaryFile.NODE_SYLLABLE -> BitPacked.widthFor(Syllables.count - 1) to nodes
                DictionaryFile.WORD_START -> BitPacked.widthFor(trieWords) to nodes + 1
                else -> error("section $id")
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
                            DictionaryFile.WORD_START -> w.add(words).also { words += row[1] }
                            else -> error("section $id")
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

    // buffered and with shorts: engine.data's LittleEndian writes straight through and has neither
    private class BufferedLittleEndian(out: OutputStream) {
        private val out = DataOutputStream(BufferedOutputStream(out, BUFFER))
        fun int(v: Int) = out.writeInt(Integer.reverseBytes(v))
        fun short(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        fun flush() = out.flush()
    }

    // a bottom-up merge sort of 0 until n: no boxing, one scratch array
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

    private const val RUN_WORDS = 1 shl 17
    private const val INITIAL_GROUP = 16
    private const val NODE_INTS = 3
    private const val BUFFER = 1 shl 16
    // one stream per depth (up to 33 each of words and nodes): small, so all of them stay a MB or two
    private const val SMALL_BUFFER = 1 shl 14
    private const val MAX_CODES = 1 shl 16
    private const val SCORE_SLOT_BITS = 17
    private const val SCORE_SLOTS = 1 shl SCORE_SLOT_BITS
    private const val PROGRESS_EVERY = (1 shl 14) - 1
}
