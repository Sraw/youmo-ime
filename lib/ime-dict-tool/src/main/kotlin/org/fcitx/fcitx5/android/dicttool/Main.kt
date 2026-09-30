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
    usage: pinyin -o <out> --lm <lm.arpa> <dict.txt>...   compile a pinyin dictionary and model
           table -o <out> <table.txt>                     compile a code table
           mix -o <out.arpa> --lm <lm.arpa> [--weight <w>] [--cutoffs <bigram>,<trigram>] <chat.jsonl.gz>
                                                          mix a model with n-grams counted in chat
           check <pinyin data> <lm.arpa>                  compare compiled scores with the model
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
        // an inconsistency only visible once everything is read: a duplicate n-gram, no <unk> ...
        err.appendLine(e.message)
        1
    }
}

/** @return false for a command or options it does not take */
private fun dispatch(command: String?, options: Options, out: Appendable): Boolean {
    when {
        command == "pinyin" && options.output != null && options.lm != null && options.inputs.isNotEmpty() ->
            pinyin(options.output, options.lm, options.inputs, out)
        command == "table" && options.output != null && options.lm == null && options.inputs.size == 1 ->
            table(options.output, options.inputs.single(), out)
        command == "mix" && options.output != null && options.lm != null && options.inputs.size == 1 ->
            mix(options, out)
        command == "check" && options.output == null && options.lm == null && options.inputs.size == 2 ->
            check(options.inputs[0], options.inputs[1], out)
        else -> return false
    }
    return true
}

private class Options(
    val output: String?,
    val lm: String?,
    val inputs: List<String>,
    val weight: Double,
    val cutoffs: Pair<Int, Int>,
) {
    companion object {
        // what measured best on the evaluation set (dev/ENGINE-DESIGN.md)
        const val WEIGHT = 0.7
        val CUTOFFS = 2 to 2

        /** @return null for an option with a bad value */
        fun parse(args: List<String>): Options? {
            var output: String? = null
            var lm: String? = null
            var weight = WEIGHT
            var cutoffs = CUTOFFS
            val inputs = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "-o" -> output = args.getOrNull(++i)
                    "--lm" -> lm = args.getOrNull(++i)
                    "--weight" -> weight = args.getOrNull(++i)?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return null
                    "--cutoffs" -> cutoffs = args.getOrNull(++i)?.split(',')?.mapNotNull { it.toIntOrNull() }
                        // a trigram's context must be a bigram kept (Mixer)
                        ?.takeIf { it.size == 2 && it[0] in 1..it[1] }?.let { it[0] to it[1] } ?: return null
                    else -> inputs += args[i]
                }
                i++
            }
            return Options(output, lm, inputs, weight, cutoffs)
        }
    }
}

private fun mix(options: Options, out: Appendable) {
    val lm = options.lm!!
    val base = File(lm).bufferedReader().use { ArpaModel.read(it, lm) }
    out.appendLine("model: ${base.size} / ${base.bigrams.size} / ${base.trigrams.size} n-grams")
    val counts = ChatCounts(base)
    val corpus = options.inputs.single()
    var lines = 0
    GZIPInputStream(File(corpus).inputStream().buffered()).bufferedReader().use { r ->
        r.forEachNumberedLine(corpus) { line, _ ->
            if (line.isBlank()) return@forEachNumberedLine
            JsonStrings.parse(line).forEach(counts::add)
            lines++
        }
    }
    out.appendLine("chat: $lines conversations, ${counts.tokens} words, ${counts.bigrams.size} bigrams, ${counts.trigrams.size} trigrams")
    val (minBigram, minTrigram) = options.cutoffs
    val mixed = Mixer(base, counts, KneserNey(counts), options.weight, minBigram, minTrigram).mix()
    out.appendLine("mixed: ${mixed.size} / ${mixed.bigrams.size} / ${mixed.trigrams.size} n-grams")
    File(options.output!!).bufferedWriter().use { mixed.write(it) }
    out.appendLine("wrote ${options.output}: ${File(options.output).length()} bytes")
}

private fun pinyin(output: String, lm: String, dicts: List<String>, out: Appendable) {
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
    dicts.forEach { path -> File(path).bufferedReader().use { reader.read(it, path) } }
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
