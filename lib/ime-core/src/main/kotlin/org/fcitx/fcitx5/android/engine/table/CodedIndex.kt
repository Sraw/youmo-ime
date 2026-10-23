/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * The words of a merged [DictionaryFile] coded for one table, as [CodedWords] codes the few the
 * user added: those of two characters or more, each text once under its code, none the table has
 * so. 0.3.1 coded them into a TreeMap per table, about 250 MB for rime-frost's 1.9 million words,
 * on a thread that did not catch an OutOfMemoryError; here they are sorted on disk a run at a time
 * ([RUN] in the heap) and the result is mapped, so a table costs the heap nothing for them.
 *
 * The file: a header ([MAGIC], [VERSION], the key it was built for, the code width, the count),
 * then the records sorted by code length, code, then place, each the code padded with `\u0000`
 * to the width and the word's place in the dictionary file, then a CRC32 of the records. By
 * length first, so a table session reads the words of a prefix a code length at a time, as it
 * shows them with codes sorted by length, and only as far as it shows ([longer]): a letter of
 * 五笔 leads to some 80,000 of rime-frost's words, which 22 MB a key made (and dropped) whole.
 * Built again whenever the key differs (another table's data, another merged file): it is a
 * cache, never migrated.
 */
class CodedIndex private constructor(private val records: ByteBuffer, private val width: Int, val size: Int) {

    private val recordBytes = width * 2 + 4

    /** The words under codes starting with [prefix], code and text, read from [dictionary], by code. */
    fun words(prefix: String, dictionary: DictionaryFile): List<Pair<String, String>> =
        if (prefix.isEmpty()) emptyList() else (exact(prefix, dictionary) + longer(prefix, byLength = false, dictionary)).toList()

    /** The words under [code] itself, by place. */
    fun exact(code: String, dictionary: DictionaryFile): List<Pair<String, String>> =
        if (code.isEmpty() || code.length > width) emptyList() else run(code.length, code, dictionary).toList()

    /**
     * The words under codes that start with [prefix] and are longer, read as they are asked for:
     * by length, then code ([byLength]), or by code alone.
     */
    fun longer(prefix: String, byLength: Boolean, dictionary: DictionaryFile): Sequence<Pair<String, String>> {
        if (prefix.isEmpty()) return emptySequence()
        val runs = (prefix.length + 1..width).map { run(it, prefix, dictionary) }
        return if (byLength) runs.asSequence().flatten() else byCode(runs)
    }

    fun leadsAnywhere(prefix: String): Boolean =
        prefix.isNotEmpty() && (prefix.length..width).any { length -> lowerBound(length, prefix).let { it < size && compare(it, length, prefix) == 0 } }

    /** Whether [text] is here under [code] exactly. */
    fun has(code: String, text: String, dictionary: DictionaryFile): Boolean = exact(code, dictionary).any { it.second == text }

    // the records of codes [length] long starting with [prefix], in order
    private fun run(length: Int, prefix: String, dictionary: DictionaryFile): Sequence<Pair<String, String>> = sequence {
        var i = lowerBound(length, prefix)
        while (i < size && compare(i, length, prefix) == 0) {
            val place = place(i)
            // a place past the file's: a file built for another, which the key should have caught
            if (place < dictionary.size) yield(code(i) to dictionary.text(place))
            i++
        }
    }

    // [runs], each in code order, merged so: a code's words by place, as they are in its run. A
    // scan of the heads, not a heap as [Runs.merge]: one run per code length, a few at most
    private fun byCode(runs: List<Sequence<Pair<String, String>>>): Sequence<Pair<String, String>> = sequence {
        val heads = runs.map { it.iterator() }.filter { it.hasNext() }.map { it to it.next() }.toMutableList()
        while (heads.isNotEmpty()) {
            val k = heads.indices.minBy { heads[it].second.first }
            val (it, word) = heads[k]
            yield(word)
            if (it.hasNext()) heads[k] = it to it.next() else heads.removeAt(k)
        }
    }

    private fun char(i: Int, k: Int): Char = records.getChar(i * recordBytes + k * 2)

    private fun place(i: Int): Int = records.getInt(i * recordBytes + width * 2)

    private fun length(i: Int): Int {
        var n = 0
        while (n < width && char(i, n) != PAD) n++
        return n
    }

    private fun code(i: Int): String = String(CharArray(length(i)) { char(i, it) })

    // the record against codes [length] long that start with [prefix]: 0 for one of them
    private fun compare(i: Int, length: Int, prefix: String): Int {
        val n = length(i)
        if (n != length) return n.compareTo(length)
        for (k in prefix.indices) {
            val c = char(i, k)
            if (c != prefix[k]) return c.compareTo(prefix[k])
        }
        return 0
    }

    private fun lowerBound(length: Int, prefix: String): Int {
        if (prefix.length > length) return size
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compare(mid, length, prefix) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        internal const val MAGIC = 0x594d4331 // YMC1
        internal const val VERSION = 2
        private const val PAD = '\u0000'

        // records sorted in the heap at a time: a few MB of them
        internal const val RUN = 1 shl 17
        private const val BUFFER = 1 shl 16
        private const val STOP_CHECK = 4096

        /** No words: what a build stopped early returns, dropped by its caller. */
        val NONE = CodedIndex(ByteBuffer.allocate(0), 1, 0)

        /**
         * The index at [file] if it was built for [key] and reads whole; null if it is not there,
         * is of another key, or is damaged (built again then). Read through once for its CRC:
         * off the input thread.
         */
        fun open(file: File, key: String): CodedIndex? = try {
            RandomAccessFile(file, "r").use { f ->
                val header = DataInputStream(BufferedInputStream(FileInputStream(f.fd), BUFFER))
                if (header.readInt() != MAGIC || header.readInt() != VERSION || header.readUTF() != key) return null
                val width = header.readInt()
                val size = header.readInt()
                val start = headerSize(key)
                val bytes = size.toLong() * (width * 2 + 4)
                if (width < 1 || size < 0 || f.length() != start + bytes + 8) return null
                val records = f.channel.map(FileChannel.MapMode.READ_ONLY, start.toLong(), bytes)
                val crc = crc32(records.duplicate())
                val stored = f.channel.map(FileChannel.MapMode.READ_ONLY, start + bytes, 8).getLong()
                if (crc != stored) null else CodedIndex(records, width, size)
            }
        } catch (_: IOException) {
            null
        }

        /**
         * [dictionary]'s words coded by [table] into [dest] for [key], replaced whole or not at
         * all, and opened; null if [wanted] said to stop (nothing written then). Bounded: [RUN]
         * records in the heap, the rest in runs on disk beside [dest], deleted after. [key] is
         * ASCII (a stamp), as the header's size counts it: another is refused at the write.
         */
        @Throws(IOException::class)
        fun build(table: TableDictionary, dictionary: DictionaryFile, dest: File, key: String, wanted: () -> Boolean = { true }): CodedIndex? =
            // one build of [dest] at a time: a newer file's, started while an older one still writes
            // (each on its own warm-up thread), shared the work dir and temporary, and each deleted the other's
            synchronized(building.computeIfAbsent(dest.absolutePath) { Any() }) { buildAlone(table, dictionary, dest, key, wanted) }

        private val building = ConcurrentHashMap<String, Any>()

        private fun buildAlone(table: TableDictionary, dictionary: DictionaryFile, dest: File, key: String, wanted: () -> Boolean): CodedIndex? {
            val work = emptyDir(File(dest.absoluteFile.parentFile, ".${dest.name}.build"))
            val temp = File(dest.path + ".tmp")
            try {
                val runs = Runs(work)
                if (!table.rules.isEmpty && !code(table, dictionary, runs, wanted)) return null
                runs.finish()
                if (!wanted()) return null
                write(temp, key, runs)
                if (!temp.renameTo(dest)) throw IOException("could not write $dest")
                // what was just written reads back, or the disk failed it: reported, not a table quietly without the words
                return open(dest, key) ?: throw IOException("$dest did not read back")
            } finally {
                temp.delete()
                work.deleteRecursively()
            }
        }

        // [dir] made anew, empty: a killed build's runs left there are not read into this one
        private fun emptyDir(dir: File): File {
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw IOException("could not make $dir")
            return dir
        }

        // each text of two characters or more coded once into [runs]; false if [wanted] said to stop
        private fun code(table: TableDictionary, dictionary: DictionaryFile, runs: Runs, wanted: () -> Boolean): Boolean {
            for (place in 0 until dictionary.size) {
                if (place % STOP_CHECK == 0 && !wanted()) return false
                val text = dictionary.text(place)
                val code = text.takeIf { it.codePointCount(0, it.length) >= 2 && firstOfText(dictionary, it, place) }
                    ?.let(table::encode)?.takeUnless { it.isEmpty() || table.contains(it, text) }
                if (code != null) runs.add(code, place)
            }
            return true
        }

        private fun write(temp: File, key: String, runs: Runs) {
            // writeUTF's modified UTF-8 is UTF-8 only for ASCII without NUL, as open reads its length (review NIT 13)
            require(key.all { it.code in 1..0x7f }) { "key $key" }
            temp.delete()
            val width = maxOf(runs.width, 1)
            RandomAccessFile(temp, "rw").use { f ->
                val out = DataOutputStream(BufferedOutputStream(FileOutputStream(f.fd), BUFFER))
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeUTF(key)
                out.writeInt(width)
                out.writeInt(runs.count)
                check(out.size() == headerSize(key)) { "header" }
                val crc = CRC32()
                val record = ByteBuffer.allocate(width * 2 + 4)
                runs.merge { code, place ->
                    (record as Buffer).clear()
                    for (k in 0 until width) record.putChar(if (k < code.length) code[k] else PAD)
                    record.putInt(place)
                    crc.update(record.array())
                    out.write(record.array())
                }
                out.writeLong(crc.value)
                out.flush()
                f.fd.sync()
            }
        }

        // magic, version, the key as writeUTF writes it (ASCII: a length, a byte a char), width, count
        private fun headerSize(key: String) = Int.SIZE_BYTES * 4 + Short.SIZE_BYTES + key.length

        // CRC32.update(ByteBuffer) is API 26; minSdk is 29 (review F3268)
        private fun crc32(buffer: ByteBuffer): Long = CRC32().apply { update(buffer) }.value

        // one record per text: the first place of it, its other readings coded the same
        private fun firstOfText(dictionary: DictionaryFile, text: String, place: Int): Boolean {
            var first = true
            dictionary.forEachOfText(text) { if (it < place) first = false }
            return first
        }
    }

    // (code, place) records sorted on disk a run at a time, then merged
    private class Runs(private val dir: File) {
        private val files = ArrayList<File>()
        private val codes = arrayOfNulls<String>(RUN)
        private val places = IntArray(RUN)
        private var n = 0
        var count = 0
        var width = 0

        fun add(code: String, place: Int) {
            codes[n] = code
            places[n] = place
            width = maxOf(width, code.length)
            count++
            if (++n == RUN) spill()
        }

        private fun spill() {
            if (n == 0) return
            val order = (0 until n).sortedWith { a, b -> compareValuesBy(a, b, { codes[it]!!.length }, { codes[it] }, { places[it] }) }
            val file = File(dir, "run${files.size}")
            DataOutputStream(BufferedOutputStream(FileOutputStream(file), BUFFER)).use { out ->
                for (i in order) {
                    out.writeUTF(codes[i]!!)
                    out.writeInt(places[i])
                }
            }
            files += file
            codes.fill(null, 0, n)
            n = 0
        }

        fun finish() = spill()

        fun merge(each: (String, Int) -> Unit) {
            val heads = PriorityQueue<Reader>(maxOf(files.size, 1), compareBy<Reader>({ it.code.length }, { it.code }, { it.place }))
            files.forEach { f -> Reader(f).also { if (it.next()) heads += it else it.close() } }
            try {
                while (heads.isNotEmpty()) {
                    val r = heads.poll()
                    each(r.code, r.place)
                    if (r.next()) heads += r else r.close()
                }
            } finally {
                // stopped early (a newer file wanted) or failed: the runs' descriptors let go now
                heads.forEach { it.close() }
            }
        }
    }

    private class Reader(file: File) {
        private val input = DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER))
        var code = ""
        var place = 0

        fun next(): Boolean = try {
            code = input.readUTF()
            place = input.readInt()
            true
        } catch (_: EOFException) {
            false
        }

        fun close() = input.close()
    }
}
