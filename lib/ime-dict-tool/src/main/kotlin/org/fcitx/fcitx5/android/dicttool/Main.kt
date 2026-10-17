/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.CodeTableReader
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.Misreadings
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.data.WordIndex
import org.fcitx.fcitx5.android.engine.data.WordLayers
import org.fcitx.fcitx5.android.engine.data.forEachNumberedLine
import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.fcitx.fcitx5.android.engine.table.TableText
import org.fcitx.fcitx5.android.engine.user.WordPack
import org.fcitx.fcitx5.android.input.voice.HotwordSpelling
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Formatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * A command, all [runCli] knows of it in one place: its [synopsis], whose flags are those it takes
 * (the bracketed ones it can do without; each takes a value), what it does, how many inputs it
 * takes, and what runs it.
 */
private class Command(val synopsis: String, about: String, val inputs: IntRange = 1..Int.MAX_VALUE, val action: (Options, Appendable) -> Unit) {
    val name = synopsis.substringBefore(' ')
    val about = about.trimIndent()
    private val words = synopsis.split(' ')
    val required = words.filter { it.startsWith('-') }.toSet()
    val flags = required + words.filter { it.startsWith("[-") }.map { it.removePrefix("[") }
}

private val COMMANDS = listOf(
    Command(
        "pinyin -o <out> --lm <lm.arpa> [--remove <remove.tsv>] [--readings <readings.tsv>] [--misreadings <misreadings.tsv>] <dict>...",
        """
            compile a pinyin dictionary and model, from
            libime's text and word packs (.words), each
            pack a layer of its own; the text's words
            corrected by lexicon/'s lists
        """,
        action = ::pinyin,
    ),
    Command(
        "table -o <out> [--words <pack.words>] [--pinyin <pinyin.data>] <table.txt>",
        """
            compile a code table, with the words of a
            word pack and of compiled pinyin data its
            组词规则 can code, the commonest first
        """,
        inputs = 1..1,
    ) { options, out -> table(options.output!!, options.inputs.single(), options.words, options.pinyin, out) },
    Command("strokes -o <out> <stroke.dict.yaml>", "compile rime-stroke's characters by their strokes", 1..1) { options, out ->
        strokes(options.output!!, options.inputs.single(), out)
    },
    Command(
        "emoji -o <out> --test <emoji-test.txt> [--lexicon <emoji.tsv>] [--pinyin <pinyin.data>] <annotations/zh.xml>",
        """
            the emoji panel's groups and the Chinese
            words emoji stand for, from Unicode's
            emoji-test.txt, CLDR's annotations and
            lexicon/emoji.tsv's edits (EmojiAnnotations),
            each word's pinyin as the data reads it
        """,
        inputs = 1..1,
    ) { options, out -> emoji(options, out) },
    Command(
        "hotwords -o <out> <pack.words>",
        """
            a word pack's words as the voice recognizer's
            hotwords (HotwordSpelling.spelled)
        """,
        inputs = 1..1,
    ) { options, _ -> File(options.output!!).writeText(hotwords(File(options.inputs.single()).readLines())) },
    Command(
        "mix -o <out.arpa> --lm <lm.arpa> [--weight <w>] [--cutoffs <bigram>,<trigram>] <corpus>...",
        """
            mix a model with n-grams counted in chat:
            conversations a JSON array a line (.jsonl.gz),
            or the chat-like pages of shards (.parquet),
            FineWeb-2's or a CommonCrawl crawl's alike;
            a word pack's words (.words) join the model's
        """,
        action = ::mix,
    ),
    Command("check <pinyin data> <lm.arpa>", "compare compiled scores with the model", 2..2) { options, out ->
        check(options.inputs[0], options.inputs[1], out)
    },
    Command(
        "words -o <out.tsv> --data <pinyin.data> [--min-count <n>] [--sketch-bits <n>] <shard.parquet>...",
        """
            the words the pages use that the data lacks
            (NewWords); --sketch-bits as clean's, though
            it keeps a sketch for each length, 2 to 4:
            three times the memory, up to 12 GB at 30
        """,
        action = ::words,
    ),
    Command(
        "pack -o <out.words> --data <pinyin.data> --layer <name> [--min-count <n>] [--min-pmi <x>] [--min-entropy <x>] [--min-surprise <x>] [--only <words.txt>] [--lexicon <add.tsv>] <candidates.tsv>",
        """
            a word pack (WordPack) of the candidates that pass,
            or of the words a curated list takes (lexicon/)
        """,
        inputs = 1..1,
        action = ::pack,
    ),
    Command(
        "cc -o <out dir> --crawl <CC-MAIN-...> [--from <n>] --files <n> [--base <url>]",
        """
            the Chinese pages of a CommonCrawl crawl's WET files,
            a shard of 100 files each (CommonCrawl)
        """,
        inputs = 0..0,
    ) { options, out ->
        options.crawl.let { CommonCrawl.extract(it.name!!, it.from, it.files!!, File(options.output!!), it.base) { line -> out.appendLine(line) } }
    },
    Command(
        "clean -o <out dir> [--sketch-bits <n>] <shard.parquet>...",
        """
            the shards without their boilerplate and spam,
            a shard each, of the same name (PageCleaner); a
            bit more a doubling of the lines past one crawl's
        """,
        action = ::clean,
    ),
    Command(
        "examples -o <out.tsv> --only <words.txt> [--per-word <n>] <shard.parquet>...",
        "sentences of the pages that use each word (Examples)",
        action = ::examples,
    ),
    Command(
        "english-lm -o <out.arpa> [--known <en_dict.fscd>] [--vocabulary <n>] [--tokens <n>] [--cutoffs <bigram>,<trigram>] <shard.parquet>...",
        """
            the English model: the words of FineWeb's pages
            and their n-grams, Kneser-Ney (EnglishVocabulary)
        """,
        action = ::englishLm,
    ),
    Command("english -o <out> --lm <english.arpa>", "compile the English words and model (EnglishData)", 0..0, ::english),
    Command(
        "prune -o <out.arpa> --lm <lm.arpa> --threshold <t> [--start-prob <p>]",
        """
            the model less the n-grams that tell it less than t
            in relative entropy (Pruner, SRILM's -prune); p how
            often a word is its sentence's first, the one after
            <s>: measured on English, 1/14, if not given
        """,
        0..0,
        ::prune,
    ),
    Command(
        "text -o <out.jsonl.gz> <shard.parquet>...",
        """
            the chat-like pages as mix takes them, a JSON array
            of a page's lines a line: an evaluation set's source
        """,
        action = ::text,
    ),
)

/** Each command's synopsis, what it does beside it or, where there is no room, under it. */
val USAGE: String = COMMANDS.withIndex().joinToString("\n") { (i, command) ->
    val head = (if (i == 0) "usage: " else " ".repeat("usage: ".length)) + command.synopsis
    val about = command.about.lines()
    val indent = " ".repeat(ABOUT_COLUMN)
    val lines = if (head.length + 2 <= ABOUT_COLUMN) listOf(head.padEnd(ABOUT_COLUMN) + about[0]) else listOf(head, indent + about[0])
    (lines + about.drop(1).map { indent + it }).joinToString("\n")
}

private const val ABOUT_COLUMN = 54

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

/** @return the process exit code: 0, 1 for bad input data, 2 for a usage error */
fun runCli(args: Array<String>, out: Appendable, err: Appendable): Int {
    val command = COMMANDS.find { it.name == args.firstOrNull() }
    val options = command?.let { Options.parse(it, args.drop(1)) }
    return try {
        if (command == null || options == null) {
            err.appendLine(USAGE)
            return 2
        }
        command.action(options, out)
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

private class Options(
    val output: String?,
    val lm: String?,
    val inputs: List<String>,
    val weight: Double,
    val cutoffs: Pair<Int, Int>,
    /** `words`, `pack`: the pinyin data whose words are known. */
    val data: String?,
    /** `words`: how often a run must occur to be a candidate; `pack`: to go in. */
    val minCount: Int,
    /** `pack`: the layer the words are of, and the least pmi, entropy (either side) and surprise a candidate needs. */
    val layer: String?,
    val minPmi: Double,
    val minEntropy: Double,
    val minSurprise: Double,
    /** `pack`: a file of the only words that may go in (the first field of each line), if any; `examples`: the words. */
    val only: String?,
    /** `examples`: sentences a word. */
    val perWord: Int,
    /** `pack`: a curated list, `word<TAB>reading...` (lexicon/add.tsv): its words and no other, as it reads them; `emoji`: lexicon/emoji.tsv, the edits. */
    val lexicon: String?,
    /**
     * `clean`: the line sketch's width, so twice the lines collide no more than one crawl's did;
     * `words`: the widest of its run sketches, for the same.
     */
    val sketchBits: Int,
    /** `pinyin`: lexicon/remove.tsv, words the dictionaries have wrongly, and lexicon/readings.tsv, readings put right. */
    val remove: String?,
    val readings: String?,
    /** `pinyin`: lexicon/misreadings.tsv, words often read wrongly, typed either way. */
    val misreadings: String?,
    /** `table`: a word pack whose words go in, coded by the table's rules. */
    val words: String?,
    /** `table`: compiled pinyin data whose words go in too; `emoji`: that reads the words. */
    val pinyin: String?,
    /** `emoji`: Unicode's emoji-test.txt. */
    val test: String?,
    /** `english-lm`: how many words, and at most how many of the pages' words to read. */
    val vocabulary: Int,
    val tokens: Long,
    /** `english-lm`: a spell dictionary (fcitx's .fscd) whose words are none of the pages' slips. */
    val known: String?,
    /** `prune`: the least relative entropy an n-gram must be worth to stay. */
    val threshold: Double,
    /** `prune`: how often a word is the first of its sentence, the likelihood of `<s>` as a context. */
    val startProb: Double,
    val crawl: Crawl,
) {
    /** `cc`: the crawl, its first WET file and how many, and where CommonCrawl is. */
    class Crawl(val name: String?, val from: Int, val files: Int?, val base: String) {
        companion object {
            /** @return null for a bad value */
            fun parse(values: Map<String, String>): Crawl? = Crawl(
                name = values["--crawl"],
                from = values["--from"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null } ?: 0,
                files = values["--files"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: return null },
                base = values["--base"] ?: CommonCrawl.BASE,
            )
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

        const val MAX_SKETCH_BITS = 30

        // Gboard's own lists are of this order; what the pages have past it is mostly names and slips
        const val VOCABULARY = 200_000

        // how often a word starts its sentence: lib/ime-eval/data/english.txt has 19,669 words in 1,500
        const val START_PROB = 1.0 / 14

        // every command's flags, each taking a value: a value is none of them
        private val FLAGS = COMMANDS.flatMapTo(HashSet()) { it.flags }

        /** [flag]'s value, [default] without one, null for a bad one. */
        private fun <T : Any> Map<String, String>.read(flag: String, default: T, value: (String) -> T?): T? =
            this[flag]?.let(value) ?: default.takeIf { flag !in this }

        private fun Map<String, String>.count(flag: String, default: Int, range: IntRange): Int? =
            read(flag, default) { it.toIntOrNull()?.takeIf { n -> n in range } }

        // a trigram's context must be a bigram kept (Mixer); every field a number, 2,x,3 no 2,3
        private fun cutoffsOf(value: String): Pair<Int, Int>? {
            val n = value.split(',').map { it.toIntOrNull() ?: return null }
            return n.takeIf { it.size == 2 && it[0] in 1..it[1] }?.let { it[0] to it[1] }
        }

        /**
         * [args] as the values of [command]'s flags and its inputs; null for a flag it does not take
         * (or none does), one it needs missing, one given twice or without a value, or more or fewer
         * inputs than it takes.
         */
        private fun split(command: Command, args: List<String>): Pair<Map<String, String>, List<String>>? {
            val values = HashMap<String, String>()
            val inputs = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when {
                    arg in command.flags -> {
                        // `-o --lm x` names no output --lm
                        val value = args.getOrNull(++i)?.takeIf { it !in FLAGS } ?: return null
                        if (values.put(arg, value) != null) return null
                    }
                    // a flag of another command or of none, which a path taken for an input would hide
                    arg.startsWith('-') -> return null
                    else -> inputs += arg
                }
                i++
            }
            if (!values.keys.containsAll(command.required) || inputs.size !in command.inputs) return null
            return values to inputs
        }

        /** @return null for what [command] does not take ([split]), or an option with a bad value */
        fun parse(command: Command, args: List<String>): Options? {
            val (values, inputs) = split(command, args) ?: return null
            return Options(
                output = values["-o"],
                lm = values["--lm"],
                inputs = inputs,
                weight = values.read("--weight", WEIGHT) { it.toDoubleOrNull()?.takeIf { w -> w in 0.0..1.0 } } ?: return null,
                cutoffs = values.read("--cutoffs", CUTOFFS, ::cutoffsOf) ?: return null,
                data = values["--data"],
                minCount = values.count("--min-count", MIN_COUNT, 1..Int.MAX_VALUE) ?: return null,
                layer = values["--layer"]?.let { it.takeIf(WordPack::validLayer) ?: return null },
                minPmi = values.read("--min-pmi", MIN_PMI) { it.toDoubleOrNull()?.takeIf(Double::isFinite) } ?: return null,
                minEntropy = values.read("--min-entropy", MIN_ENTROPY) { it.toDoubleOrNull()?.takeIf(Double::isFinite) } ?: return null,
                minSurprise = values.read("--min-surprise", MIN_SURPRISE) { it.toDoubleOrNull()?.takeIf(Double::isFinite) } ?: return null,
                only = values["--only"],
                perWord = values.count("--per-word", Examples.PER_WORD, 1..Int.MAX_VALUE) ?: return null,
                crawl = Crawl.parse(values) ?: return null,
                lexicon = values["--lexicon"],
                // past 30 the cells would not fit an array
                sketchBits = values.count("--sketch-bits", NewWords.SKETCH_BITS, NewWords.MIN_SKETCH_BITS..MAX_SKETCH_BITS) ?: return null,
                remove = values["--remove"],
                readings = values["--readings"],
                misreadings = values["--misreadings"],
                words = values["--words"],
                pinyin = values["--pinyin"],
                test = values["--test"],
                // the start's id must fit an n-gram key too (EnglishCounts)
                vocabulary = values.count("--vocabulary", VOCABULARY, 1 until NgramKey.MAX_WORDS - 2) ?: return null,
                tokens = values.read("--tokens", Long.MAX_VALUE) { it.toLongOrNull()?.takeIf { n -> n >= 1 } } ?: return null,
                known = values["--known"],
                threshold = values.read("--threshold", 0.0) { it.toDoubleOrNull()?.takeIf { t -> t >= 0 && t.isFinite() } } ?: return null,
                // what Pruner takes: a bad value is a usage error, not its exception
                startProb = values.read("--start-prob", START_PROB) { it.toDoubleOrNull()?.takeIf { p -> p > 0 && p < 1 } } ?: return null,
            )
        }
    }
}

/** The first field of each line of [file], its comments (`#`) and blank lines left out, in its order. */
private fun wordList(file: File): LinkedHashSet<String> = file.useLines { lines ->
    lines.map { it.substringBefore('\t').trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toCollection(LinkedHashSet())
}

/**
 * Whether a candidate's numbers pass the thresholds. An entropy not measured (NaN: the run's pmi
 * was too low to gather its neighbours) is no reason to leave it out when the pmi asked for is
 * lower still. A run of two whose entropy was measured is held to that alone: its pmi says little
 * (NewWords.GATHER_PMI).
 */
private fun Options.passes(c: CandidateRow): Boolean {
    if (c.known || c.count < minCount || c.surprise < minSurprise) return false
    val entropy = minOf(c.leftEntropy, c.rightEntropy)
    return when {
        entropy.isNaN() -> c.pmi >= minPmi
        c.text.length == 2 -> entropy >= minEntropy
        else -> c.pmi >= minPmi && entropy >= minEntropy
    }
}

/**
 * Whether a character of [text] is one the model all but never saw: a traditional one (視頻, 圖片)
 * on a page the page filter let through, or a typo, no word for a simplified typist either way.
 */
private fun rare(data: PinyinData, text: String): Boolean =
    text.indices.any { i -> data.wordIndex.find(text, i, i + 1).let { it < 0 || data.model.score(it) < RARE_CHAR } }

/**
 * A curated list's words and readings, `word<TAB>pin'yin[<TAB>...]` a line, `#` comments: each
 * line's to [each], in the list's order.
 * @throws SourceException for a line without a reading, a syllable the engine does not know, or
 * a reading of more or fewer syllables than the word has characters
 */
private fun curated(file: File, each: (text: String, reading: String) -> Unit) = file.bufferedReader().use { reader ->
    reader.forEachNumberedLine(file.path) { line, n ->
        if (line.isBlank() || line.startsWith('#')) return@forEachNumberedLine
        val f = line.split('\t')
        val text = f[0].trim()
        val reading = f.getOrNull(1)?.trim().orEmpty()
        val syllables = reading.split('\'')
        // by code point, as the dictionaries and misreadings.tsv count: a character past U+FFFF is two chars and a syllable
        if (syllables.size != text.codePointCount(0, text.length) || syllables.any { Syllables.id(it) < 0 }) {
            throw SourceException(file.path, n, "\"$reading\" is no reading of $text")
        }
        each(text, reading)
    }
}

/** lexicon/add.tsv's words, each as it reads it ([curated]). */
private fun lexicon(file: File): Map<String, String> = LinkedHashMap<String, String>().apply {
    curated(file) { text, reading -> this[text] = reading }
}

/** lexicon/readings.tsv's words, each with every reading it lists, a line a reading ([curated]). */
private fun curatedReadings(file: File): Map<String, List<String>> = LinkedHashMap<String, MutableList<String>>().apply {
    curated(file) { text, reading -> getOrPut(text) { ArrayList() } += reading }
}

/**
 * lexicon/misreadings.tsv ([Misreadings.parse]), each reading and misreading one the engine can type.
 * @throws SourceException for a line that is no entry, or a syllable the engine does not know
 */
private fun misreadings(file: File): List<Misreadings> {
    val lines = file.readLines()
    val entries = Misreadings.parse(lines.asSequence(), file.path)
    // parse keeps no line numbers: each line read again alone, which it parsed whole already, for its entry's
    lines.forEachIndexed { i, line ->
        val entry = Misreadings.parse(sequenceOf(line), file.path).singleOrNull() ?: return@forEachIndexed
        val bad = (listOf(entry.typed) + entry.misread).firstOrNull { r -> r.split('\'').any { Syllables.id(it) < 0 } } ?: return@forEachIndexed
        throw SourceException(file.path, i + 1, "\"$bad\" is no reading of ${entry.word}")
    }
    return entries
}

/** Each chat-like page's texts as a JSON array of strings, a line a page, gzipped: for the eval-set tools. */
private fun text(options: Options, out: Appendable) {
    var pages = 0
    GZIPOutputStream(File(options.output!!).outputStream().buffered()).bufferedWriter().use { w ->
        options.inputs.forEach { shard ->
            WebText.read(File(shard)) { page, _ ->
                w.write(JsonStrings.format(page))
                w.write("\n")
                pages++
            }
        }
    }
    out.appendLine("text: $pages pages")
}

/** `word<TAB>sentence...`, a line for each word of the list found, in the list's order. */
private fun examples(options: Options, out: Appendable) {
    val words = wordList(File(options.only!!))
    val examples = Examples(words, options.perWord)
    var pages = 0L
    options.inputs.forEach { shard -> WebText.pages(File(shard)) { examples.page(it.text); pages++ } }
    val found = examples.sentences()
    File(options.output!!).bufferedWriter().use { w ->
        for (word in words) {
            val sentences = found[word] ?: continue
            w.write(word)
            for (s in sentences) w.write("\t" + s.replace('\t', ' '))
            w.write("\n")
        }
    }
    out.appendLine("examples: ${found.size} of ${words.size} words found in $pages pages")
}

/**
 * Each shard without its boilerplate and spam, written to the output directory under its name:
 * every shard's lines are counted first, as a menu repeats over pages of more than one shard.
 */
private fun clean(options: Options, out: Appendable) {
    val dir = File(options.output!!)
    val shards = options.inputs.map(::File)
    // written in place, a raw shard is gone, and a run stopped halfway leaves raw and clean mixed
    require(shards.none { it.canonicalFile.parentFile == dir.canonicalFile }) { "$dir holds the shards: each would be overwritten" }
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make $dir")
    require(shards.map { it.name }.toSet().size == shards.size) { "two shards of one name: one would overwrite the other" }
    val cleaner = PageCleaner(options.sketchBits)
    var pages = 0L
    shards.forEach { shard -> WebText.pages(shard) { cleaner.count(it.text); pages++ } }
    var kept = 0L
    var han = 0L
    for (shard in shards) {
        val clean = ArrayList<WebText.Page>()
        WebText.pages(shard) { page -> cleaner.clean(page.text)?.let { clean += WebText.Page(page.url, it, page.date) } }
        WebText.write(File(dir, shard.name), clean)
        kept += clean.size
        han += clean.sumOf { page -> page.text.count(TextChars::isHan) }
        out.appendLine("${shard.name}: ${clean.size} pages kept")
    }
    out.appendLine("clean: $kept of $pages pages kept, $han Han characters")
}

/**
 * The runs of Han characters the pages use that the data's base layer lacks, with what tells a
 * word from a chance run: `text count year pmi left-entropy right-entropy surprise known`, a
 * line each ([CandidateRow]), in no order ([NewWords], [Surprise]). Lines starting `#` are comments.
 */
private fun words(options: Options, out: Appendable) {
    val data = PinyinData.load(map(options.data!!))
    // the base layer's words: the others are a pack's, the kind a pack is for, scored by true counts
    val known = HashSet<String>(data.vocabulary.size * 2)
    for (id in 0 until data.vocabulary.size) if (data.layers.layer(id) == 0) known += data.vocabulary.word(id)
    val finder = NewWords(known, options.minCount, sketchBits = options.sketchBits)
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
        formatting(w) { formatter ->
            w.write(CandidateRow.HEADER + "\n")
            w.write("# chars\t${finder.chars}\n")
            finder.candidates { c ->
                val s = surprise.of(c.text, c.count, finder.chars)
                CandidateRow(c.text, c.count, c.year, c.pmi, c.leftEntropy, c.rightEntropy, s, c.known).writeTo(formatter)
                all++
                if (!c.known) unknown++
            }
        }
    }
    out.appendLine("words: $all runs of at least ${options.minCount}, $unknown of them not in the data")
}

/**
 * A line of `words`'s output by its fields' names: what [words] writes and [pack] reads back, and
 * lexicon/tools/batches.py by [HEADER]'s names, so a column moved or added misleads neither.
 */
private class CandidateRow(
    val text: String,
    val count: Int,
    val year: Int,
    val pmi: Double,
    val leftEntropy: Double,
    val rightEntropy: Double,
    val surprise: Double,
    val known: Boolean,
) {
    /** Writes the row, a line, through [formatter]. */
    fun writeTo(formatter: Formatter) {
        formatter.format("%s\t%d\t%d\t%.3f\t%.3f\t%.3f\t%.3f\t%d\n", text, count, year, pmi, leftEntropy, rightEntropy, surprise, if (known) 1 else 0)
    }

    companion object {
        const val HEADER = "# text\tcount\tyear\tpmi\tleft_entropy\tright_entropy\tsurprise\tknown"
        private val FIELDS = HEADER.removePrefix("# ").split('\t').size

        /** @throws IllegalArgumentException for a line that is none */
        fun parse(line: String): CandidateRow {
            val f = line.split('\t')
            require(f.size == FIELDS && (f[7] == "0" || f[7] == "1")) { "expected \"${HEADER.removePrefix("# ")}\", got \"$line\"" }
            return CandidateRow(f[0], f[1].toInt(), f[2].toInt(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toDouble(), f[7] == "1")
        }
    }
}

/**
 * Each candidate of `words`'s output, its first line [CandidateRow.HEADER], the comment lines skipped.
 * @throws SourceException for a file of another kind or layout, or a line that is no candidate
 */
private fun File.forEachCandidate(row: (CandidateRow) -> Unit) = bufferedReader().use { reader ->
    reader.forEachNumberedLine(path) { line, n ->
        if (n == 1 && line != CandidateRow.HEADER) throw SourceException(path, n, "not the output of words: expected \"${CandidateRow.HEADER}\"")
        if (line.isNotBlank() && !line.startsWith('#')) row(CandidateRow.parse(line))
    }
}

/**
 * The candidates of `words` that pass the thresholds and are not plainly phrases or fragments
 * ([Phrases]), as a word pack: each read as the dictionary's words in it are ([Readings]), scored on the
 * model's unigram scale by its count ([CountFit] over the candidates the model knows). One with
 * a character no reading is known for is left out. Given a curated list, its words instead ([packCurated]).
 */
private fun pack(options: Options, out: Appendable) {
    val data = PinyinData.load(map(options.data!!))
    val input = File(options.inputs.single())
    // what a curator took, as it reads it: no threshold or guess of ours second-guesses that
    val lexicon = options.lexicon?.let { lexicon(File(it)) }
    // a list that says which runs are words (an encyclopedia's titles): what pmi and entropy only
    // guess at, and most of what they let through is not (dev/TRAINING-PLAN.md 11.7b)
    val only = options.only?.let { wordList(File(it)) }
    // two reads of the candidates rather than all of them in memory: there are millions
    val known = ArrayList<Pair<Long, Float>>()
    // every word of the dictionary, not the model's alone (data.wordIndex): most are not the model's
    val dictionary = WordIndex(data.vocabulary, data.vocabulary.size)
    val phrases = Phrases { text, from, to -> dictionary.find(text, from, to) >= 0 }
    input.forEachCandidate { c ->
        val id = data.wordIndex.find(c.text, 0, c.text.length)
        // the base layer's alone: a curated word the model has took its score from an earlier fit
        if (id >= 0 && id < data.model.vocabularySize && data.layers.layer(id) == 0) known += c.count.toLong() to data.model.score(id)
        phrases.saw(c.text, c.count)
    }
    // the slope fitted too, not held at 1 as frequencies would have it: the pages are not the
    // model's kind of text, and the flatter line measured better (dev/TRAINING-PLAN.md 11.7)
    val fit = CountFit.of(known)
    out.appendLine("fit: log10 P = %.3f + %.3f log10(count + 1), over ${known.size} words the model has".format(Locale.ROOT, fit.a, fit.b))
    File(options.output!!).bufferedWriter().use { w ->
        formatting(w) { formatter ->
            w.write("${WordPack.HEADER}\n# layer: ${options.layer}\n")
            if (lexicon != null) formatter.packCurated(lexicon, input, fit, options.minCount, out) else formatter.packPassing(options, data, phrases, only, input, fit, out)
        }
    }
}

/**
 * The words of a curated list, as it reads them: those among the candidates scored by their count,
 * in the candidates' order, the rest after.
 */
private fun Formatter.packCurated(lexicon: Map<String, String>, input: File, fit: CountFit, minCount: Int, out: Appendable) {
    val unseen = lexicon.keys.toMutableSet()
    var words = 0
    input.forEachCandidate { c ->
        if (unseen.remove(c.text)) {
            packLine(c.text, lexicon.getValue(c.text), fit, c.count.toLong())
            words++
        }
    }
    // taken from elsewhere than the pages (a report of a missing word, a failed evaluation):
    // scored as the least a candidate is counted
    unseen.forEach { text ->
        packLine(text, lexicon.getValue(text), fit, minCount.toLong())
        words++
    }
    out.appendLine("pack: ${unseen.size} of the list's words not among the candidates, scored as seen $minCount times")
    out.appendLine("pack: $words words")
}

/** The candidates that pass, of [only] if it is given, less the phrases and those of a character the model hardly has. */
private fun Formatter.packPassing(options: Options, data: PinyinData, phrases: Phrases, only: Set<String>?, input: File, fit: CountFit, out: Appendable) {
    val readings = Readings(data)
    var words = 0
    var unread = 0
    var rare = 0
    input.forEachCandidate { c ->
        val listed = only == null || c.text in only
        if (!listed || !options.passes(c) || phrases.isPhrase(c.text, c.count)) return@forEachCandidate
        if (rare(data, c.text)) {
            rare++
            return@forEachCandidate
        }
        val pinyin = readings.of(c.text)
        if (pinyin == null) {
            unread++
            return@forEachCandidate
        }
        packLine(c.text, pinyin, fit, c.count.toLong())
        words++
    }
    out.appendLine("pack: $words words, $rare left out for a character the model hardly has, $unread for want of a reading")
}

/** A word pack's line: [text] under [reading], scored as [fit] has [count], no likelier than certain. */
private fun Formatter.packLine(text: String, reading: String, fit: CountFit, count: Long) {
    format("%s\t%s\t%.3f\n", text, reading, minOf(0f, fit.prob(count)))
}

// log10: a character under this in the model is as good as not in it (the dictionary's rarest are around -6)
private const val RARE_CHAR = -6.5f

private fun mix(options: Options, out: Appendable) {
    val lm = options.lm!!
    var base = File(lm).bufferedReader().use { ArpaModel.read(it, lm) }
    out.appendLine("model: ${base.size} / ${base.bigrams.size} / ${base.trigrams.size} n-grams")
    // a word pack's words join the vocabulary the text is split into, at the pack's scores: only
    // a unigram the model has gets n-grams, and a curated word would otherwise never have a context
    val (packFiles, corpora) = options.inputs.partition { it.endsWith(PACK_SUFFIX) }
    if (packFiles.isNotEmpty()) {
        val words = packFiles.flatMap { path ->
            File(path).useLines { WordPack.parse(it, path) }.words.map { it.entry.text to it.score.toDouble() }
        }
        val before = base.size
        base = base.withUnigrams(words)
        out.appendLine("packs: ${words.size} words, ${base.size - before} of them new to the model")
    }
    val counts = ChatCounts(base)
    var documents = 0
    for (corpus in corpora) {
        if (corpus.endsWith(".parquet")) {
            // a CommonCrawl crawl's shards as FineWeb-2's: the chat-like pages alone, as measured
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

private const val PACK_SUFFIX = ".words"

/**
 * The words of fcitx's spell dictionary (`en_dict.fscd`): `FSCD0000`, the count, then per word a
 * little-endian 16-bit weight and the word, 0-ended.
 */
internal fun spellWords(bytes: ByteArray): List<String> {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val magic = "FSCD0000"
    require(bytes.size >= magic.length + Int.SIZE_BYTES && String(bytes, 0, magic.length, Charsets.US_ASCII) == magic) { "not a spell dictionary" }
    b.position(magic.length)
    val count = b.int
    // the file's count, which a broken one has past anything it holds: a word takes three bytes at least
    val words = ArrayList<String>(count.coerceIn(0, bytes.size / MIN_SPELL_ENTRY))
    while (words.size < count && b.remaining() > Short.SIZE_BYTES) {
        b.short
        val start = b.position()
        while (b.hasRemaining() && b.get() != 0.toByte()) Unit
        val end = b.position() - if (bytes[b.position() - 1] == 0.toByte()) 1 else 0
        if (end > start) words += String(bytes, start, end - start, Charsets.UTF_8)
    }
    return words
}

// a word's weight and one char, its 0 left off as the last one's may be
private const val MIN_SPELL_ENTRY = 3

private fun englishLm(options: Options, out: Appendable) {
    val shards = options.inputs.map(::File)
    val vocabulary = EnglishVocabulary()
    readEnglish(shards, options.tokens, vocabulary::add)
    // fcitx's spell dictionary, which the app has had: its words are no slips
    val known = options.known?.let { path ->
        spellWords(File(path).readBytes()).mapTo(HashSet(), EnglishVocabulary::key)
    }.orEmpty()
    val words = vocabulary.words(options.vocabulary, known)
    out.appendLine("pass one: ${vocabulary.spellings} spellings, ${words.list.size} words, ${words.slips.size} slips, ${words.exempted} under a tenth of their group's commonest")
    // for a look at what was taken for a slip, and of what
    File(options.output!! + ".slips").printWriter().use { w -> words.slips.toSortedMap().forEach { (slip, of) -> w.println("$slip\t$of") } }
    val counts = EnglishCounts(words, vocabulary.tokens)
    readEnglish(shards, options.tokens, counts::add)
    out.appendLine("pass two: ${counts.tokens} words, ${counts.bigrams.size} bigrams, ${counts.trigrams.size} trigrams")
    // no model to mix with: Kneser-Ney's alone, its n-grams cut off and the backoff weights worked out
    val n = words.list.size
    val none = ArpaModel(words.list, DoubleArray(n) { NO_PROB }, DoubleArray(n), LongIndex(), DoubleColumn(), DoubleColumn(), LongIndex(), DoubleColumn())
    val (minBigram, minTrigram) = options.cutoffs
    val model = Mixer(none, counts, KneserNey(counts), 0.0, minBigram, minTrigram).mix()
    out.appendLine("model: ${model.size} / ${model.bigrams.size} / ${model.trigrams.size} n-grams")
    File(options.output!!).bufferedWriter().use { model.write(it) }
    out.appendLine("wrote ${options.output}: ${File(options.output).length()} bytes")
}

// what the model of no n-grams gives every word: 10^-∞ is 0, so the mix at weight 0 is Kneser-Ney's
// whole, whatever a weight would multiply it by
private const val NO_PROB = Double.NEGATIVE_INFINITY

/** Each run of words of [shards]' pages, as [EnglishText] splits them, till [tokens] words are read. */
private fun readEnglish(shards: List<File>, tokens: Long, run: (List<String>, Boolean) -> Unit) {
    var read = 0L
    try {
        for (shard in shards) {
            WebText.pages(shard) { page ->
                EnglishText.runs(page.text) { words, sentence ->
                    run(words, sentence)
                    read += words.size
                }
                if (read >= tokens) throw Enough()
            }
        }
    } catch (_: Enough) {
        // as many as asked for: the rest of the shard unread
    }
}

private class Enough : RuntimeException(null, null, false, false)

private fun pinyin(options: Options, out: Appendable) {
    val output = options.output!!
    val lm = options.lm!!
    val dicts = options.inputs
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
    // lexicon/remove.tsv's first field; lexicon/readings.tsv's first two, a line a reading, each
    // checked as add.tsv is: a reading the engine cannot type would take its word out of the data
    val removed = options.remove?.let { wordList(File(it)) }.orEmpty()
    val readings = options.readings?.let { curatedReadings(File(it)) }.orEmpty()
    val misread = options.misreadings?.let { misreadings(File(it)) }.orEmpty()
    val reader = PinyinDictReader(builder, removed, readings, misread.associate { it.word to (listOf(it.typed) + it.misread) })
    val (packFiles, texts) = dicts.partition { it.endsWith(PACK_SUFFIX) }
    val packs = packFiles.map { path -> File(path).useLines { WordPack.parse(it, path) } }
    val layers = (listOf(WordLayers.BASE) + packs.map { it.layer }).distinct()
    builder.layers(layers)
    texts.forEach { path -> File(path).bufferedReader().use { reader.read(it, path) } }
    reader.corrected()
    if (reader.corrections > 0) out.appendLine("corrected: ${reader.corrections} readings of the dictionaries left out or replaced")
    // scored as the engine scores a pack the user put in: a word the model lacks as the pack says.
    // Unlike one put in, a word the dictionary has stays in its layer, and the first pack to list a word holds
    for ((path, pack) in packFiles.zip(packs)) {
        val layer = layers.indexOf(pack.layer)
        var scored = 0
        for (word in pack.words) {
            builder.entry(word.entry.text, word.entry.syllables, 0f, layer)
            if (builder.unigramOf(word.entry.text) == null) {
                builder.unigram(word.entry.text, word.score, 0f)
                scored++
            }
        }
        out.appendLine("${File(path).name}: ${pack.words.size} words in layer ${pack.layer}, $scored of them scored as the pack says")
    }
    out.appendLine("dictionary: ${reader.entries} readings")
    if (reader.skipped > 0) {
        out.appendLine("skipped ${reader.skipped} readings with unknown syllables: " +
            reader.unknownSyllables.entries.joinToString(" ") { "${it.key}×${it.value}" })
    }
    if (misread.isNotEmpty()) out.appendLine("misread: ${misread.size} words, typed by their misreadings too")
    val meta = mapOf("source" to (listOf(lm) + dicts).joinToString(",") { File(it).name }) +
        misread.associate { Misreadings.META_PREFIX + it.word to Misreadings.meta(it) }
    write(output, builder.build(meta), out)
    val data = PinyinData.load(map(output))
    out.appendLine("vocabulary: ${data.vocabulary.size} words, ${data.model.vocabularySize} of them in the model")
    out.appendLine("trie: ${data.dictionary.nodeCount} nodes")
}

private fun table(output: String, input: String, words: String?, pinyin: String?, out: Appendable) {
    val reader = CodeTableReader()
    File(input).bufferedReader().use { reader.read(it, input) }
    val uncoded = TableText.codePhrases(reader)
    out.appendLine("table: ${reader.entries} entries, ${reader.phrases.size - uncoded} phrases coded by the rules")
    if (uncoded > 0) out.appendLine("$uncoded phrases the rules cannot code, left out")
    if (words != null || pinyin != null) {
        val pack = words?.let { path -> File(path).useLines { WordPack.parse(it, path) } }
        val data = pinyin?.let { PinyinData.load(map(it), verify = false) }
        val packed = pack?.words.orEmpty().map { it.entry.text to it.score }
        val all = wordsByUse(packed, data)
        val packScore = HashMap<String, Float>().apply { packed.forEach { (text, s) -> merge(text, s, ::maxOf) } }
        val index = data?.wordIndex
        fun score(text: String): Float {
            val id = index?.find(text) ?: -1
            val model = if (id < 0) Float.NEGATIVE_INFINITY else data!!.model.score(id)
            return maxOf(model, packScore[text] ?: Float.NEGATIVE_INFINITY)
        }
        val added = TableText.addWords(reader, all, ::score) { it in packScore }
        out.appendLine("words: ${added.fresh} new words and ${added.others} others of ${all.size} added, " +
            "${added.dropped} of them left out as rare beside their code's commonest")
    }
    if (reader.strayCodes.isNotEmpty()) {
        out.appendLine("${reader.strayCodes.size} entries use characters outside 键码, e.g. " +
            reader.strayCodes.take(STRAY_EXAMPLES).joinToString(" | "))
    }
    write(output, reader.builder.build(), out)
}

/**
 * Words of Han characters, two or more, from [pack] (text and log10 score) and [data]'s
 * vocabulary, the commonest first: by the model's unigram score, or the pack's for a word the
 * model lacks; then the dictionary's words the model has no score for, as listed.
 */
internal fun wordsByUse(pack: List<Pair<String, Float>>, data: PinyinData?): List<String> {
    fun han(text: String) = text.length >= 2 && text.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    val score = HashMap<String, Float>()
    for ((text, s) in pack) if (han(text)) score.merge(text, s, ::maxOf)
    val unscored = LinkedHashSet<String>()
    if (data != null) {
        for (id in 0 until data.vocabulary.size) {
            val text = data.vocabulary.word(id)
            if (!han(text)) continue
            if (id < data.model.vocabularySize) score.merge(text, data.model.score(id), ::maxOf) else unscored += text
        }
    }
    return score.entries.sortedByDescending { it.value }.map { it.key } + unscored.filter { it !in score }
}

private fun emoji(options: Options, out: Appendable) {
    val input = options.inputs.single()
    val emoji = EmojiAnnotations.emoji(File(options.test!!).readText())
    val annotations = EmojiAnnotations.read(File(input).readText(), emoji)
    val words = EmojiAnnotations.words(annotations, options.lexicon?.let { File(it).readLines() }.orEmpty(), emoji)
    // read as the settings read a word the user adds (Engines.pinyinOf), for the panel's search by pinyin
    val engines = options.pinyin?.let { data -> Engines({ path -> if (path == Engines.PINYIN_DATA) map(data) else throw FileNotFoundException(path) }, null) }
    val pinyin = engines?.pinyinsOf(words.keys).orEmpty()
    // the commoner word first, as the panel's search lists words alike (笑 before 孝); by word without the data
    val sorted = words.keys.sorted()
    val order = engines?.logProbs(sorted)?.let { p -> sorted.indices.sortedByDescending { p[it] }.map(sorted::get) } ?: sorted
    val output = File(options.output!!)
    output.writeText(EmojiData.format(emoji.groups, order.associateWith(words::getValue), EMOJI_HEADER, pinyin))
    val unread = words.keys.count { pinyin[it] == null }
    if (options.pinyin != null && unread > 0) out.appendLine("emoji: $unread words the pinyin data does not read: not found by search")
    out.appendLine("emoji: ${emoji.groups.sumOf { it.second.size }} in the panel, ${annotations.size} annotated, ${words.size} words")
    out.appendLine("wrote $output: ${output.length()} bytes")
}

private val EMOJI_HEADER = """
    emoji.data, ime-dict-tool's emoji: the panel's groups (@), then the Chinese words emoji stand for
    from Unicode's emoji-test.txt and CLDR's annotations (Unicode-3.0, https://www.unicode.org/license.txt)
""".trimIndent()

private fun strokes(output: String, input: String, out: Appendable) {
    val builder = File(input).bufferedReader().use { Strokes.read(it, input) }
    val writer = builder.build()
    out.appendLine("strokes: read $input")
    write(output, writer, out)
}

/**
 * The words of a word pack's [lines] the voice recognizer takes as hotwords, a line each, in the
 * pack's order: [HotwordSpelling.spelled], the rule the app spells the user's words by too.
 */
internal fun hotwords(lines: List<String>): String = lines.asSequence()
    .filterNot { it.startsWith("#") }
    .map { it.substringBefore('\t') }
    .mapNotNull(HotwordSpelling::spelled)
    .distinct()
    .joinToString("") { "$it\n" }

private fun prune(options: Options, out: Appendable) {
    val lm = options.lm!!
    val model = File(lm).bufferedReader().use { ArpaModel.read(it, lm) }
    out.appendLine("model: ${model.size} / ${model.bigrams.size} / ${model.trigrams.size} n-grams")
    val start = model.words.indexOf(EnglishText.START)
    require(start >= 0) { "no ${EnglishText.START} in $lm" }
    val pruned = Pruner(model, options.threshold, start, options.startProb).prune()
    out.appendLine("pruned: ${pruned.size} / ${pruned.bigrams.size} / ${pruned.trigrams.size} n-grams")
    File(options.output!!).bufferedWriter().use { pruned.write(it) }
    out.appendLine("wrote ${options.output}: ${File(options.output).length()} bytes")
}

private fun english(options: Options, out: Appendable) {
    val lm = options.lm!!
    val builder = EnglishDataBuilder()
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
    write(options.output!!, builder.build(), out)
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
        if (count[order] == 0) "-" else "max error ${"%.4f".format(Locale.ROOT, worst[order])}, mean ${"%.5f".format(Locale.ROOT, total[order] / count[order])}"
}

private const val STRAY_EXAMPLES = 5
