/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.CodeTableReader
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.WordLayers
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.WordPack
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.data.forEachNumberedLine
import org.fcitx.fcitx5.android.engine.table.TableText
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.system.exitProcess

val USAGE = """
    usage: pinyin -o <out> --lm <lm.arpa> [--rime-unigrams <min count>] <dict>...
                                                          compile a pinyin dictionary and model, from
                                                          libime's text or Rime's .dict.yaml
           table -o <out> <table.txt>                     compile a code table
           mix -o <out.arpa> --lm <lm.arpa> [--weight <w>] [--cutoffs <bigram>,<trigram>] <corpus>...
                                                          mix a model with n-grams counted in chat:
                                                          conversations a JSON array a line (.jsonl.gz),
                                                          or FineWeb-2 shards' chat-like pages (.parquet)
           check <pinyin data> <lm.arpa>                  compare compiled scores with the model
           words -o <out.tsv> --data <pinyin.data> [--min-count <n>] <shard.parquet>...
                                                          the words the pages use that the data lacks (NewWords)
           pack -o <out.words> --data <pinyin.data> --layer <name> [--min-count <n>] [--min-pmi <x>] [--min-entropy <x>] [--min-surprise <x>] <candidates.tsv>
                                                          a word pack (WordPack) of the candidates that pass
""".trimIndent()

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

/** @return the process exit code: 0, 1 for bad input data, 2 for a usage error */
fun runCli(args: Array<String>, out: Appendable, err: Appendable): Int {
    val command = args.firstOrNull()
    val options = Options.parse(args.drop(1))
    return try {
        if (options == null || !dispatch(command, options, out)) {
            err.appendLine(USAGE)
            return 2
        }
        0
    } catch (e: SourceException) {
        err.appendLine(e.message)
        1
    } catch (e: DataFormatException) {
        // check reads a compiled file: a stale or foreign one
        err.appendLine(e.message)
        1
    } catch (e: IOException) {
        err.appendLine(e.toString())
        1
    } catch (e: IllegalArgumentException) {
        // an inconsistency only visible once everything is read (a duplicate n-gram, no <unk> ...),
        // or a path WebText refuses
        err.appendLine(e.message)
        1
    }
}

/** @return false for a command or options it does not take */
private fun dispatch(command: String?, options: Options, out: Appendable): Boolean {
    if (!options.fit(command)) return false
    when (command) {
        "pinyin" -> pinyin(options.output!!, options.lm!!, options.inputs, options.rimeUnigrams, out)
        "table" -> table(options.output!!, options.inputs.single(), out)
        "mix" -> mix(options, out)
        "check" -> check(options.inputs[0], options.inputs[1], out)
        "words" -> words(options, out)
        "pack" -> pack(options, out)
    }
    return true
}

private class Options(
    val output: String?,
    val lm: String?,
    val inputs: List<String>,
    val weight: Double,
    val cutoffs: Pair<Int, Int>,
    /** Rime words the model lacks with at least this count get a unigram from it ([CountFit]); null for none. */
    val rimeUnigrams: Long?,
    /** `words`, `pack`: the pinyin data whose words are known. */
    val data: String?,
    /** `words`: how often a run must occur to be a candidate; `pack`: to go in. */
    val minCount: Int,
    /** `pack`: the layer the words are of, and the least pmi, entropy (either side) and surprise a candidate needs. */
    val layer: String?,
    val minPmi: Double,
    val minEntropy: Double,
    val minSurprise: Double,
) {
    /** Whether [command] takes these options. */
    fun fit(command: String?): Boolean {
        val some = inputs.isNotEmpty()
        return when (command) {
            "pinyin", "mix" -> output != null && lm != null && some
            "table" -> output != null && lm == null && inputs.size == 1
            "check" -> output == null && lm == null && inputs.size == 2
            "words" -> output != null && data != null && some
            "pack" -> output != null && data != null && layer != null && inputs.size == 1
            else -> false
        }
    }

    companion object {
        // what measured best with FineWeb-2's chat-like pages over the written and the chat
        // evaluation sets together (dev/ENGINE-DESIGN.md)
        const val WEIGHT = 0.6
        val CUTOFFS = 2 to 2
        // a word of the last years in 0.4 billion characters of chat-like pages: hundreds of times
        const val MIN_COUNT = 50
        // a run ten times as frequent as its parts predict, with a handful of different neighbours
        const val MIN_PMI = 1.0
        const val MIN_ENTROPY = 1.5
        // no least surprise: on FineWeb-2 it told the model's domain from the pages' more than
        // phrases from words (dev/TRAINING-PLAN.md 11.7), but a corpus nearer the model's can ask for one
        val MIN_SURPRISE = Double.NEGATIVE_INFINITY

        // every option takes a value
        private val FLAGS = setOf(
            "-o", "--lm", "--weight", "--cutoffs", "--rime-unigrams", "--data", "--min-count", "--layer", "--min-pmi", "--min-entropy", "--min-surprise",
        )

        /** @return null for an option with a bad or missing value */
        fun parse(args: List<String>): Options? {
            val values = HashMap<String, String>()
            val inputs = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                if (args[i] in FLAGS) values[args[i]] = args.getOrNull(++i) ?: return null else inputs += args[i]
                i++
            }
            fun <T : Any> read(flag: String, default: T, value: (String) -> T?): T? = values[flag]?.let(value) ?: default.takeIf { flag !in values }
            return Options(
                output = values["-o"],
                lm = values["--lm"],
                inputs = inputs,
                weight = read("--weight", WEIGHT) { it.toDoubleOrNull()?.takeIf { w -> w in 0.0..1.0 } } ?: return null,
                // a trigram's context must be a bigram kept (Mixer)
                cutoffs = read("--cutoffs", CUTOFFS) { v ->
                    v.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 && it[0] in 1..it[1] }?.let { it[0] to it[1] }
                } ?: return null,
                rimeUnigrams = values["--rime-unigrams"]?.let { it.toLongOrNull()?.takeIf { c -> c >= 0 } ?: return null },
                data = values["--data"],
                minCount = read("--min-count", MIN_COUNT) { it.toIntOrNull()?.takeIf { c -> c >= 1 } } ?: return null,
                layer = values["--layer"]?.let { it.takeIf(WordPack::validLayer) ?: return null },
                minPmi = read("--min-pmi", MIN_PMI) { it.toDoubleOrNull()?.takeIf(Double::isFinite) } ?: return null,
                minEntropy = read("--min-entropy", MIN_ENTROPY) { it.toDoubleOrNull()?.takeIf(Double::isFinite) } ?: return null,
                minSurprise = read("--min-surprise", MIN_SURPRISE) { it.toDoubleOrNull()?.takeIf(Double::isFinite) } ?: return null,
            )
        }
    }
}

/**
 * The runs of Han characters the pages use that the data's base layer lacks, with what tells a
 * word from a chance run: `text count year pmi left-entropy right-entropy surprise known`, a
 * line each, in no order ([NewWords], [Surprise]). Lines starting `#` are comments.
 */
private fun words(options: Options, out: Appendable) {
    val data = PinyinData.load(map(options.data!!))
    // the base layer's words: the others (Rime's) are the kind a pack is for, scored by true counts
    val known = HashSet<String>(data.vocabulary.size * 2)
    for (id in 0 until data.vocabulary.size) if (data.layers.layer(id) == 0) known += data.vocabulary.word(id)
    val finder = NewWords(known, options.minCount)
    val shards = options.inputs.map(::File)
    var pages = 0
    while (!finder.done) {
        pages = 0
        for (shard in shards) {
            // every simplified page, not only the chat-like: new words are in the news too
            WebText.read(shard, chatOnly = false) { page, date ->
                page.forEach { finder.add(it, date) }
                pages++
            }
        }
        out.appendLine("pass ${finder.pass}: $pages pages")
        finder.nextPass()
    }
    val surprise = Surprise(data)
    var all = 0
    var unknown = 0
    File(options.output!!).bufferedWriter().use { w ->
        w.write("# text\tcount\tyear\tpmi\tleft_entropy\tright_entropy\tsurprise\tknown\n")
        w.write("# chars\t${finder.chars}\n")
        finder.candidates { c ->
            val s = surprise.of(c.text, c.count, finder.chars)
            w.write("%s\t%d\t%d\t%.3f\t%.3f\t%.3f\t%.3f\t%d\n".format(c.text, c.count, c.year, c.pmi, c.leftEntropy, c.rightEntropy, s, if (c.known) 1 else 0))
            all++
            if (!c.known) unknown++
        }
    }
    out.appendLine("words: $all runs of at least ${options.minCount}, $unknown of them not in the data")
}

/**
 * The candidates of `words` that pass the thresholds and are not plainly phrases or fragments
 * ([Phrases]), as a word pack: each read by its characters' likeliest readings, scored on the
 * model's unigram scale by its count ([CountFit] over the candidates the model knows). One with
 * a character no reading is known for is left out.
 */
private fun pack(options: Options, out: Appendable) {
    val data = PinyinData.load(map(options.data!!))
    val readings = CharReadings(data)
    val input = File(options.inputs.single())
    // two reads of the candidates rather than all of them in memory: there are millions
    val known = ArrayList<Pair<Long, Float>>()
    val phrases = Phrases()
    input.forEachRow { f ->
        val id = data.wordIndex.find(f[0], 0, f[0].length)
        if (id >= 0 && id < data.model.vocabularySize) known += f[1].toLong() to data.model.score(id)
        phrases.saw(f[0], f[1].toInt())
    }
    // the slope fitted too, not held at 1 as frequencies would have it: the pages are not the
    // model's kind of text, and the flatter line measured better (dev/TRAINING-PLAN.md 11.7)
    val fit = CountFit.of(known)
    out.appendLine("fit: log10 P = %.3f + %.3f log10(count + 1), over ${known.size} words the model has".format(fit.a, fit.b))
    var words = 0
    var unread = 0
    var rare = 0
    File(options.output!!).bufferedWriter().use { w ->
        w.write("${WordPack.HEADER}\n# layer: ${options.layer}\n")
        input.forEachRow { f ->
            val text = f[0]
            val count = f[1].toInt()
            val passes = f[7] == "0" && count >= options.minCount && f[3].toDouble() >= options.minPmi &&
                minOf(f[4].toDouble(), f[5].toDouble()) >= options.minEntropy && f[6].toDouble() >= options.minSurprise &&
                !phrases.isPhrase(text, count)
            if (!passes) return@forEachRow
            // a character the model all but never saw is a traditional one (視頻, 圖片) on a page the
            // page filter let through, or a typo: no word for a simplified typist either way
            if (text.any { c -> data.wordIndex.find(text, text.indexOf(c), text.indexOf(c) + 1).let { it < 0 || data.model.score(it) < RARE_CHAR } }) {
                rare++
                return@forEachRow
            }
            val pinyin = readings.of(text)
            if (pinyin == null) {
                unread++
                return@forEachRow
            }
            w.write("%s\t%s\t%.3f\n".format(text, pinyin, minOf(0f, fit.prob(count.toLong()))))
            words++
        }
    }
    out.appendLine("pack: $words words, $rare left out for a character the model hardly has, $unread for want of a reading")
}

/** Each candidate row of `words`'s output, split; the comment lines skipped. */
private fun File.forEachRow(row: (List<String>) -> Unit) = useLines { lines ->
    lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { row(it.split('\t')) }
}

// log10: a character under this in the model is as good as not in it (the dictionary's rarest are around -6)
private const val RARE_CHAR = -6.5f

/**
 * What tells a phrase or a fragment of a word from a word among the candidates of `words`, which
 * pmi and entropy let through as readily (的事情, 一个人, 务员): a function character at either
 * end (a verb's 了/过 excepted: 哭了, 累了 are typed as one), 的 anywhere, or most of its count
 * inside one longer candidate ([saw] every candidate first).
 */
private class Phrases {
    // the most any one candidate a character longer counts, by the candidate it contains
    private val longest = HashMap<String, Int>()

    fun saw(text: String, count: Int) {
        if (text.length < 3) return
        for (part in listOf(text.dropLast(1), text.drop(1))) longest.merge(part, count, ::maxOf)
    }

    fun isPhrase(text: String, count: Int): Boolean {
        if (INSIDE.any { it in text }) return true
        if (text.first() in STOP) return true
        if (text.last() in STOP && !(text.length == 2 && text.last() in VERB_END)) return true
        return (longest[text] ?: 0) > count * FRAGMENT_SHARE
    }

    private companion object {
        const val STOP = "的了是在就都也不和与着过得地吗呢吧啊呀哦嘛我你他她它们这那个把被将让给对从向为以之其此些很太更最又再还才并或而但却如若因所比跟没有要会能可"
        const val VERB_END = "了过"
        const val INSIDE = "的"
        const val FRAGMENT_SHARE = 0.5
    }
}

/** The likeliest reading of each character the dictionary has alone. */
private class CharReadings(data: PinyinData) {
    private val best = HashMap<Char, Pair<Int, Float>>()

    init {
        val d = data.dictionary
        for (i in 0 until d.childCount(d.root)) {
            val node = d.firstChild(d.root) + i
            for (w in 0 until d.wordCount(node)) {
                val word = data.vocabulary.word(d.word(node, w))
                if (word.length != 1) continue
                val weight = d.weight(node, w)
                if ((best[word[0]]?.second ?: Float.NEGATIVE_INFINITY) < weight) best[word[0]] = d.syllable(node) to weight
            }
        }
    }

    /** `pin'yin` for [text], or null if a character of it has no reading. */
    fun of(text: String): String? = text.map { best[it]?.first ?: return null }.joinToString("'") { Syllables.spelling(it) }
}

private fun mix(options: Options, out: Appendable) {
    val lm = options.lm!!
    val base = File(lm).bufferedReader().use { ArpaModel.read(it, lm) }
    out.appendLine("model: ${base.size} / ${base.bigrams.size} / ${base.trigrams.size} n-grams")
    val counts = ChatCounts(base)
    var documents = 0
    for (corpus in options.inputs) {
        if (corpus.endsWith(".parquet")) {
            WebText.read(File(corpus)) { page, _ ->
                page.forEach(counts::add)
                documents++
            }
        } else {
            GZIPInputStream(File(corpus).inputStream().buffered()).bufferedReader().use { r ->
                r.forEachNumberedLine(corpus) { line, _ ->
                    if (line.isBlank()) return@forEachNumberedLine
                    JsonStrings.parse(line).forEach(counts::add)
                    documents++
                }
            }
        }
    }
    out.appendLine("chat: $documents documents, ${counts.tokens} words, ${counts.bigrams.size} bigrams, ${counts.trigrams.size} trigrams")
    val (minBigram, minTrigram) = options.cutoffs
    val mixed = Mixer(base, counts, KneserNey(counts), options.weight, minBigram, minTrigram).mix()
    out.appendLine("mixed: ${mixed.size} / ${mixed.bigrams.size} / ${mixed.trigrams.size} n-grams")
    File(options.output!!).bufferedWriter().use { mixed.write(it) }
    out.appendLine("wrote ${options.output}: ${File(options.output).length()} bytes")
}

private const val RIME_SUFFIX = ".dict.yaml"
/** The layer of the words from Rime's dictionaries: 万象's, the only ones read so far. */
private const val WANXIANG_LAYER = "wanxiang"

private fun pinyin(output: String, lm: String, dicts: List<String>, rimeUnigrams: Long?, out: Appendable) {
    val builder = PinyinDataBuilder()
    val counts = File(lm).bufferedReader().use { r ->
        ArpaReader.read(r, lm) { words, prob, backoff ->
            when (words.size) {
                1 -> builder.unigram(words[0], prob, backoff)
                2 -> builder.bigram(words[0], words[1], prob, backoff)
                else -> builder.trigram(words[0], words[1], words[2], prob)
            }
        }
    }
    out.appendLine("model: ${counts.joinToString(" / ")} n-grams")
    val reader = PinyinDictReader(builder)
    // Rime's are read together, after libime's: a word's weights there depend on every file it is in
    val (rime, libime) = dicts.partition { it.endsWith(RIME_SUFFIX) }
    builder.layers(if (rime.isEmpty()) listOf(WordLayers.BASE) else listOf(WordLayers.BASE, WANXIANG_LAYER))
    libime.forEach { path -> File(path).bufferedReader().use { reader.read(it, path) } }
    if (rime.isNotEmpty()) {
        reader.layer = 1
        val rimeDict = RimeDict()
        rime.forEach { path -> File(path).bufferedReader().use { rimeDict.read(it, path) } }
        rimeDict.entries().forEach { (word, pinyin, weight) -> reader.add(word, pinyin, weight) }
        out.appendLine("rime: ${rimeDict.words} words")
        if (rimeUnigrams != null) {
            val fit = CountFit.of(rimeDict.totals().mapNotNull { (word, count) -> builder.unigramOf(word)?.let { count to it } }.toList())
            var added = 0
            rimeDict.totals().forEach { (word, count) ->
                if (count >= rimeUnigrams && builder.unigramOf(word) == null) {
                    builder.unigram(word, fit.prob(count), 0f)
                    added++
                }
            }
            out.appendLine("rime: $added words the model lacks scored by their counts, log10 P = %.3f + %.3f log10(count + 1)".format(fit.a, fit.b))
        }
    }
    out.appendLine("dictionary: ${reader.entries} readings")
    if (reader.skipped > 0) {
        out.appendLine("skipped ${reader.skipped} readings with unknown syllables: " +
            reader.unknownSyllables.entries.joinToString(" ") { "${it.key}×${it.value}" })
    }
    write(output, builder.build(mapOf("source" to (listOf(lm) + dicts).joinToString(",") { File(it).name })), out)
    val data = PinyinData.load(map(output))
    out.appendLine("vocabulary: ${data.vocabulary.size} words, ${data.model.vocabularySize} of them in the model")
    out.appendLine("trie: ${data.dictionary.nodeCount} nodes")
}

private fun table(output: String, input: String, out: Appendable) {
    val reader = CodeTableReader()
    File(input).bufferedReader().use { reader.read(it, input) }
    val uncoded = TableText.codePhrases(reader)
    out.appendLine("table: ${reader.entries} entries, ${reader.phrases.size - uncoded} phrases coded by the rules")
    if (uncoded > 0) out.appendLine("$uncoded phrases the rules cannot code, left out")
    if (reader.strayCodes.isNotEmpty()) {
        out.appendLine("${reader.strayCodes.size} entries use characters outside 键码, e.g. " +
            reader.strayCodes.take(STRAY_EXAMPLES).joinToString(" | "))
    }
    write(output, reader.builder.build(), out)
}

private fun write(output: String, writer: DataFile.Writer, out: Appendable) {
    File(output).outputStream().buffered().use { writer.writeTo(it) }
    out.appendLine("wrote $output: ${File(output).length()} bytes")
}

/** What the engine does: map the file rather than read it into the heap. */
private fun map(path: String): ByteBuffer =
    RandomAccessFile(path, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

/**
 * Scores every n-gram of [lm] through the compiled model and reports how far each order strays
 * from the source, probabilities and backoff weights both; unigrams must match exactly, the
 * rest by the quantisation error.
 */
private fun check(dataPath: String, lm: String, out: Appendable) {
    val data = PinyinData.load(map(dataPath))
    val ids = HashMap<String, Int>(data.vocabulary.size * 2)
    for (id in 0 until data.vocabulary.size) ids[data.vocabulary.word(id)] = id
    val prob = ErrorStats()
    val backoff = ErrorStats()
    val counts = File(lm).bufferedReader().use { r ->
        ArpaReader.read(r, lm) { words, p, bo ->
            val w = words.map { requireNotNull(ids[it]) { "\"$it\" is not in the compiled vocabulary" } }
            val m = data.model
            when (w.size) {
                1 -> {
                    prob.add(1, m.score(w[0]), p)
                    backoff.add(1, m.backoff(w[0]), bo)
                }
                2 -> {
                    prob.add(2, m.score(w[0], w[1]), p)
                    backoff.add(2, m.backoff(w[0], w[1]), bo)
                }
                else -> prob.add(3, m.score(w[0], w[1], w[2]), p)
            }
        }
    }
    counts.indices.forEach { i ->
        out.appendLine("${i + 1}-grams: ${counts[i]}, probability ${prob.describe(i + 1)}" +
            if (i + 1 < counts.size) ", backoff ${backoff.describe(i + 1)}" else "")
    }
}

private class ErrorStats {
    private val worst = FloatArray(ArpaReader.MAX_ORDER + 1)
    private val total = DoubleArray(ArpaReader.MAX_ORDER + 1)
    private val count = IntArray(ArpaReader.MAX_ORDER + 1)

    fun add(order: Int, got: Float, expected: Float) {
        val error = abs(got - expected)
        worst[order] = maxOf(worst[order], error)
        total[order] += error
        count[order]++
    }

    fun describe(order: Int) =
        if (count[order] == 0) "-" else "max error ${"%.4f".format(worst[order])}, mean ${"%.5f".format(total[order] / count[order])}"
}

private const val STRAY_EXAMPLES = 5
