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
import org.fcitx.fcitx5.android.engine.data.WordIndex
import org.fcitx.fcitx5.android.engine.data.WordLayers
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.WordPack
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.data.forEachNumberedLine
import org.fcitx.fcitx5.android.engine.table.TableText
import java.io.File
import java.util.Locale
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.system.exitProcess

val USAGE = """
    usage: pinyin -o <out> --lm <lm.arpa> [--remove <remove.tsv>] [--readings <readings.tsv>] <dict>...
                                                          compile a pinyin dictionary and model, from
                                                          libime's text and word packs (.words), each
                                                          pack a layer of its own; the text's words
                                                          corrected by lexicon/'s lists
           table -o <out> <table.txt>                     compile a code table
           mix -o <out.arpa> --lm <lm.arpa> [--weight <w>] [--cutoffs <bigram>,<trigram>] <corpus>...
                                                          mix a model with n-grams counted in chat:
                                                          conversations a JSON array a line (.jsonl.gz),
                                                          or FineWeb-2 shards' chat-like pages (.parquet);
                                                          a word pack's words (.words) join the model's
           check <pinyin data> <lm.arpa>                  compare compiled scores with the model
           words -o <out.tsv> --data <pinyin.data> [--min-count <n>] <shard.parquet>...
                                                          the words the pages use that the data lacks (NewWords)
           pack -o <out.words> --data <pinyin.data> --layer <name> [--min-count <n>] [--min-pmi <x>] [--min-entropy <x>] [--min-surprise <x>] [--only <words.txt>] [--lexicon <add.tsv>] <candidates.tsv>
                                                          a word pack (WordPack) of the candidates that pass,
                                                          or of the words a curated list takes (lexicon/)
           cc -o <out dir> --crawl <CC-MAIN-...> [--from <n>] --files <n> [--base <url>]
                                                          the Chinese pages of a CommonCrawl crawl's WET files,
                                                          a shard of 100 files each (CommonCrawl)
           clean -o <out dir> [--sketch-bits <n>] <shard.parquet>...
                                                          the shards without their boilerplate and spam,
                                                          a shard each, of the same name (PageCleaner); a
                                                          bit more a doubling of the lines past one crawl's
           examples -o <out.tsv> --only <words.txt> [--per-word <n>] <shard.parquet>...
                                                          sentences of the pages that use each word (Examples)
           text -o <out.jsonl.gz> <shard.parquet>...      the chat-like pages as mix takes them, a JSON array
                                                          of a page's lines a line: an evaluation set's source
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
        "pinyin" -> pinyin(options, out)
        "table" -> table(options.output!!, options.inputs.single(), out)
        "mix" -> mix(options, out)
        "check" -> check(options.inputs[0], options.inputs[1], out)
        "words" -> words(options, out)
        "pack" -> pack(options, out)
        "cc" -> options.crawl.let { CommonCrawl.extract(it.name!!, it.from, it.files!!, File(options.output!!), it.base) { line -> out.appendLine(line) } }
        "clean" -> clean(options, out)
        "examples" -> examples(options, out)
        "text" -> text(options, out)
    }
    return true
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
    /** `pack`: a curated list, `word<TAB>reading...` (lexicon/add.tsv): its words and no other, as it reads them. */
    val lexicon: String?,
    /** `clean`: the line sketch's width, so twice the lines collide no more than one crawl's did. */
    val sketchBits: Int,
    /** `pinyin`: lexicon/remove.tsv, words the dictionaries have wrongly, and lexicon/readings.tsv, readings put right. */
    val remove: String?,
    val readings: String?,
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

    /** Whether [command] takes these options. */
    fun fit(command: String?): Boolean = when (command) {
        "pinyin", "mix", "table", "check" -> fitModel(command)
        "words", "pack", "cc", "clean", "examples", "text" -> fitCorpus(command)
        else -> false
    }

    private fun fitModel(command: String): Boolean = when (command) {
        "table" -> output != null && lm == null && inputs.size == 1
        "check" -> output == null && lm == null && inputs.size == 2
        else -> output != null && lm != null && inputs.isNotEmpty()
    }

    private fun fitCorpus(command: String): Boolean = output != null && when (command) {
        "words" -> data != null && inputs.isNotEmpty()
        "pack" -> data != null && layer != null && inputs.size == 1
        "cc" -> crawl.name != null && crawl.files != null && inputs.isEmpty()
        "examples" -> only != null && inputs.isNotEmpty()
        else -> inputs.isNotEmpty()
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

        // every option takes a value
        private val FLAGS = setOf(
            "-o", "--lm", "--weight", "--cutoffs", "--data", "--min-count", "--layer", "--min-pmi", "--min-entropy", "--min-surprise", "--only",
            "--crawl", "--from", "--files", "--base", "--per-word", "--lexicon", "--sketch-bits", "--remove", "--readings",
        )

        /** [flag]'s value, [default] without one, null for a bad one. */
        private fun <T : Any> Map<String, String>.read(flag: String, default: T, value: (String) -> T?): T? =
            this[flag]?.let(value) ?: default.takeIf { flag !in this }

        private fun Map<String, String>.count(flag: String, default: Int, range: IntRange): Int? =
            read(flag, default) { it.toIntOrNull()?.takeIf { n -> n in range } }

        /** @return null for an option with a bad or missing value */
        fun parse(args: List<String>): Options? {
            val values = HashMap<String, String>()
            val inputs = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                if (args[i] in FLAGS) values[args[i]] = args.getOrNull(++i) ?: return null else inputs += args[i]
                i++
            }
            return Options(
                output = values["-o"],
                lm = values["--lm"],
                inputs = inputs,
                weight = values.read("--weight", WEIGHT) { it.toDoubleOrNull()?.takeIf { w -> w in 0.0..1.0 } } ?: return null,
                // a trigram's context must be a bigram kept (Mixer)
                cutoffs = values.read("--cutoffs", CUTOFFS) { v ->
                    v.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 && it[0] in 1..it[1] }?.let { it[0] to it[1] }
                } ?: return null,
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
private fun Options.passes(f: List<String>): Boolean {
    if (f[7] != "0" || f[1].toInt() < minCount || f[6].toDouble() < minSurprise) return false
    val entropy = minOf(f[4].toDouble(), f[5].toDouble())
    return when {
        entropy.isNaN() -> f[3].toDouble() >= minPmi
        f[0].length == 2 -> entropy >= minEntropy
        else -> f[3].toDouble() >= minPmi && entropy >= minEntropy
    }
}

/**
 * Whether a character of [text] is one the model all but never saw: a traditional one (視頻, 圖片)
 * on a page the page filter let through, or a typo, no word for a simplified typist either way.
 */
private fun rare(data: PinyinData, text: String): Boolean =
    text.indices.any { i -> data.wordIndex.find(text, i, i + 1).let { it < 0 || data.model.score(it) < RARE_CHAR } }

/**
 * A curated list's words and readings: `word<TAB>pin'yin[<TAB>...]` a line, `#` comments.
 * @throws SourceException for a line without a reading, a syllable the engine does not know, or
 * a reading of more or fewer syllables than the word has characters
 */
private fun lexicon(file: File): Map<String, String> {
    val words = LinkedHashMap<String, String>()
    file.bufferedReader().use { reader ->
        reader.forEachNumberedLine(file.path) { line, n ->
            if (line.isBlank() || line.startsWith('#')) return@forEachNumberedLine
            val f = line.split('\t')
            val text = f[0].trim()
            val reading = f.getOrNull(1)?.trim().orEmpty()
            val syllables = reading.split('\'')
            if (syllables.size != text.length || syllables.any { Syllables.id(it) < 0 }) {
                throw SourceException(file.path, n, "\"$reading\" is no reading of $text")
            }
            words[text] = reading
        }
    }
    return words
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
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make $dir")
    val shards = options.inputs.map(::File)
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
        han += clean.sumOf { page -> page.text.count { it in '一'..'鿿' } }
        out.appendLine("${shard.name}: ${clean.size} pages kept")
    }
    out.appendLine("clean: $kept of $pages pages kept, $han Han characters")
}

/**
 * The runs of Han characters the pages use that the data's base layer lacks, with what tells a
 * word from a chance run: `text count year pmi left-entropy right-entropy surprise known`, a
 * line each, in no order ([NewWords], [Surprise]). Lines starting `#` are comments.
 */
private fun words(options: Options, out: Appendable) {
    val data = PinyinData.load(map(options.data!!))
    // the base layer's words: the others are a pack's, the kind a pack is for, scored by true counts
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
            w.write("%s\t%d\t%d\t%.3f\t%.3f\t%.3f\t%.3f\t%d\n".format(Locale.ROOT, c.text, c.count, c.year, c.pmi, c.leftEntropy, c.rightEntropy, s, if (c.known) 1 else 0))
            all++
            if (!c.known) unknown++
        }
    }
    out.appendLine("words: $all runs of at least ${options.minCount}, $unknown of them not in the data")
}

/**
 * The candidates of `words` that pass the thresholds and are not plainly phrases or fragments
 * ([Phrases]), as a word pack: each read as the dictionary's words in it are ([Readings]), scored on the
 * model's unigram scale by its count ([CountFit] over the candidates the model knows). One with
 * a character no reading is known for is left out.
 */
private fun pack(options: Options, out: Appendable) {
    val data = PinyinData.load(map(options.data!!))
    val readings = Readings(data)
    val input = File(options.inputs.single())
    // two reads of the candidates rather than all of them in memory: there are millions
    val known = ArrayList<Pair<Long, Float>>()
    // every word of the dictionary, not the model's alone (data.wordIndex): most are not the model's
    val dictionary = WordIndex(data.vocabulary, data.vocabulary.size)
    val phrases = Phrases { text, from, to -> dictionary.find(text, from, to) >= 0 }
    input.forEachRow { f ->
        val id = data.wordIndex.find(f[0], 0, f[0].length)
        // the base layer's alone: a curated word the model has took its score from an earlier fit
        if (id >= 0 && id < data.model.vocabularySize && data.layers.layer(id) == 0) known += f[1].toLong() to data.model.score(id)
        phrases.saw(f[0], f[1].toInt())
    }
    // the slope fitted too, not held at 1 as frequencies would have it: the pages are not the
    // model's kind of text, and the flatter line measured better (dev/TRAINING-PLAN.md 11.7)
    val fit = CountFit.of(known)
    out.appendLine("fit: log10 P = %.3f + %.3f log10(count + 1), over ${known.size} words the model has".format(Locale.ROOT, fit.a, fit.b))
    // a list that says which runs are words (an encyclopedia's titles): what pmi and entropy only
    // guess at, and most of what they let through is not (dev/TRAINING-PLAN.md 11.7b)
    val only = options.only?.let { wordList(File(it)) }
    // what a curator took, as it reads it: no threshold or guess of ours second-guesses that
    val lexicon = options.lexicon?.let { lexicon(File(it)) }
    val unseen = lexicon?.keys?.toMutableSet()
    var words = 0
    var unread = 0
    var rare = 0
    File(options.output!!).bufferedWriter().use { w ->
        w.write("${WordPack.HEADER}\n# layer: ${options.layer}\n")
        input.forEachRow { f ->
            val text = f[0]
            val count = f[1].toInt()
            if (lexicon != null) {
                if (unseen!!.remove(text)) {
                    w.write("%s\t%s\t%.3f\n".format(Locale.ROOT, text, lexicon.getValue(text), minOf(0f, fit.prob(count.toLong()))))
                    words++
                }
                return@forEachRow
            }
            val listed = only == null || text in only
            if (!listed || !options.passes(f) || phrases.isPhrase(text, count)) return@forEachRow
            if (rare(data, text)) {
                rare++
                return@forEachRow
            }
            val pinyin = readings.of(text)
            if (pinyin == null) {
                unread++
                return@forEachRow
            }
            w.write("%s\t%s\t%.3f\n".format(Locale.ROOT, text, pinyin, minOf(0f, fit.prob(count.toLong()))))
            words++
        }
        // taken from elsewhere than the pages (a report of a missing word, a failed evaluation):
        // scored as the least a candidate is counted
        unseen?.forEach { text ->
            w.write("%s\t%s\t%.3f\n".format(Locale.ROOT, text, lexicon.getValue(text), minOf(0f, fit.prob(options.minCount.toLong()))))
            words++
        }
    }
    if (unseen != null) out.appendLine("pack: ${unseen.size} of the list's words not among the candidates, scored as seen ${options.minCount} times")
    out.appendLine("pack: $words words, $rare left out for a character the model hardly has, $unread for want of a reading")
    val size = File(options.output).length()
    if (size > MAX_FETCHED) out.appendLine("pack: ${size shr 20} MB, more than the app fetches from a server (${MAX_FETCHED shr 20} MB): import by hand only")
}

/** Each candidate row of `words`'s output, split; the comment lines skipped. */
private fun File.forEachRow(row: (List<String>) -> Unit) = useLines { lines ->
    lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { row(it.split('\t')) }
}

// log10: a character under this in the model is as good as not in it (the dictionary's rarest are around -6)
private const val RARE_CHAR = -6.5f

// what the app's daily fetch takes at most (HttpRemoteModel.MAX_WORDS)
private const val MAX_FETCHED = 16L shl 20

/**
 * What tells a phrase or a fragment of a word from a word among the candidates of `words`, which
 * pmi and entropy let through as readily (的事情, 一个人, 务员): a function character at either
 * end (a verb's 了/过 excepted: 哭了, 累了 are typed as one), 的 anywhere, or most of its count
 * inside one longer candidate ([saw] every candidate first).
 */
private class Phrases(private val known: (String, Int, Int) -> Boolean) {
    // the most any one candidate a character longer counts, by the candidate it contains
    private val longest = HashMap<String, Int>()

    // by the run a character shorter: how often a word the dictionary has runs into it from the
    // character before (洛阳 into 阳市, 巷子 into 子里), and out of it into the character after.
    // Each such word alone is a fraction of the run (洛阳市, 贵阳市, 沈阳市 ...), together most of it
    private val crossedIn = HashMap<String, Int>()
    private val crossedOut = HashMap<String, Int>()

    fun saw(text: String, count: Int) {
        if (text.length < 3) return
        for (part in listOf(text.dropLast(1), text.drop(1))) longest.merge(part, count, ::maxOf)
        if ((2..text.length).any { end -> known(text, 0, end) }) crossedIn.merge(text.drop(1), count, Int::plus)
        if ((0..text.length - 2).any { start -> known(text, start, text.length) }) crossedOut.merge(text.dropLast(1), count, Int::plus)
    }

    fun isPhrase(text: String, count: Int): Boolean {
        if (INSIDE.any { it in text }) return true
        if (text.first() in STOP || endsAsOne(text)) return true
        val crossed = maxOf(crossedIn[text] ?: 0, crossedOut[text] ?: 0)
        return maxOf(longest[text] ?: 0, crossed) > count * FRAGMENT_SHARE
    }

    private fun endsAsOne(text: String): Boolean {
        val last = text.last()
        if (last !in STOP || last in LAST_IS_FINE) return false
        return !(text.length == 2 && last in VERB_END)
    }

    private companion object {
        const val STOP = "的了是在就都也不和与着过得地吗呢吧啊呀哦嘛我你他她它们这那个把被将让给对从向为以之其此些很太更最又再还才并或而但却如若因所比跟没有要会能可"
        const val VERB_END = "了过"
        // function characters at the start of a phrase that end words too: 碳中和, 保有
        const val LAST_IS_FINE = "和有"
        const val INSIDE = "的"
        const val FRAGMENT_SHARE = 0.5
    }
}

/**
 * The reading of a run of characters, as the dictionary reads its words: the run is cut into the
 * fewest words the dictionary has, each read as the dictionary reads it likeliest. Read character
 * by character instead, 长期服用 came out zhang'qi'fu'yong and 行业标杆 xing'ye'biao'gan: in a
 * pack, words under readings no one types, pushing out what is typed (dev/TRAINING-PLAN.md 12.10).
 * Most readings weigh the same (a weight marks a polyphone's rarer one only), so ties are broken:
 * between two readings of a word, the one more of its characters are likeliest read as alone;
 * between two cuts, the likelier reading weights, then the words the model finds commoner.
 */
private class Readings(private val data: PinyinData) {
    private class Reading(val syllables: IntArray, val weight: Float, val agreement: Int)

    private val best = HashMap<String, Reading>()
    private val alone = HashMap<Char, Pair<Int, Float>>()
    private var longest = 1

    init {
        val d = data.dictionary
        for (i in 0 until d.childCount(d.root)) {
            val node = d.firstChild(d.root) + i
            for (w in 0 until d.wordCount(node)) {
                val word = data.vocabulary.word(d.word(node, w))
                val weight = d.weight(node, w)
                if (word.length == 1 && (alone[word[0]]?.second ?: Float.NEGATIVE_INFINITY) < weight) alone[word[0]] = d.syllable(node) to weight
            }
        }
        val path = IntArray(MAX_WORD)
        fun visit(node: Int, depth: Int) {
            for (w in 0 until d.wordCount(node)) {
                val word = data.vocabulary.word(d.word(node, w))
                // a reading a character, as every word of the dictionary has
                if (word.length == depth) offer(word, path.copyOf(depth), d.weight(node, w))
            }
            if (depth == MAX_WORD) return
            for (i in 0 until d.childCount(node)) {
                val child = d.firstChild(node) + i
                path[depth] = d.syllable(child)
                visit(child, depth + 1)
            }
        }
        visit(d.root, 0)
    }

    private fun offer(word: String, syllables: IntArray, weight: Float) {
        val agreement = word.indices.count { alone[word[it]]?.first == syllables[it] }
        val held = best[word]
        val better = held == null || compareValuesBy(Reading(syllables, weight, agreement), held, { it.weight }, { it.agreement }) > 0
        if (better) {
            best[word] = Reading(syllables, weight, agreement)
            if (word.length > longest) longest = word.length
        }
    }

    private fun commonness(word: String): Float {
        val id = data.wordIndex.find(word)
        return if (id >= 0 && id < data.model.vocabularySize) data.model.score(id) else UNKNOWN
    }

    /** `pin'yin` for [text], or null if a character of it has no reading. */
    fun of(text: String): String? {
        // fewest words to each position, then the most weight, then the commonest words
        val words = IntArray(text.length + 1) { if (it == 0) 0 else Int.MAX_VALUE }
        val weight = FloatArray(text.length + 1)
        val common = FloatArray(text.length + 1)
        val from = IntArray(text.length + 1)
        for (end in 1..text.length) {
            for (start in maxOf(0, end - longest) until end) {
                val part = text.substring(start, end)
                val reading = best[part]
                if (reading == null || words[start] == Int.MAX_VALUE) continue
                val n = words[start] + 1
                val w = weight[start] + reading.weight
                val c = common[start] + commonness(part)
                // fewer words first, then more weight, then commoner ones
                val order = compareValues(words[end], n).takeIf { it != 0 } ?: compareValues(w, weight[end]).takeIf { it != 0 } ?: compareValues(c, common[end])
                if (order > 0) {
                    words[end] = n
                    weight[end] = w
                    common[end] = c
                    from[end] = start
                }
            }
        }
        if (words[text.length] == Int.MAX_VALUE) return null
        val syllables = ArrayList<Int>()
        var end = text.length
        while (end > 0) {
            syllables.addAll(0, best.getValue(text.substring(from[end], end)).syllables.asList())
            end = from[end]
        }
        return syllables.joinToString("'") { Syllables.spelling(it) }
    }

    private companion object {
        // the dictionary's words are seldom longer; a longer one is read in parts
        const val MAX_WORD = 8
        // a word the model has not: rarer than any it has
        const val UNKNOWN = -99f
    }
}

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
    // lexicon/remove.tsv's first field; lexicon/readings.tsv's first two, a line a reading
    val removed = options.remove?.let { wordList(File(it)) }.orEmpty()
    val readings = options.readings?.let { path ->
        File(path).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t') }
                .onEach { require(it.size >= 2) { "$path: expected \"word<TAB>reading...\", got \"${it.joinToString("\t")}\"" } }
                .groupBy({ it[0].trim() }, { it[1].trim() })
        }
    }.orEmpty()
    val reader = PinyinDictReader(builder, removed, readings)
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
        if (count[order] == 0) "-" else "max error ${"%.4f".format(Locale.ROOT, worst[order])}, mean ${"%.5f".format(Locale.ROOT, total[order] / count[order])}"
}

private const val STRAY_EXAMPLES = 5
