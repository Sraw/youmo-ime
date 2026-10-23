/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.escapeValue
import org.fcitx.fcitx5.android.engine.data.splitValues
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.io.BufferedReader
import java.io.FilterReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * What a text file to import is, told from the whole of it before a word is imported: its
 * charset, which column is the text and which the pinyin and how the pinyin is spelt, and what
 * its numbers are. Anything it is not sure of is refused with the reason ([Refused]), never
 * imported half right (the user's rule, after a 1.9-million-word file read wrongly cost them
 * their keyboard): a file of two layouts, or of initials only, or one too many of whose lines do
 * not read. [Detected] says what it found, for the confirm dialog, and [analyse] writes the lines in the
 * one form the compiler and the kept sources take (`text pin'yin weight`), as it reads them.
 */
object DictionaryFormat {

    /** How many lines the layout is decided from, spread from the file's head to its tail. */
    const val SAMPLE = 2000

    /** Rime's own `*.dict.yaml` (its header said so), or any other list. */
    enum class Source { RIME, PLAIN }

    /** Which comes first on a line. */
    enum class Order { TEXT_FIRST, PINYIN_FIRST }

    /** How the pinyin is written: `ni'hao`, `ni hao`, `nihao` (split one way only), or none at all. */
    enum class Reading { APOSTROPHES, SPACES, RUN_TOGETHER, NONE }

    /** What a line's number is: a probability in (0, 1), a count, a log10 probability (< 0), or there is none. */
    enum class Weight { PROBABILITY, COUNT, LOG, NONE }

    /** Why a file is refused. */
    enum class Reason {
        /** neither UTF-8 (or UTF-16 by its byte order mark) nor GBK/GB18030, through the whole file */
        ENCODING,

        /** a NUL in it: a binary file, whatever it decodes as */
        BINARY,

        /** no line that is a word */
        NO_WORDS,

        /** more than 2% of the sample in a second layout */
        MIXED,

        /** the pinyin is initials only (`nh 你好`): no reading to give the words */
        INITIALS,

        /** too many lines do not read as a word and its pinyin */
        UNREADABLE,

        /** the numbers are not of one kind */
        MIXED_WEIGHTS,

        /** more words than [DictionaryCompiler.MAX_WORDS] */
        TOO_MANY,

        /** no pinyin on most lines: only the user dictionary takes words without it */
        NO_PINYIN,

        /**
         * the characters do not have the readings given them, as the base dictionary knows them:
         * another encoding read as GBK (Big5's pairs all decode so), or another romanisation
         */
        MISMATCH,
    }

    sealed class Analysis

    /** Refused, for [reason]. */
    class Refused(val reason: Reason) : Analysis()

    /**
     * A file read as [charset], laid out so. [words] lines read as a word with its pinyin,
     * [textOnly] as a word alone or a blocked one (`!`; only the user dictionary takes those),
     * [unread] as neither.
     */
    class Detected internal constructor(
        val charset: Charset,
        val source: Source,
        val order: Order?,
        val reading: Reading,
        val weight: Weight,
        val words: Int,
        val textOnly: Int,
        val unread: Int,
    ) : Analysis() {
        /**
         * Why the base dictionary does not take it, which has nothing to guess a reading from;
         * null if it does. Lines without pinyin are unread there, held to the same 2%.
         */
        val refusedForBase: Reason?
            get() = when {
                words == 0 -> if (textOnly > 0) Reason.NO_PINYIN else Reason.NO_WORDS
                tooMany(textOnly + unread, words + textOnly + unread) -> if (textOnly > unread) Reason.NO_PINYIN else Reason.UNREADABLE
                else -> null
            }
    }

    /**
     * What the file [open] reads (opened again for each pass: two, a third if it is not UTF-8,
     * none holding more than a line and the sample) is, or why it is refused. Its words go to [write] as the
     * sources keep them (`text pin'yin weight`), in the second pass: the caller drops what it
     * wrote if it is refused then. [progress] hears the share of [size] read, both passes over 0..1;
     * a throw from it (a CancellationException, the user cancelled) stops it.
     *
     * @throws IOException if it cannot be read
     */
    @Throws(IOException::class)
    fun analyse(
        open: () -> InputStream,
        size: Long = 0,
        write: ((String) -> Unit)? = null,
        knows: ((String, IntArray) -> Boolean)? = null,
        progress: (Float) -> Unit = {},
    ): Analysis {
        // pass 1: the charset, decoded strictly through, with the lines and a sample spread over them;
        // a UTF-8 pass failed late and the GB18030 one from 0 never moves the bar back
        var shown = 0f
        val scan = scanned(open, size) { progress(maxOf(shown, it / 2).also { s -> shown = s }) } ?: return Refused(Reason.ENCODING)
        if (scan.binary) return Refused(Reason.BINARY)
        val sample = HashMap<Layout, Int>()
        for (line in scan.sample) classify(line)?.let { sample.merge(it.layout(), 1, Int::plus) }
        val sampled = sample.values.sum()
        if (sampled == 0) return Refused(Reason.NO_WORDS)
        val layout = when (val decided = decide(sample, sampled)) {
            is Refusal -> return Refused(decided.reason)
            is Layout -> decided
        }
        // only a file read as GB18030 can be another encoding that decoded so (Big5): a UTF-8 one's
        // characters are what they are, if the base lacks them (traditional, rare ones)
        if (knows != null && scan.charset == WordLists.GB18030 && !readingsHold(scan.sample, layout, knows)) return Refused(Reason.MISMATCH)
        // pass 2: every line read in that layout, and written as the sources keep them
        var lines = 0
        var words = 0
        var textOnly = 0
        counted(open, size, { progress(0.5f + it / 2) }) { input ->
            read(input, scan.charset) { seq ->
                for (read in seq.withHeaders {}.mapNotNull(::classify)) {
                    lines++
                    val entry = read.entry
                    when {
                        !layout.takes(read) -> {}
                        entry == null -> textOnly++
                        else -> {
                            words++
                            write?.invoke("${escapeValue(entry.text)} ${WordLists.code(entry.syllables)} ${layout.weigh(read)}")
                        }
                    }
                }
            }
        }
        val unread = lines - words - textOnly
        if (tooMany(unread, lines)) return Refused(Reason.UNREADABLE)
        if (words > DictionaryCompiler.MAX_WORDS) return Refused(Reason.TOO_MANY)
        return Detected(scan.charset, scan.source, layout.order, layout.reading ?: Reading.NONE, layout.weight, words, textOnly, unread)
    }

    // whether the sample's characters have their readings, as [knows] (the base dictionary) has
    // them, for [AGREE] of them: Big5 read as GBK decodes without an error, each pair one
    // character, the pinyin beside it right; only the characters tell
    private fun readingsHold(sample: List<String>, layout: Layout, knows: (String, IntArray) -> Boolean): Boolean {
        var chars = 0
        var hold = 0
        val entries = sample.mapNotNull(::classify).mapNotNull { read -> read.entry?.takeIf { layout.takes(read) } }
        for (entry in entries) {
            val points = entry.text.codePoints().toArray()
            if (points.size != entry.syllables.size) continue
            for ((i, syllable) in entry.syllables.withIndex()) {
                chars++
                if (knows(String(Character.toChars(points[i])), intArrayOf(syllable))) hold++
            }
        }
        return chars == 0 || hold >= chars * AGREE
    }

    // the share of the characters that must have their readings: polyphones and rare ones the base lacks fall short of all
    private const val AGREE = 0.9

    private class Scan(val charset: Charset, val source: Source, val binary: Boolean, val sample: List<String>)

    // the file decoded through: UTF-16 or UTF-8 by a byte order mark, else UTF-8 if all of it
    // decodes so, else GB18030 if all of it does; null for none
    private fun scanned(open: () -> InputStream, size: Long, progress: (Float) -> Unit): Scan? {
        val head = ByteArray(3)
        val n = open().use { input -> input.readNBytesCompat(head) }
        WordLists.bomCharset(head, n)?.let { return scan(open, it, size, progress) }
        val utf8Bom = n == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        scan(open, Charsets.UTF_8, size, progress)?.let { return it }
        if (utf8Bom) return null
        return scan(open, WordLists.GB18030, size, progress)
    }

    // one pass: decoded strictly (null if it does not), Rime's header seen, a NUL, and [SAMPLE]
    // lines drawn evenly at random from all of them (reservoir), the same each time
    private fun scan(open: () -> InputStream, charset: Charset, size: Long, progress: (Float) -> Unit): Scan? = try {
        var source = Source.PLAIN
        var binary = false
        val sample = ArrayList<String>(SAMPLE)
        val random = java.util.Random(SEED)
        counted(open, size, progress) { input ->
            read(input, charset) { seq ->
                var seen = 0L
                for (line in seq.withHeaders { source = Source.RIME }) {
                    // a NUL: a binary file, read no further
                    if ('\u0000' in line) {
                        binary = true
                        break
                    }
                    val bare = line.trim()
                    if (bare.isNotEmpty() && !bare.startsWith("#")) {
                        seen++
                        if (sample.size < SAMPLE) {
                            sample += line
                        } else {
                            val at = (random.nextDouble() * seen).toLong()
                            if (at < SAMPLE) sample[at.toInt()] = line
                        }
                    }
                }
            }
        }
        Scan(charset, source, binary, sample)
    } catch (_: CharacterCodingException) {
        null
    }

    // [block] on what [open] reads, [progress] told the share of [size] read so far (none for 0)
    private fun <T> counted(open: () -> InputStream, size: Long, progress: (Float) -> Unit, block: (InputStream) -> T): T {
        val input = object : java.io.FilterInputStream(open()) {
            var read = 0L
            var told = 0L

            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                if (n > 0) read += n
                if (size > 0 && read - told >= PROGRESS_BYTES) {
                    told = read
                    progress(minOf(1f, read.toFloat() / size))
                }
            }
        }
        return input.use(block).also { if (size > 0) progress(1f) }
    }

    // told every this many bytes read: about 80 times for an 85 MB file
    private const val PROGRESS_BYTES = 1L shl 20

    private const val SEED = 20261022L

    // what [decide] makes of the sample: a layout, or why there is none
    private sealed interface Decision

    private class Refusal(val reason: Reason) : Decision

    // the layout most of the sample has, or why there is none
    private fun decide(sample: Map<Layout, Int>, sampled: Int): Decision {
        val read = sample.filterKeys { it.reads }
        val order = read.entries.filter { it.key.order != null }.groupBy({ it.key.order }, { it.value })
            .maxByOrNull { it.value.sum() }?.key
        val reading = read.entries.filter { it.key.reading in SPELT }.groupBy({ it.key.reading }, { it.value })
            .maxByOrNull { it.value.sum() }?.key
            // one syllable a word all through, or no pinyin at all
            ?: if (read.keys.any { it.line == LineReading.ANY }) Reading.APOSTROPHES else Reading.NONE
        val shape = Layout(order, reading, Weight.NONE)
        val fits = sample.filterKeys { shape.takes(it) }
        val fitting = fits.values.sum()
        if (tooMany(sampled - fitting, sampled)) {
            val initials = sample.filterKeys { it.line == LineReading.INITIALS }.values.sum()
            val otherLayout = read.filterKeys { !shape.takes(it) }.values.sum()
            return when {
                initials >= otherLayout && tooMany(initials, sampled) -> Reason.INITIALS
                tooMany(otherLayout, sampled) -> Reason.MIXED
                fitting > 0 && fits.keys.all { it.reading == Reading.NONE } -> Reason.NO_PINYIN
                else -> Reason.UNREADABLE
            }.let(::Refusal)
        }
        val weighed = fits.entries.filter { it.key.weight != Weight.NONE }
        val byWeight = weighed.groupBy({ it.key.weight }, { it.value }).mapValues { it.value.sum() }
        val weight = byWeight.maxByOrNull { it.value }?.key ?: Weight.NONE
        val some = byWeight.values.sum()
        // the numbers one kind on 99% of the lines that have one
        if (some > 0 && (some - byWeight.getValue(weight)) * 100 > some) return Refusal(Reason.MIXED_WEIGHTS)
        return Layout(order, reading, weight)
    }

    // more than 2% of [of]
    private fun tooMany(n: Int, of: Int) = n * 50 > of

    private val SPELT = setOf(Reading.APOSTROPHES, Reading.SPACES, Reading.RUN_TOGETHER)

    // a line's layout; reading ANY for one syllable (any spelling reads so), INITIALS and BAD for none
    private enum class LineReading { APOSTROPHES, SPACES, RUN_TOGETHER, NONE, ANY, INITIALS, BAD }

    private data class Layout(val order: Order?, val reading: Reading?, val weight: Weight, val line: LineReading? = null) : Decision {
        val reads get() = line != LineReading.INITIALS && line != LineReading.BAD

        // whether a line of [other]'s layout reads as this one's: its weight aside
        fun takes(other: Layout): Boolean {
            if (!other.reads) return false
            if (other.order != null && order != null && other.order != order) return false
            return when (other.line) {
                LineReading.ANY, LineReading.NONE -> true
                else -> other.reading == reading
            }
        }

        fun takes(read: Read) = takes(read.layout())

        // what [read]'s number is written as: a 1 (or 0) is of any kind but a log10 probability's,
        // where a 1 is none (a 1 kept made the compiler's signs mixed: the whole source unranked);
        // one of another kind than this layout's, or in a layout of none, tells nothing (0). A 0
        // in a log file is its best (log10 1) but goes as 0, none, as 0.3.1 wrote one missing:
        // the compiler cannot tell the two, and libime's own files give no word 0
        fun weigh(read: Read): Double = when {
            weight == Weight.NONE -> 0.0
            read.weight == weight -> read.value
            read.weight == Weight.NONE && !(weight == Weight.LOG && read.value > 0) -> read.value
            else -> 0.0
        }
    }

    private class Read(val order: Order?, val line: LineReading, val weight: Weight, val value: Double, val entry: Entry?) {
        fun layout(): Layout {
            val reading = when (line) {
                LineReading.APOSTROPHES -> Reading.APOSTROPHES
                LineReading.SPACES -> Reading.SPACES
                LineReading.RUN_TOGETHER -> Reading.RUN_TOGETHER
                LineReading.NONE -> Reading.NONE
                else -> null
            }
            return Layout(order, reading, weight, line)
        }
    }

    // the initials of pinyin: a file of these alone has no reading
    private const val INITIALS = "bpmfdtnlgkhjqxzcsryw"

    private val NUMBER = Regex("-?\\d+(\\.\\d+)?([eE]-?\\d+)?")

    // Rime's share of a polyphone's readings (`的	di	10%`): a number, of no kind that ranks a word
    private val PERCENT = Regex("\\d+(\\.\\d+)?%")
    private val SPACES = Regex("\\s+")

    // a line's layout and word; null for a blank line or a comment
    private fun classify(raw: String): Read? {
        val line = raw.removePrefix("\uFEFF").trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        // a word blocked, as a user's list has them: the user dictionary's alone, as a word without pinyin
        // the marks stripped at once: a line of thousands of them recursed one call each (review)
        if (line.startsWith("!")) return if (classify(line.trimStart('!'))?.layout()?.reads == true) BLOCKED else BAD
        return libimeLine(line) ?: wordListLine(line)
    }

    private val BAD = Read(null, LineReading.BAD, Weight.NONE, 0.0, null)
    private val BLOCKED = Read(null, LineReading.NONE, Weight.NONE, 0.0, null)

    // libime's text, as the engine wrote it (a value in quotes if escaped): null if it is not
    private fun libimeLine(line: String): Read? {
        val fields = splitValues(line)
        if (fields.size !in 2..3) return null
        val value = (if (fields.size == 3) fields[2].toDoubleOrNull() else 0.0) ?: return null
        val entry = LibimeImport.entry(fields[0], fields[1]) ?: return null
        val reading = if (entry.syllables.size <= 1) LineReading.ANY else LineReading.APOSTROPHES
        return Read(Order.TEXT_FIRST, reading, weightOf(value), value, entry)
    }

    // a line's word, its pinyin (a token of letters and ' each) and its number, in the order they come
    private class Tokens(val text: String, val pinyin: List<String>, val pinyinFirst: Boolean, val value: Double?)

    private fun tokens(line: String): Tokens? {
        var text: String? = null
        var textAt = -1
        var pinyinAt = -1
        val pinyin = ArrayList<String>()
        var value: Double? = null
        for ((i, token) in line.split(SPACES).withIndex()) when {
            // never the first: a word of digits is no number of itself
            NUMBER.matches(token) && (text != null || pinyin.isNotEmpty()) -> value = token.toDouble()
            PERCENT.matches(token) && (text != null || pinyin.isNotEmpty()) -> {}
            token.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == 'ü' } -> {
                if (pinyinAt < 0) pinyinAt = i
                pinyin += token
            }
            text == null -> {
                text = token
                textAt = i
            }
            // a second word: no line of a list
            else -> return null
        }
        return text?.let { Tokens(it, pinyin, pinyinAt in 0 until textAt, value) }
    }

    // a line of another input method's list, read as WordLists reads one
    private fun wordListLine(line: String): Read {
        val t = tokens(line) ?: return BAD
        val weight = t.value?.let(::weightOf) ?: Weight.NONE
        val w = t.value ?: 0.0
        if (t.pinyin.isEmpty()) {
            // a word alone: Han only, or it is no word (Baidu's `词(ci)`, a sentence of punctuation)
            val han = isHan(t.text)
            return Read(null, if (han) LineReading.NONE else LineReading.BAD, weight, w, null)
        }
        val order = if (t.pinyinFirst) Order.PINYIN_FIRST else Order.TEXT_FIRST
        val spelt = t.pinyin.joinToString(" ")
        val entry = WordLists.entry(t.text, spelt) ?: return Read(order, unreadReading(t.text, spelt), weight, w, null)
        val reading = when {
            entry.syllables.size <= 1 -> LineReading.ANY
            '\'' in spelt -> LineReading.APOSTROPHES
            t.pinyin.size > 1 -> LineReading.SPACES
            else -> LineReading.RUN_TOGETHER
        }
        return Read(order, reading, weight, w, entry)
    }

    private fun isHan(text: String): Boolean = text.codePoints().allMatch { Character.isIdeographic(it) }

    // a reading that does not read: initials alone, a letter a character, or none
    private fun unreadReading(text: String, spelt: String): LineReading {
        val letters = spelt.filter { it.isLetter() }.lowercase()
        val initials = letters.isNotEmpty() && letters.all { it in INITIALS } && letters.length == text.codePointCount(0, text.length)
        return if (initials) LineReading.INITIALS else LineReading.BAD
    }

    // 1 is a probability and a count alike, 0 none (0.3.1 wrote one missing so): neither tells the kind
    private fun weightOf(v: Double): Weight = when {
        v == 0.0 || v == 1.0 -> Weight.NONE
        v < 0 -> Weight.LOG
        v < 1 -> Weight.PROBABILITY
        else -> Weight.COUNT
    }

    // the lines but Rime's header: `---` (comments before it) to `...`, told to [rime]
    private fun Sequence<String>.withHeaders(rime: () -> Unit): Sequence<String> = sequence {
        var at = HeaderState.BEFORE
        for (raw in this@withHeaders) {
            val line = raw.removePrefix("\uFEFF").trim()
            if (at == HeaderState.BEFORE && line.isNotEmpty() && !line.startsWith("#")) {
                at = if (line == "---") HeaderState.HEADER.also { rime() } else HeaderState.BODY
            } else if (at == HeaderState.HEADER && line == "...") {
                at = HeaderState.BODY
                continue
            }
            if (at != HeaderState.HEADER) yield(raw)
        }
    }

    // before any line but comments, in Rime's header, past it
    private enum class HeaderState { BEFORE, HEADER, BODY }

    // InputStream.readNBytes is API 33
    private fun InputStream.readNBytesCompat(into: ByteArray): Int {
        var n = 0
        while (n < into.size) {
            val r = read(into, n, into.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun strict(charset: Charset) = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    private fun <T> read(input: InputStream, charset: Charset, block: (Sequence<String>) -> T): T =
        BufferedReader(Capped(InputStreamReader(input, strict(charset))), BUFFER).use { block(it.lineSequence()) }

    // a line longer than [MAX_LINE] broken where it reaches it (a character dropped for the break):
    // a file of no newlines (minified JSON) is no one String of all of it, and reads as lines unread
    private class Capped(reader: Reader) : FilterReader(reader) {
        private var run = 0

        override fun read(): Int {
            val c = super.read()
            return if (c < 0) c else cap(c.toChar()).code
        }

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            val n = super.read(cbuf, off, len)
            for (i in off until off + maxOf(n, 0)) cbuf[i] = cap(cbuf[i])
            return n
        }

        private fun cap(c: Char): Char = when {
            c == '\n' || c == '\r' -> c.also { run = 0 }
            ++run > MAX_LINE -> '\n'.also { run = 0 }
            else -> c
        }
    }

    private const val MAX_LINE = 4096

    private const val BUFFER = 64 shl 10
}
