/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.escapeValue
import org.fcitx.fcitx5.android.engine.data.splitValues
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.imports.WordText
import java.io.BufferedReader
import java.io.FilterReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException

/**
 * What a text file to import is, told from all of it before a word is imported: charset, column order, pinyin
 * spelling, number kind. Anything unsure is refused with a [Reason], never imported half right (a 1.9-million-word
 * file read wrongly once cost a user their keyboard). [analyse] writes lines as `text pin'yin weight`.
 */
object DictionaryFormat {

    const val SAMPLE = 2000

    enum class Source { RIME, PLAIN }

    enum class Order { TEXT_FIRST, PINYIN_FIRST }

    /** How the pinyin is written: `ni'hao`, `ni hao`, `nihao` (split one way only), or none. */
    enum class Reading { APOSTROPHES, SPACES, RUN_TOGETHER, NONE }

    enum class Weight { PROBABILITY, COUNT, LOG, NONE }

    enum class Reason {
        /** neither UTF-8 (or UTF-16 by BOM) nor GBK/GB18030 through the whole file */
        ENCODING,

        /** a NUL in it: a binary file, whatever it decodes as */
        BINARY,

        NO_WORDS,

        /** more than 2% of the sample in a second layout */
        MIXED,

        /** the pinyin is initials only (`nh 你好`) */
        INITIALS,

        UNREADABLE,

        MIXED_WEIGHTS,

        TOO_MANY,

        /** no pinyin on most lines: only the user dictionary takes words without it */
        NO_PINYIN,

        /** the characters lack the readings given: another encoding read as GBK (Big5 decodes so), or another romanisation */
        MISMATCH,

        /** too large for its import type or the memory converting it needs (the app's check) */
        TOO_LARGE,
    }

    sealed class Analysis

    class Refused(val reason: Reason) : Analysis()

    /** A file read as [charset]: [words] with pinyin, [textOnly] words alone or blocked (`!`), [unread] neither; [guessed]/[unguessed] of the words alone given or denied a guessed reading. */
    class Detected internal constructor(
        val charset: Charset,
        val source: Source,
        val order: Order?,
        val reading: Reading,
        val weight: Weight,
        val words: Int,
        val textOnly: Int,
        val unread: Int,
        val guessed: Int = 0,
        val unguessed: Int = 0,
    ) : Analysis() {
        /** Why the base dictionary refuses it (it has no reading to guess from); null if it takes it. */
        val refusedForBase: Reason?
            get() = when {
                words == 0 -> if (textOnly > 0) Reason.NO_PINYIN else Reason.NO_WORDS
                tooMany(textOnly - unguessed + unread, words + textOnly - unguessed + unread) ->
                    if (textOnly - unguessed > unread) Reason.NO_PINYIN else Reason.UNREADABLE
                else -> null
            }
    }

    /**
     * What the file [open] reads is, or why refused. Up to three passes, holding no more than a line and the sample;
     * words go to [write] in the second pass, dropped by the caller if refused then. A throw from [progress] cancels.
     * [guess] gives a word alone its reading, [GUESS_BATCH] at a time. A GB18030 file whose words alone it reads
     * under [AGREE] is refused ([Reason.MISMATCH]): Big5 decoded as GBK.
     *
     * @throws IOException if it cannot be read
     */
    @Throws(IOException::class)
    fun analyse(
        open: () -> InputStream,
        size: Long = 0,
        write: ((String) -> Unit)? = null,
        knows: ((String, IntArray) -> Boolean)? = null,
        guess: ((List<String>) -> Map<String, String?>)? = null,
        progress: (Float) -> Unit = {},
    ): Analysis {
        // pass 1: the charset, decoded strictly, with a spread sample; a late UTF-8 failure never moves the bar back
        var shown = 0f
        val scan = scanned(open, size) { progress(maxOf(shown, it / 2).also { s -> shown = s }) } ?: return Refused(Reason.ENCODING)
        if (scan.binary) return Refused(Reason.BINARY)
        val sample = HashMap<Layout, Int>()
        for (line in scan.sample) classify(line)?.let { sample.merge(it.layout(), 1, Int::plus) }
        val sampled = sample.values.sum()
        if (sampled == 0) return Refused(Reason.NO_WORDS)
        val layout = when (val decided = decide(sample, sampled, guessing = guess != null)) {
            is Refusal -> return Refused(decided.reason)
            is Layout -> decided
        }
        // only a GB18030 read can be another encoding (Big5); a UTF-8 file's characters are what they are
        if (knows != null && scan.charset == WordLists.GB18030 && !readingsHold(scan.sample, layout, knows)) return Refused(Reason.MISMATCH)
        var lines = 0
        var words = 0
        var textOnly = 0
        val alone = Alone(guess, write, gbk = scan.charset == WordLists.GB18030)
        counted(open, size, { progress(0.5f + it / 2) }) { input ->
            read(input, scan.charset) { seq ->
                for (read in seq.withHeaders {}.mapNotNull(::classify)) {
                    lines++
                    val entry = read.entry
                    when {
                        !layout.takes(read) -> {}
                        entry == null -> {
                            textOnly++
                            alone.add(read.alone, layout.weigh(read))
                        }
                        else -> {
                            words++
                            write?.invoke("${escapeValue(entry.text)} ${entry.apostrophed()} ${layout.weigh(read)}")
                        }
                    }
                }
            }
            alone.flush()
        }
        if (!alone.agrees(words)) return Refused(Reason.MISMATCH)
        words += alone.guessed
        textOnly -= alone.guessed
        val unread = lines - words - textOnly
        if (tooMany(unread, lines)) return Refused(Reason.UNREADABLE)
        if (words > DictionaryCompiler.MAX_WORDS) return Refused(Reason.TOO_MANY)
        return Detected(scan.charset, scan.source, layout.order, layout.reading ?: Reading.NONE, layout.weight, words, textOnly, unread, alone.guessed, alone.unguessed)
    }

    // words alone and weights waiting for [guess], written a batch at a time; [gbk]: held to [AGREE]
    private class Alone(
        private val guess: ((List<String>) -> Map<String, String?>)?,
        private val write: ((String) -> Unit)?,
        private val gbk: Boolean,
    ) {
        var guessed = 0
        var unguessed = 0

        private var failed = 0

        private val waiting = ArrayList<Pair<String, Double>>()

        /** Whether the guesses bear the encoding out, judged only where words alone are most of the file: a real guess reads Big5's wrong characters too, so only one that reads little fails. */
        fun agrees(read: Int): Boolean {
            val asked = guessed + unguessed - failed
            return !gbk || asked == 0 || asked <= read || guessed >= asked * AGREE
        }

        fun add(text: String?, weight: Double) {
            if (guess == null || text == null) return
            waiting += text to weight
            if (waiting.size >= GUESS_BATCH) flush()
        }

        // a failed guess (engine gone, a bug) skips the batch, not the whole file; a cancel goes on up
        @Suppress("TooGenericExceptionCaught")
        private fun readings(guess: (List<String>) -> Map<String, String?>): Map<String, String?> = try {
            guess(waiting.mapTo(LinkedHashSet()) { it.first }.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed += waiting.size
            emptyMap()
        }

        fun flush() {
            if (guess == null || waiting.isEmpty()) return
            val readings = readings(guess)
            for ((text, weight) in waiting) {
                val entry = readings[text]?.let { WordLists.entry(text, it) }
                if (entry == null) {
                    unguessed++
                } else {
                    guessed++
                    write?.invoke("${escapeValue(entry.text)} ${entry.apostrophed()} $weight")
                }
            }
            waiting.clear()
        }
    }

    // Big5 read as GBK decodes without error and the pinyin beside it is right; only the characters' readings tell
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

    // polyphones and rare characters the base lacks fall short of all
    private const val AGREE = 0.9

    private class Scan(val charset: Charset, val source: Source, val binary: Boolean, val sample: List<String>)

    // BOM first, else UTF-8 if all decodes, else GB18030; null for none
    private fun scanned(open: () -> InputStream, size: Long, progress: (Float) -> Unit): Scan? {
        val head = ByteArray(3)
        val n = open().use { input -> input.readNBytesCompat(head) }
        WordLists.bomCharset(head, n)?.let { return scan(open, it, size, progress) }
        val utf8Bom = n == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        scan(open, Charsets.UTF_8, size, progress)?.let { return it }
        if (utf8Bom) return null
        return scan(open, WordLists.GB18030, size, progress)
    }

    // one strict pass: Rime's header, a NUL, and [SAMPLE] lines by reservoir (deterministic)
    private fun scan(open: () -> InputStream, charset: Charset, size: Long, progress: (Float) -> Unit): Scan? = try {
        var source = Source.PLAIN
        var binary = false
        val sample = ArrayList<String>(SAMPLE)
        val random = java.util.Random(SEED)
        counted(open, size, progress) { input ->
            read(input, charset) { seq ->
                var seen = 0L
                for (line in seq.withHeaders { source = Source.RIME }) {
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

    private const val PROGRESS_BYTES = 1L shl 20

    private const val SEED = 20261022L

    /** Words alone per guess: a list of a million read without holding it. */
    const val GUESS_BATCH = 4096

    private sealed interface Decision

    private class Refusal(val reason: Reason) : Decision

    // [guessing]: words alone get a reading, so lines of no word are what is wrong, not missing pinyin
    private fun decide(sample: Map<Layout, Int>, sampled: Int, guessing: Boolean): Decision {
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
                !guessing && fitting > 0 && fits.keys.all { it.reading == Reading.NONE } -> Reason.NO_PINYIN
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

    private fun tooMany(n: Int, of: Int) = n * 50 > of

    private val SPELT = setOf(Reading.APOSTROPHES, Reading.SPACES, Reading.RUN_TOGETHER)

    // ANY for one syllable (any spelling reads so); INITIALS and BAD for none
    private enum class LineReading { APOSTROPHES, SPACES, RUN_TOGETHER, NONE, ANY, INITIALS, BAD }

    private data class Layout(val order: Order?, val reading: Reading?, val weight: Weight, val line: LineReading? = null) : Decision {
        val reads get() = line != LineReading.INITIALS && line != LineReading.BAD

        fun takes(other: Layout): Boolean {
            if (!other.reads) return false
            if (other.order != null && order != null && other.order != order) return false
            return when (other.line) {
                LineReading.ANY, LineReading.NONE -> true
                else -> other.reading == reading
            }
        }

        fun takes(read: Read) = takes(read.layout())

        // a 1 (or 0) is of any kind but log10's, where 1 is none (else the compiler saw mixed signs); a 0 in a log file
        // goes as none, as 0.3.1 wrote one missing
        fun weigh(read: Read): Double = when {
            weight == Weight.NONE -> 0.0
            read.weight == weight -> read.value
            read.weight == Weight.NONE && !(weight == Weight.LOG && read.value > 0) -> read.value
            else -> 0.0
        }
    }

    private class Read(val order: Order?, val line: LineReading, val weight: Weight, val value: Double, val entry: Entry?, val alone: String? = null) {
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

    private const val INITIALS = "bpmfdtnlgkhjqxzcsryw"

    private val NUMBER = Regex("-?\\d+(\\.\\d+)?([eE]-?\\d+)?")

    // Rime's share of a polyphone's readings (`的	di	10%`): no weight
    private val PERCENT = Regex("\\d+(\\.\\d+)?%")
    private val SPACES = Regex("\\s+")

    private fun classify(raw: String): Read? {
        val line = raw.removePrefix("\uFEFF").trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        // a blocked word: the user dictionary's alone. Marks stripped at once: thousands of them recursed one call each
        if (line.startsWith("!")) return if (classify(line.trimStart('!'))?.layout()?.reads == true) BLOCKED else BAD
        return libimeLine(line) ?: wordListLine(line)
    }

    private val BAD = Read(null, LineReading.BAD, Weight.NONE, 0.0, null)
    private val BLOCKED = Read(null, LineReading.NONE, Weight.NONE, 0.0, null)

    // libime's text, as the engine wrote it (a value in quotes if escaped)
    private fun libimeLine(line: String): Read? {
        val fields = splitValues(line)
        if (fields.size !in 2..3) return null
        val value = (if (fields.size == 3) fields[2].toDoubleOrNull() else 0.0) ?: return null
        val entry = WordText.entry(fields[0], fields[1]) ?: return null
        val reading = if (entry.syllables.size <= 1) LineReading.ANY else LineReading.APOSTROPHES
        return Read(Order.TEXT_FIRST, reading, weightOf(value), value, entry)
    }

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
            else -> return null
        }
        return text?.let { Tokens(it, pinyin, pinyinAt in 0 until textAt, value) }
    }

    private fun wordListLine(line: String): Read {
        val t = tokens(line) ?: return BAD
        val weight = t.value?.let(::weightOf) ?: Weight.NONE
        val w = t.value ?: 0.0
        if (t.pinyin.isEmpty()) {
            // Han only, or it is no word (Baidu's `词(ci)`)
            val han = isHan(t.text)
            return Read(null, if (han) LineReading.NONE else LineReading.BAD, weight, w, null, t.text.takeIf { han })
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

    private fun unreadReading(text: String, spelt: String): LineReading {
        val letters = spelt.filter { it.isLetter() }.lowercase()
        val initials = letters.isNotEmpty() && letters.all { it in INITIALS } && letters.length == text.codePointCount(0, text.length)
        return if (initials) LineReading.INITIALS else LineReading.BAD
    }

    // 1 is a probability and a count alike, 0 none: neither tells the kind
    private fun weightOf(v: Double): Weight = when {
        v == 0.0 || v == 1.0 -> Weight.NONE
        v < 0 -> Weight.LOG
        v < 1 -> Weight.PROBABILITY
        else -> Weight.COUNT
    }

    // Rime's header (`---` to `...`) skipped, told to [rime]
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

    // a line over [MAX_LINE] is broken there: a file with no newlines is no single String
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
