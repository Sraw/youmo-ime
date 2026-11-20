/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.glide.GlidePath
import org.fcitx.fcitx5.android.engine.glide.PinyinGlide
import org.fcitx.fcitx5.android.engine.glide.TableGlide
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingDecoder
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.CharInfo
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.stroke.StrokeFilter
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableLanguage
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSlips
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.LearningUse
import org.fcitx.fcitx5.android.engine.user.WordPack
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.Locale
import java.util.Random
import kotlin.system.exitProcess

/** [learningUse]'s options: declared before [COMMANDS], which take them. */
private val USE_OPTIONS = arrayOf("user-counts", "short-guard")

internal val COMMANDS = listOf(
    Command("score", "<set.tsv> <result.tsv> [<baseline-result.tsv>] [--half <half>]", 2..3, "half") { args, out, _ ->
        val (setPath, resultPath) = args.operands
        val baselinePath = args.operands.getOrNull(2)
        score(setPath, resultPath, baselinePath, args.options["half"], out)
    },
    Command(
        "pinyin",
        "<pinyin.data> <set.tsv> <result.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>] [--neighbours on|off] [--typos on|off] [--latin on|off] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--limit <rerank>[,<refine>]] [--beam <n>] [--sentences <n>] [--layers <name>=<log10>,...] [--pack <file.words>] [--merged <dictionary.txt> [--stale]] [--threads <n>] [--taps <hand>:<α>[:<seed>]] [--sp-abbrev off|<extra penalty>] [--history <words.txt>] [--touch <keyboard>:<hand>:<clean|slip>[:<seed>] [--touched <set.tsv>]]",
        3..3, "scheme", "fuzzy", "half", "neighbours", "typos", "latin", "rerank", "refine", "weight", "limit", "beam", "sentences", "layers", "pack", "merged", "stale", "threads", "taps", "sp-abbrev", "history", "touch", "touched",
    ) { args, _, _ ->
        // --touched needs --touch
        if ("touched" in args.options && "touch" !in args.options) return@Command null
        val (dataPath, setPath, resultPath) = args.operands
        runPinyin(dataPath, setPath, resultPath, RunOptions(args.options), Models(args.options))
    },
    Command(
        "predict", "<pinyin.data> <predict.tsv> [--offers <out.tsv>] [--threads <n>] [--rerank <model.safetensors> [--weight <w>]]",
        2..2, "threads", "offers", "rerank", "weight",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val weight = args.options["weight"]?.let { weights(it)?.first } ?: Reranker.WEIGHT
        predict(dataPath, setPath, RunOptions(args.options).threads, args.options["offers"], args.options["rerank"], weight, out)
    },
    Command(
        "english", "<english.data> <sentences.txt> [--keys nine] [--noise <rate>] [--hand <s0.2>] [--max-correction <cost>] [--misses <out.tsv>] [--threads <n>]",
        2..2, "keys", "noise", "hand", "max-correction", "misses", "threads",
    ) { args, out, _ ->
        if (args.options["keys"].let { it != null && it != "nine" }) return@Command null
        val (dataPath, setPath) = args.operands
        val sentences = File(setPath).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val nine = args.options["keys"] == "nine"
        val data = EnglishData.load(mapFile(dataPath))
        val maxCorrection = args.options["max-correction"]?.toFloat() ?: EnglishSession.MAX_CORRECTION
        // taps with a place: each set with the place ignored (`-0`) and read (`-t`), plus one slip a word (`slip1`)
        val hand = args.options["hand"]?.let(TouchSim::hand)
        val runs = if (hand == null) {
            listOf("" to EnglishRun(data, maxCorrection = maxCorrection, nineKeys = nine))
        } else {
            listOf(false, true).map { reads -> (if (reads) "-t" else "-0") to EnglishRun(data, maxCorrection = maxCorrection, nineKeys = nine, hand = hand, readsTouch = reads) }
        }
        val noise = args.options["noise"]?.toDouble() ?: ENGLISH_NOISE
        val threads = RunOptions(args.options).threads
        val misses = ArrayList<String>()
        out.appendLine(if (nine) EnglishRun.Result.NINE_HEADER else EnglishRun.Result.HEADER)
        for ((suffix, run) in runs) {
            // the nine keys: no slip of [noise]; a word not offered is spelled by its letters, pinned by long press
            val sets = listOf("clean" to run.run(sentences, 0.0, ENGLISH_SEED, threads)) +
                (if (nine) emptyList() else listOf("slips" to run.run(sentences, noise, ENGLISH_SEED, threads))) +
                (if (hand == null) emptyList() else listOf("slip1" to run.run(sentences, 0.0, ENGLISH_SEED, threads, oneSlip = true)))
            for ((name, r) in sets) {
                val label = if (nine && hand == null) "nine" else name + suffix
                out.appendLine(if (nine) r.nineRow(label) else r.row(label))
                misses += r.misses.map { "$label\t$it" }
            }
        }
        args.options["misses"]?.let { path -> File(path).printWriter().use { w -> misses.forEach(w::println) } }
        0
    },
    Command(
        "english-glide", "<english.data> <sentences.txt> [--wobble <key widths>] [--shape <w>] [--proximity <w>] [--edges <w>] [--corner <w>] [--turn <cos>] [--near <r>] [--ends <r>] [--misses <out.tsv>] [--threads <n>]",
        2..2, "wobble", "shape", "proximity", "edges", "corner", "turn", "near", "ends", "misses", "threads",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val sentences = File(setPath).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val o = args.options
        val run = GlideRun(EnglishData.load(mapFile(dataPath)), glideWeights(o))
        out.appendLine(GlideRun.Result.HEADER)
        val r = run.run(sentences, o["wobble"]?.toFloat() ?: GLIDE_WOBBLE, ENGLISH_SEED, RunOptions(o).threads)
        out.appendLine(r.row("wobble"))
        if (r.skipped > 0) out.appendLine("${r.skipped} words left out: a key the layout lacks, or all on one key")
        out.appendLine(r.stepsRow())
        o["misses"]?.let { path -> File(path).printWriter().use { w -> r.misses.forEach(w::println) } }
        0
    },
    Command(
        "pinyin-glide", "<pinyin.data> <set.tsv> [--syllables <n; 0, the words>] [--word <letters>] [--shuangpin <scheme, as EngineSettings names it>] [--keys nine] [--table <x.data>] [--wobble <key widths>] [--shape <w>] [--proximity <w>] [--edges <w>] [--corner <w>] [--turn <cos>] [--near <r>] [--ends <r>] [--misses <out.tsv>]",
        2..2, "syllables", "wobble", "shape", "proximity", "edges", "corner", "turn", "near", "ends", "misses", "word", "shuangpin", "keys", "table",
    ) { args, out, _ ->
        if (args.options["keys"].let { it != null && it != "nine" }) return@Command null
        val (dataPath, setPath) = args.operands
        val o = args.options
        o["word"]?.let { word ->
            val layout = if (o["keys"] == "nine") GlideLayout.nineKeys() else GlideLayout.QWERTY
            val path = word.flatMap { layout.centre(it).toList() }.toFloatArray()
            PinyinGlide(loadData(dataPath), glideWeights(o, PinyinGlide.WEIGHTS)).decode(path, layout, 10)
                .forEach { out.appendLine(String.format(Locale.ROOT, "%-16s %-8s %7.2f", it.keys, it.word, it.score)) }
            return@Command 0
        }
        val samples = readSet(setPath).map { it.input to it.expected }
        val data = loadData(dataPath)
        val weights = glideWeights(o, PinyinGlide.WEIGHTS)
        val spelling = o["shuangpin"]?.let { PinyinGlide.Spelling.shuangpin(EngineSettings.SCHEMES[it] ?: throw IllegalArgumentException("no scheme $it")) }
        val layout = if (o["keys"] == "nine") GlideLayout.nineKeys() else GlideLayout.QWERTY
        val table = o["table"]?.let { path ->
            val words = TextWords(data.model, data.wordIndex)
            TableDictionary(CodeTable.load(mapFile(path))).let { it to TableGlide(it, { text -> words.logProb(text) }, weights) }
        }
        val r = PinyinGlideRun(data, weights).run(
            samples, o["syllables"]?.toInt() ?: 0, o["wobble"]?.toFloat() ?: GLIDE_WOBBLE, ENGLISH_SEED,
            spelling ?: PinyinGlide.Spelling.FULL, layout, table,
        )
        out.appendLine(GlideRun.Result.HEADER)
        out.appendLine(r.row("wobble"))
        if (r.skipped > 0) out.appendLine("${r.skipped} words left out: a key the layout lacks, or all on one key")
        if (r.samplesSkipped > 0) out.appendLine("${r.samplesSkipped} samples left out: their syllables are not one a character")
        out.appendLine(r.stepsRow())
        o["misses"]?.let { path -> File(path).printWriter().use { w -> r.misses.forEach(w::println) } }
        0
    },
    Command("table-offers", "<pinyin.data> <set.tsv> <x.data> [--names <names.txt>]", 3..3, "names") { args, out, _ ->
        val (dataPath, setPath, tablePath) = args.operands
        val data = loadData(dataPath)
        val words = TextWords(data.model, data.wordIndex)
        val run = TableOfferRun(TableDictionary(CodeTable.load(mapFile(tablePath))), TableOptions.WUBI) { words.boundaries(it) }
        val names = args.options["names"]?.let { path -> File(path).readLines().map(String::trim).filter(String::isNotEmpty) }.orEmpty()
        val r = run.run(readSet(setPath).map { it.expected }, names)
        fun pct(n: Int, of: Int) = String.format(Locale.ROOT, "%.1f%%", if (of == 0) 0.0 else 100.0 * n / of)
        out.appendLine("offered after a character   ${r.offeredAt} of ${r.positions} (${pct(r.offeredAt, r.positions)}), ${r.words} words")
        out.appendLine("offered at a sentence's end ${r.offeredAtEnd} of ${r.sentences} (${pct(r.offeredAtEnd, r.sentences)})")
        if (names.isNotEmpty()) out.appendLine("names offered whole         ${r.namesOffered} of ${r.names} (${pct(r.namesOffered, r.names)})")
        0
    },
    Command("predict-learn", "<pinyin.data> <set.tsv> [--user-weight <w>] [--continuations on|off]", 2..2, "user-weight", "continuations") { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val userWeight = args.options["user-weight"]?.toFloat() ?: Predictor.USER_WEIGHT
        val continuations = args.options["continuations"] ?: "on"
        val rows = PredictLearning(loadData(dataPath), userWeight, continuations == "on").measure(readSet(setPath))
        rows.forEach { (label, score) -> out.appendLine("%-28s %s".format(label, score)) }
        0
    },
    Command("learn-log-bench", "<dir> [--commits <n>] [--days <n>] [--counts <pinyin.data>]", 1..1, "commits", "days", "counts") { args, out, _ ->
        val dir = File(args.operands[0]).apply { mkdirs() }
        val bench = LearnLogBench(args.options["commits"]?.toInt() ?: LEARN_LOG_COMMITS, args.options["days"]?.toInt() ?: LEARN_LOG_DAYS)
        val lines = args.options["counts"]?.let { bench.counts(loadData(it), dir).lines() } ?: bench.run(dir).lines()
        lines.forEach(out::appendLine)
        0
    },
    Command("history", "<pinyin.data> <words> <history.txt>", 3..3) { args, _, _ ->
        val (dataPath, words, outPath) = args.operands
        // a history for --history and run-on-device.sh's HISTORY: the dictionary's likeliest words
        val n = words.toIntOrNull()?.takeIf { it > 0 } ?: return@Command null
        File(outPath).printWriter().use { w -> TypedHistory.likeliest(loadData(dataPath), n).forEach(w::println) }
        0
    },
    Command("predict-set", "<pinyin.data> <set.tsv> <predict.tsv>", 3..3) { args, _, _ ->
        val (dataPath, setPath, outPath) = args.operands
        File(outPath).printWriter().use { w ->
            PredictLearning(loadData(dataPath), 0f).continuations(readSet(setPath)).forEach { w.println("${it.context}\t${it.next}") }
        }
        0
    },
    Command(
        "sentences", "<pinyin.data> <set.tsv> <out.tsv> [--scheme <scheme>] [--neighbours on|off] [--beam <n>] [--sentences <n>] [--threads <n>]",
        3..3, "scheme", "neighbours", "beam", "sentences", "threads",
    ) { args, _, _ ->
        val (dataPath, setPath, outPath) = args.operands
        sentences(dataPath, setPath, outPath, RunOptions(args.options))
    },
    // voice post-processing on voice-eval's recorded stretches, no recognizer
    Command(
        "voice-post",
        "<seg-set.jsonl>... [--source app|top|tagged] [--tagger <polish-tagger.json>] [--guard on|off] [--misses <out.tsv>] [--texts <out.tsv>]" +
            " [--words <words.tsv> --data <engine-assets dir> [--bonus <added>,<learned>] [--picked <out dir>]]",
        1..Int.MAX_VALUE, "source", "tagger", "guard", "misses", "texts", "words", "data", "bonus", "picked",
    ) { args, out, err ->
        val source = args.options["source"] ?: "app"
        if (source !in setOf("app", "top", "tagged")) {
            err.appendLine("voice-post: --source app, top or tagged, not $source")
            return@Command null
        }
        val tagger = args.options["tagger"]
        if ((source == "tagged") != (tagger != null)) {
            err.appendLine("voice-post: --tagger goes with --source tagged, and only with it")
            return@Command null
        }
        val guard = args.options["guard"] ?: "on"
        if (guard !in setOf("on", "off")) {
            err.appendLine("voice-post: --guard on or off, not $guard")
            return@Command null
        }
        // the guards come from the tagger's labels: without a tagger they would be ignored silently
        if (args.options["guard"] != null && tagger == null) {
            err.appendLine("voice-post: --guard goes with --source tagged, and only with it")
            return@Command null
        }
        val wordsFile = args.options["words"]
        val data = args.options["data"]
        val withWords = args.options["bonus"] != null || args.options["picked"] != null
        if ((wordsFile == null) != (data == null) || (withWords && wordsFile == null)) {
            err.appendLine("voice-post: --words and --data go together, --bonus and --picked with them")
            return@Command null
        }
        val bonus = args.options["bonus"]?.let { b -> b.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        if (args.options["bonus"] != null && bonus == null) {
            err.appendLine("voice-post: --bonus <added>,<learned>, not ${args.options["bonus"]}")
            return@Command null
        }
        try {
            val polish = tagger?.let { VoicePostRun.Polish(VoicePostRun.tags(File(it)), guard == "on") }
            val words = wordsFile?.let { w ->
                val engines = Engines({ path -> mapFile(File(data!!, path).path) }, null)
                VoicePostRun.Words.read(engines, File(w), bonus)
            }
            val picked = args.options["picked"]
            if (words != null && picked != null) {
                args.operands.forEach { VoicePostRun.writePicked(it, words, File(picked)) }
                0
            } else {
                VoicePostRun.run(args.operands, source, args.options["misses"], out, args.options["texts"], polish, words)
            }
        } catch (e: VoicePostRun.BadInput) {
            // a bad tagger file or record: said as a failed run; anything else is the tool's bug and fails loudly
            err.appendLine("voice-post: ${e.message}")
            1
        }
    },
    Command("lm", "<model.safetensors> <context> <text>...", 3..Int.MAX_VALUE) { args, out, _ ->
        val (modelPath, context) = args.operands
        lm(modelPath, context, args.operands.drop(2), out)
    },
    Command("shuangpin", "<scheme> <set.tsv> <shuangpin-set.tsv> [--keys 18|14|nine] [--same <set-kept.tsv>]", 3..3, "keys", "same") { args, out, err ->
        val (scheme, setPath, outPath) = args.operands
        val keys = args.options["keys"]?.let { ShuangpinSet.KEYS[it] ?: return@Command null }
        // no 14 keys under a scheme that types ; (微软): the app offers none
        if (keys == NineKeyLetters.G14 && ShuangpinSet.SCHEMES[scheme]?.let(ImKeyboards::has14) == false) {
            err.appendLine("shuangpin: $scheme types ; and has no 14 keys, as the app offers none")
            return@Command null
        }
        if (scheme in ShuangpinSet.SCHEMES) writeShuangpinSet(scheme, setPath, outPath, out, keys, args.options["same"]) else null
    },
    Command("t9", "<set.tsv> <t9-set.tsv> [--presses once|letters] [--keys nine|14|18] [--same <set-kept.tsv>]", 2..2, "presses", "keys", "same") { args, out, err ->
        val (setPath, outPath) = args.operands
        val presses = T9Set.Presses.of(args.options["presses"] ?: "once")!!
        val keys = T9Set.KEYS[args.options["keys"] ?: "nine"] ?: return@Command null
        if (presses != T9Set.Presses.ONCE && keys != NineKeyLetters.STANDARD) {
            err.appendLine("t9: --presses ${presses.option} is the nine keys' alone, not --keys ${args.options["keys"]}")
            null
        } else writeT9Set(setPath, outPath, presses, keys, out, args.options["same"])
    },
    Command("zhuyin", "<set.tsv> <zhuyin-set.tsv> [--keys nine|full] [--tones <chars.txt>[,<phrases.txt>]]", 2..2, "keys", "tones") { args, out, err ->
        val (setPath, outPath) = args.operands
        val keys = ZhuyinSet.KEYS[args.options["keys"] ?: "full"] ?: run {
            err.appendLine("zhuyin: --keys nine or full")
            return@Command null
        }
        val samples = readSet(setPath)
        val converted = ZhuyinSet.convert(samples, keys, args.options["tones"]?.let(ZhuyinSet.Tones::of))
        val toned = if ("tones" in args.options) ", tones typed" else ""
        writeSet(outPath, "$setPath typed in zhuyin on ${args.options["keys"] ?: "full"} keys by `zhuyin`$toned; samples without one exact reading left out", converted)
        out.appendLine("${converted.size} of ${samples.size} samples typed in zhuyin")
        0
    },
    Command("slips", "<set.tsv> <slip-set.tsv> [--scheme <双拼 scheme>]", 2..2, "scheme") { args, out, _ ->
        val (setPath, outPath) = args.operands
        val scheme = args.options["scheme"]
        if (scheme != null && scheme !in ShuangpinSet.SCHEMES) return@Command null
        writeSlipSet(setPath, outPath, scheme, out)
    },
    Command("tune", "<pinyin.data> <set.tsv> <slip-set.tsv> [--threads <n>]", 3..3, "threads") { args, out, _ ->
        val (dataPath, setPath, slipsPath) = args.operands
        tune(dataPath, setPath, slipsPath, RunOptions(args.options).threads, out)
    },
    Command(
        "ksc",
        "<pinyin.data> <set.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--threads <n>] [--outcomes <out.tsv>]",
        2..2, "scheme", "fuzzy", "half", "rerank", "refine", "weight", "threads", "outcomes",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        ksc(dataPath, setPath, RunOptions(args.options), Models(args.options), args.options["outcomes"], out)
    },
    Command(
        "learn",
        "<pinyin.data> <set.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--neighbours on|off] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--limit <rerank>[,<refine>]] [--pack <file.words>] [--misses <out.tsv>] $USE_USAGE",
        2..2, "scheme", "fuzzy", "neighbours", "rerank", "refine", "weight", "limit", "pack", "misses", *USE_OPTIONS,
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        learn(dataPath, setPath, RunOptions(args.options), Models(args.options), args.options["pack"], learningUse(args.options), args.options["misses"], out)
    },
    Command(
        "learn-ux",
        "<pinyin.data> <script.txt or a directory of them> [--seed <commits>] [--gate] [--rerank <model.safetensors>] [--refine <model.safetensors>] $USE_USAGE",
        2..2, "seed", "gate", "rerank", "refine", *USE_OPTIONS,
    ) { args, out, _ ->
        val (dataPath, scriptPath) = args.operands
        learnUx(dataPath, File(scriptPath), args.options, out)
    },
    Command(
        "strokes", "<pinyin.data> <stroke.data> <set.tsv> [--charinfo <charinfo.data>] [--scheme <scheme>] [--look-ahead <n>] [--first-char] [--rerank <model.safetensors>] [--refine <model.safetensors>]",
        3..3, "charinfo", "scheme", "look-ahead", "first-char", "rerank", "refine",
    ) { args, out, _ ->
        val (dataPath, strokePath, setPath) = args.operands
        val lookAhead = args.options["look-ahead"]?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: return@Command null } ?: StrokeFilter.LOOK_AHEAD
        strokes(dataPath, strokePath, setPath, lookAhead, args.options, out)
    },
    Command("readings", "<pinyin.data> <readings.tsv> [--typed] [--misses <out.tsv>] [--gate]", 2..2, "typed", "misses", "gate") { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val r = ReadingsRun(loadData(dataPath), typed = "typed" in args.options).measure(File(setPath).useLines { ReadingsRun.parse(it) })
        out.appendLine("words: ${r.words}, read right: ${r.right} (${"%.2f".format(Locale.ROOT, r.accuracy)}%), gate ${ReadingsRun.GATE}%")
        args.options["misses"]?.let { path -> File(path).printWriter().use { w -> r.misses.forEach(w::println) } }
        if ("gate" in args.options && r.accuracy < ReadingsRun.GATE) 1 else 0
    },
    Command("chars", "<pinyin.data>", 1..1) { args, out, _ ->
        CharRanks.report(CharRanks(loadData(args.operands[0])).measure(), out)
        0
    },
    Command(
        "handwriting",
        "<pinyin.data> <rows.tsv> [--weights <ink weight>,... (one with --rerank)] [--alternatives <n>] [--limit <rows>] [--rerank <small model.safetensors> [--refine <large model.safetensors>] [--model-weights <w>,...]]",
        2..2, "weights", "alternatives", "limit", "rerank", "refine", "model-weights",
    ) { args, out, _ ->
        val (dataPath, rowsPath) = args.operands
        val inkWeights = args.options["weights"]?.split(',')?.size ?: 1
        if (modelsAlone(args.options, "model-weights" to "rerank") || ("rerank" in args.options && inkWeights > 1)) {
            null
        } else {
            handwriting(dataPath, rowsPath, args.options, out)
        }
    },
    Command(
        "table", "<table.data> <set.tsv> [--preset <preset> [--pinyin <pinyin.data> [--rerank <small.safetensors> [--refine <large.safetensors>]]]]",
        2..2, "preset", "pinyin", "rerank", "refine",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val preset = args.options["preset"] ?: DEFAULT_PRESET
        if (modelsAlone(args.options, "rerank" to "pinyin")) {
            null
        } else {
            table(dataPath, setPath, preset, out)
            // the 重码 after the preceding text, as the app ranks them (TableLanguage)
            args.options["pinyin"]?.let { tableHomographs(dataPath, setPath, preset, it, args.options["rerank"], args.options["refine"], out) }
            0
        }
    },
    Command(
        "table-slips", "<table.data> <set.tsv> [--preset <preset>] [--hand <σ, TouchSim's: s0.2>] [--seed <n>] [--half tune|held-out] [--floor <log10>] [--topping-floor <log10>] [--words <pinyin.data>] [--plan whole|prefix] [--misses <out.tsv>] [--touched <out.tsv>]",
        2..2, "preset", "hand", "seed", "half", "floor", "topping-floor", "words", "plan", "misses", "touched",
    ) { args, out, err ->
        val (dataPath, setPath) = args.operands
        val o = args.options
        tableSlips(dataPath, setPath, o["preset"] ?: DEFAULT_PRESET, o, out, err)
    },
)

val USAGE = "usage: " + COMMANDS.joinToString("\n       ") { "${it.name} ${it.usage}" } + """
schemes: ${(ShuangpinSet.SCHEMES.keys + ShuangpinSet.GROUPED.keys + T9Set.SCHEMES + ZhuyinSet.SCHEMES.keys + NO_LATIN).joinToString(" ")}
pairs: ${Fuzzy.entries.joinToString(" ") { it.name.lowercase() }}
halves: ${Halves.NAMES.joinToString(" ")}
presets: ${TableRun.PRESETS.keys.joinToString(" ")}
presets for --pinyin, which order by use (the app's 常用的字词排前面): ${TableRun.PRESETS.filterValues { it.orderByUse }.keys.joinToString(" ")}"""

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

/** A command: what follows its [name] in the usage, its argument count and [options], and its action (exit code, null for a usage error). */
internal class Command(
    val name: String,
    val usage: String,
    val arity: IntRange,
    vararg options: String,
    val run: (Arguments, Appendable, Appendable) -> Int?,
) {
    val options = options.toSet()
}

private val FLAGS = setOf("gate", "stale", "jvm", "first-char", "typed")

/** Positional arguments and `--name value` options; a [FLAGS] one has an empty value. */
internal class Arguments(args: Array<String>) {
    val positional = ArrayList<String>()
    val options = HashMap<String, String>()
    var valid = true
        private set

    init {
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            if (!arg.startsWith("--")) {
                positional += arg
            } else if (arg.removePrefix("--") in FLAGS) {
                if (options.put(arg.removePrefix("--"), "") != null) valid = false
            } else if (i == args.size || options.put(arg.removePrefix("--"), args[i++]) != null) {
                valid = false // no value, or given twice
            }
        }
    }

    val operands: List<String> get() = positional.drop(1)

    /** Whether these are [command]'s: its name, its argument count, and only its options. */
    fun fit(command: Command) =
        valid && positional.firstOrNull() == command.name && operands.size in command.arity && options.keys.all { it in command.options }
}

fun runCli(args: Array<String>, out: Appendable, err: Appendable): Int {
    val arguments = Arguments(args)
    val command = COMMANDS.find { arguments.fit(it) }
    if (command == null || !optionsValid(arguments.options)) return usage(err)
    return command.run(arguments, out, err) ?: usage(err)
}

/** How often a key slips in `english`'s second run, and the slips' seed. */
private const val ENGLISH_NOISE = 0.04
private const val ENGLISH_SEED = 20261011L

private const val GLIDE_WOBBLE = 0.3f

/** A heavy user's year of commits: what learn-log-bench writes. */
private const val LEARN_LOG_COMMITS = 200_000
private const val LEARN_LOG_DAYS = 365

private const val NANOS_PER_MILLI = 1_000_000

private const val NO_LATIN = "no-latin"

private val TAPPED = setOf(NineKeyLetters.G14, NineKeyLetters.G18)

private const val DEFAULT_PRESET = "plain"

private fun optionsValid(options: Map<String, String>): Boolean {
    fun valid(name: String, test: (String) -> Boolean) = options[name]?.let(test) ?: true
    val scheme = options["scheme"]
    return listOf(
        valid("half") { it in Halves.NAMES },
        valid("presses") { T9Set.Presses.of(it) != null },
        valid("scheme") { it == NO_LATIN || it in ShuangpinSet.SCHEMES || it in ShuangpinSet.GROUPED || it in T9Set.SCHEMES || it in ZhuyinSet.SCHEMES },
        valid("fuzzy") { fuzzyPairs(it) != null },
        valid("preset") { it in TableRun.PRESETS },
        // a table that does not order by use keeps its order whatever the text before
        valid("pinyin") { TableRun.PRESETS[options["preset"] ?: DEFAULT_PRESET]?.orderByUse == true },
        valid("weight") { weights(it) != null },
        valid("limit") { limits(it) != null },
        valid("user-weight") { weight(it) != null },
        valid("max-correction") { weight(it) != null },
        valid("noise") { n -> n.toDoubleOrNull()?.let { it in 0.0..1.0 } == true },
        // the union; each command checks its own
        valid("keys") { it in T9Set.KEYS || it in ShuangpinSet.KEYS || it in ZhuyinSet.KEYS },
        valid("user-counts") { it in setOf("full", "none", "multi") },
        valid("short-guard") { it in ON_OFF },
        valid("continuations") { it in ON_OFF },
        valid("taps") { runCatching { Taps.parse(it) }.isSuccess && scheme != null && scheme in T9Set.SCHEMES && T9Set.lettersOf(scheme) in TAPPED },
        valid("seed") { (it.toIntOrNull() ?: -1) >= 0 },
        // table-slips' floors: one unread would run at the app's yet be reported as asked
        listOf("floor", "topping-floor").all { name -> valid(name) { it.toFloatOrNull()?.isFinite() == true } },
        valid("stale") { "merged" in options },
        listOf("threads", "beam", "sentences").all { name -> valid(name) { (it.toIntOrNull() ?: 0) > 0 } },
        // a key read as its neighbour
        valid("neighbours") { it in ON_OFF && readsNeighbours(scheme) },
        valid("touch") { "taps" !in options && touchValid(it) },
        valid("hand") { runCatching { TouchSim.hand(it) }.isSuccess },
        valid("typos") { it in ON_OFF && readsTypos(scheme) },
        // 中英混输 on keys; the full keyboard's off is the scheme no-latin
        valid("latin") { it in ON_OFF && scheme in T9Set.SCHEMES },
        // 双拼's 简拼 in the middle: off, or a penalty other than the app's
        valid("sp-abbrev") { readsAbbrev(it, scheme) },
    ).all { it }
}

/** Whether `--sp-abbrev` [spec] is off or a finite penalty ≤ 0, under a 双拼 scheme (full or grouped keyboard). */
private fun readsAbbrev(spec: String, scheme: String?) = (spec == "off" || spec.toFloatOrNull()?.let { it.isFinite() && it <= 0f } == true) && (scheme in ShuangpinSet.SCHEMES || scheme in ShuangpinSet.GROUPED)

/** Whether [scheme]'s segmenter reads a key as its neighbour: every one, 双拼's groups and zhuyin's too. */
private fun readsNeighbours(scheme: String?) = scheme == null || scheme == NO_LATIN || scheme in ShuangpinSet.SCHEMES || scheme in T9Set.SCHEMES ||
    scheme in ShuangpinSet.GROUPED || scheme in ZhuyinSet.SCHEMES

private fun touchValid(spec: String) = spec.split(':').let { p ->
    p.size in 3..4 && p[2] in setOf("clean", "slip") && (p.size == 3 || p[3].toLongOrNull() != null) &&
        runCatching { TouchSim.board(p[0]); TouchSim.hand(p[1]) }.isSuccess
}

private fun readsTypos(scheme: String?) = scheme == null || scheme == NO_LATIN || scheme in ShuangpinSet.SCHEMES

private fun usage(err: Appendable): Int {
    err.appendLine(USAGE)
    return 2
}

private fun fuzzyPairs(names: String): Set<Fuzzy>? {
    if (names == "all") return Fuzzy.entries.toSet()
    val byName = Fuzzy.entries.associateBy { it.name.lowercase() }
    return names.split(',').map { byName[it] ?: return null }.toSet()
}

private fun glideWeights(o: Map<String, String>, d: GlidePath.Weights = GlidePath.Weights()): GlidePath.Weights {
    return GlidePath.Weights(
        near = o["near"]?.toFloat() ?: d.near, ends = o["ends"]?.toFloat() ?: d.ends,
        shape = o["shape"]?.toFloat() ?: d.shape, proximity = o["proximity"]?.toFloat() ?: d.proximity,
        edges = o["edges"]?.toFloat() ?: d.edges,
        corner = o["corner"]?.toFloat() ?: d.corner, turn = o["turn"]?.toFloat() ?: d.turn,
    )
}

private fun readSet(path: String) = File(path).useLines { EvalSet.parse(it) }

private fun mapFile(path: String) = RandomAccessFile(path, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

private fun loadData(path: String): PinyinData = PinyinData.load(mapFile(path))

private fun weight(spec: String): Float? = spec.toFloatOrNull()?.takeIf { it >= 0 && it.isFinite() }

/** `<rerank>[,<refine>]`: the sentence models' weights against the decoder; refine defaults to rerank. */
private fun weights(spec: String): Pair<Float, Float>? = pairOf(spec, ::weight)

/** `<rerank>[,<refine>]`: how many readings each sentence model weighs; refine defaults to rerank. */
private fun limits(spec: String): Pair<Int, Int>? = pairOf(spec) { it.toIntOrNull()?.takeIf { n -> n > 0 } }

private fun <T : Any> pairOf(spec: String, part: (String) -> T?): Pair<T, T>? {
    val parts = spec.split(',').map { part(it) ?: return null }
    return when (parts.size) {
        1 -> parts[0] to parts[0]
        2 -> parts[0] to parts[1]
        else -> null
    }
}

/** The options of the commands that run the engine over a set, checked already by optionsValid. */
private class RunOptions(options: Map<String, String>) {
    private val scheme = options["scheme"]
    private val fuzzy = options["fuzzy"]?.let(::fuzzyPairs).orEmpty()
    private val neighbours = options["neighbours"] == "on"
    private val typos = options["typos"] != "off"
    private val latin = options["latin"] != "off"
    val half = options["half"]
    val beam = options["beam"]?.toInt() ?: PinyinDecoder.DEFAULT_BEAM
    val sentences = options["sentences"]?.toInt() ?: PinyinDecoder.DEFAULT_SENTENCES
    val threads = options["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val layers = options["layers"]
    val pack = options["pack"]
    val merged = options["merged"]
    val stale = "stale" in options

    /** A file of [TypedHistory]'s lines typed before the run: 双拼 reads a key alone only for those. */
    val history = options["history"]

    /** 双拼 reads a key as an initial alone in the middle too (ShuangpinSegmenter), unless off. */
    val abbreviations = options["sp-abbrev"] != "off"
    val abbrevPenalty = options["sp-abbrev"]?.toFloatOrNull() ?: Penalties().abbrev

    /** The 14 or 18 keys tapped by a simulated hand ([Taps]). */
    val taps = options["taps"]?.let(Taps::parse)?.also {
        require(scheme != null && scheme in T9Set.SCHEMES && T9Set.lettersOf(scheme) in TAPPED) { "--taps types the 14 or the 18 keys: such a --scheme, not $scheme" }
    }

    val tapKeys: NineKeyLetters get() = T9Set.lettersOf(scheme!!)

    /** Taps with a place ([TouchSim]): each key where a simulated hand tapped it; read where [neighbours] is on. */
    val touch = options["touch"]?.also { require(taps == null) { "--touch or --taps, not both" } }

    /** Where the set as [touch] tapped it goes ([EvalSet.line]). */
    val touched = options["touched"]

    /** Each syllable spelt out in the preedit, as the app shows 双拼 and the nine keys. */
    val spell = scheme != null && scheme != NO_LATIN

    /** [touch]'s samples with each tap's place across its key where the app sends one (the 14 and 18 keys). */
    fun placed(samples: List<Sample>): List<Sample> = scheme?.takeIf { it in T9Set.SCHEMES }?.let { TouchSim.placed(samples, T9Set.lettersOf(it)) } ?: samples

    /** Full pinyin reads [data]'s Latin words as the app does, on every keyboard, unless the scheme is [NO_LATIN] or `--latin off`. */
    fun segmenter(data: PinyinData): Segmenter = when (scheme) {
        null -> PinyinSegmenter(fuzzy, typos, neighbours = neighbours, latin = LatinWords.of(data.dictionary))
        NO_LATIN -> PinyinSegmenter(fuzzy, typos, neighbours = neighbours)
        in ZhuyinSet.SCHEMES -> T9Segmenter(fuzzy, abbreviations = false, letters = ZhuyinSet.SCHEMES.getValue(scheme), toneAllows = data.tones::allows, neighbours = neighbours)
        in T9Set.SCHEMES -> T9Segmenter(
            fuzzy, abbreviations = T9Set.abbreviates(scheme), letters = T9Set.lettersOf(scheme), neighbours = neighbours,
            latin = if (latin) LatinWords.of(data.dictionary) else null,
        )
        in ShuangpinSet.GROUPED -> ShuangpinSet.GROUPED.getValue(scheme).let { (s, letters) -> GroupedShuangpinSegmenter(s, letters, fuzzy, neighbours = neighbours, abbreviations = abbreviations) }
        else -> ShuangpinSegmenter(ShuangpinSet.SCHEMES.getValue(scheme), fuzzy, typos, abbreviations = abbreviations, neighbours = neighbours)
    }
}

/** The sentence models (at each key, and at a pause): loaded once and shared; each reranker keeps its own state. */
private class Models(options: Map<String, String>) {
    private val rerankPath = options["rerank"]
    private val refinePath = options["refine"]
    private val weights = options["weight"]?.let(::weights) ?: (Reranker.WEIGHT to Reranker.WEIGHT)
    private val limits = options["limit"]?.let(::limits) ?: (Reranker.LIMIT to Reranker.REFINE_LIMIT)

    // floats, as the app has the smaller: the same scores, faster
    private val rerank by lazy { rerankPath?.let { SentenceModel.load(mapFile(it), unpack = true) } }
    private val refine by lazy { refinePath?.let { SentenceModel.load(mapFile(it), unpack = true) } }

    val reranker: (() -> Reranker)? get() = rerank?.let { model -> { Reranker(model, weights.first, limit = limits.first) } }

    val refiner: (() -> SentenceRefiner)? get() = refine?.let { model -> { Reranker(model, weights.second, limit = limits.second) } }
}

private fun predict(dataPath: String, setPath: String, threads: Int, offersPath: String?, rerank: String?, weight: Float, out: Appendable): Int {
    val samples = File(setPath).useLines { PredictRun.parse(it) }
    val model = rerank?.let { SentenceModel.load(mapFile(it), unpack = true) }
    val offers = dealt(samples, threads, { PredictRun(loadData(dataPath), model, weight) }) { hand -> hand.map { offers(it.context) } }
    offersPath?.let { path ->
        File(path).printWriter().use { w -> samples.zip(offers).forEach { (s, o) -> w.println("${s.context}\t${s.next}\t${o.joinToString(" ")}") } }
    }
    out.appendLine(PredictRun.score(samples, offers).toString())
    return 0
}

private fun score(setPath: String, resultPath: String, baselinePath: String?, half: String?, out: Appendable): Int {
    val samples = readSet(setPath)
    fun resultsOf(path: String) = File(path).useLines { RunResultFormat.parse(it).toList() }
    val results = resultsOf(resultPath)
    val baseline = baselinePath?.let(::resultsOf)
    out.appendLine(Report.table(Metrics.score(samples, results, half), baseline?.let { Metrics.score(samples, it, half) }))
    // which samples changed: a point's delta on a few hundred samples is as often chance as not
    if (baseline != null) out.appendLine().appendLine(Report.flips(Metrics.flips(samples, results, baseline, half)))
    return 0
}

private fun runPinyin(dataPath: String, setPath: String, resultPath: String, options: RunOptions, models: Models): Int {
    val mergedPath = options.merged
    val pack = options.pack?.let { path -> File(path).useLines { WordPack.parse(it, path) } }
    val work = mergedPath?.let { Files.createTempDirectory("ime-eval-merged").toFile() }
    try {
        val merged = mergedPath?.let { compileMerged(it, loadData(dataPath), File(checkNotNull(work), "merged.ymd")) }
        val typed = options.history?.let { File(it).readLines() }
        // a data file each: its buffers are not to be shared
        val set = Halves.select(readSet(setPath), options.half)
        val selected = options.taps?.let { spec ->
            Taps.convert(set, spec, options.tapKeys).also { require(it.isNotEmpty()) { "--taps: no sample of $setPath has one exact reading (a set already of codes?)" } }
        } ?: options.touch?.let { spec ->
            TouchSim.convert(set, spec).also { require(it.isNotEmpty()) { "--touch: no sample of $setPath has a key on that keyboard" } }
                .let(options::placed)
        } ?: set
        // the set as tapped, for the device runner (EngineEvalRunner) to type the same
        options.touched?.let { path -> File(path).printWriter().use { w -> selected.forEach { w.println(EvalSet.line(it)) } } }
        val results = dealt(selected, options.threads, {
            val data = loadData(dataPath)
            PinyinRun(
                data, options.segmenter(data), penalties = Penalties(tap = options.taps?.let { -it.alpha } ?: Penalties().tap, abbrev = options.abbrevPenalty),
                beam = options.beam, sentenceCount = options.sentences, layers = options.layers, pack = pack,
                reranker = models.reranker, refiner = models.refiner,
                merged = merged?.let { DictionaryFile.open(JvmFile(it), verify = false) }, mergedStale = options.stale,
                typed = typed,
            )
        }) { hand -> run(hand) }
        File(resultPath).printWriter().use { out -> results.forEach { out.println(RunResultFormat.format(it)) } }
    } finally {
        work?.deleteRecursively()
    }
    return 0
}

/** [source], a dictionary's text, compiled as the app compiles it against [data]'s dictionary; its size told on stderr. */
private fun compileMerged(source: String, data: PinyinData, dest: File): File {
    val started = System.nanoTime()
    val result = File(source).useLines { lines -> DictionaryCompiler.compile(listOf(lines), dest, inBase = DictionaryCompiler.inBase(data)) }
    System.err.println(
        "merged: ${result.words} words, ${result.inBase} the base's, ${result.unread} unread; " +
            "${dest.length()} bytes, compiled in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms",
    )
    // checked once here; each thread opens it unchecked
    DictionaryFile.open(JvmFile(dest))
    return dest
}

/** Each sample's whole-input readings with their log10 scores, one line per sample in the set's order, for training a sentence model. */
private fun sentences(dataPath: String, setPath: String, outPath: String, options: RunOptions): Int {
    val lines = dealt(readSet(setPath), options.threads, { loadData(dataPath).let { PinyinRun(it, options.segmenter(it), beam = options.beam, sentenceCount = options.sentences) } }) { hand ->
        hand.map { sample ->
            (listOf(sample.input) + sentences(sample.input, sample.context).flatMap { (text, score) -> listOf(text, score.toString()) })
                .joinToString("\t")
        }
    }
    File(outPath).printWriter().use { out -> lines.forEach(out::println) }
    return 0
}

private fun lm(modelPath: String, context: String, texts: List<String>, out: Appendable): Int {
    // floats, as the other commands load it: faster than the JVM kernel per weight of the mapped file
    val model = SentenceModel.load(mapFile(modelPath), unpack = true)
    model.Scorer().score(context, texts).forEachIndexed { i, score ->
        out.appendLine(String.format(Locale.ROOT, "%8.3f %6.3f %s", score, score / texts[i].length, texts[i]))
    }
    return 0
}

private fun writeSet(outPath: String, header: String, samples: List<Sample>) {
    File(outPath).printWriter().use { w ->
        w.println("# SPDX-License-Identifier: LGPL-2.1-or-later")
        w.println("# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors")
        w.println("# $header")
        // the context too: without it the models would read nothing before the input
        samples.forEach { w.println(listOf(it.input, it.expected, it.tag, it.context).joinToString("\t").trimEnd('\t')) }
    }
}

private fun keysName(keys: NineKeyLetters): String = when (keys) {
    NineKeyLetters.G18 -> "18 keys"
    NineKeyLetters.G14 -> "14 keys"
    else -> "nine keys"
}

// [same]: the kept samples, for full pinyin to be scored on the same ones (only single-exact-reading samples are typed)
private fun writeT9Set(setPath: String, outPath: String, presses: T9Set.Presses, keys: NineKeyLetters, out: Appendable, same: String? = null): Int {
    val samples = readSet(setPath)
    val converted = T9Set.convert(samples, presses, keys)
    same?.let { path -> writeSet(path, "$setPath: the samples `t9` typed, as they were", samples.filter { T9Set.convert(listOf(it), presses, keys).isNotEmpty() }) }
    val on = "the ${keysName(keys)}"
    writeSet(outPath, "$setPath typed on $on by `t9`, keys pressed ${presses.option}; samples without one exact reading left out", converted)
    out.appendLine("${converted.size} of ${samples.size} samples typed on $on")
    return 0
}

private fun strokes(dataPath: String, strokePath: String, setPath: String, lookAhead: Int, o: Map<String, String>, out: Appendable): Int {
    val data = loadData(dataPath)
    val strokes = Strokes(CodeTable.load(mapFile(strokePath))) { c -> data.wordIndex.find(c).let { if (it < 0) Float.NEGATIVE_INFINITY else data.model.score(it) } }
    // the sentence models as the app's 档位 has them: without, a refine has nothing to weigh
    val models = Models(o)
    val session = PinyinSession(data, RunOptions(o).segmenter(data), prediction = false, reranker = models.reranker?.invoke(), refiner = models.refiner?.invoke())
    val set = readSet(setPath)
    val samples = if ("first-char" in o) firstChars(set, o["scheme"]) else set
    // the mainland's orders as the app has them (StrokeFilter); without, rime-stroke's alone
    val charInfo = o["charinfo"]?.let { CharInfo.load(mapFile(it), verify = false) } ?: CharInfo.EMPTY
    StrokeRun(session, strokes, lookAhead, charInfo).run(samples).rows().forEach(out::appendLine)
    return 0
}

/** `strokes --first-char`: each sample's first character alone, typed by its exact reading in [scheme]'s keys. */
internal fun firstChars(set: List<Sample>, scheme: String?): List<Sample> = set.mapNotNull { s ->
    val syllable = ExactReading.of(s)?.firstOrNull()?.syllable ?: return@mapNotNull null
    val one = Sample(Syllables.spelling(syllable), String(Character.toChars(s.expected.codePointAt(0))), s.tag)
    when (scheme) {
        null, NO_LATIN -> one
        in T9Set.SCHEMES -> T9Set.convert(listOf(one), keys = T9Set.lettersOf(scheme)).firstOrNull()
        in ShuangpinSet.GROUPED -> ShuangpinSet.GROUPED.getValue(scheme).let { (sp, keys) -> ShuangpinSet.convert(listOf(one), sp, keys) }.firstOrNull()
        else -> ShuangpinSet.convert(listOf(one), ShuangpinSet.SCHEMES.getValue(scheme)).firstOrNull()
    }
}

// [same]: the kept samples, for the full keyboard and full pinyin to be scored on the same ones
private fun writeShuangpinSet(scheme: String, setPath: String, outPath: String, out: Appendable, keys: NineKeyLetters? = null, same: String? = null): Int {
    val samples = readSet(setPath)
    val s = ShuangpinSet.SCHEMES.getValue(scheme)
    val converted = ShuangpinSet.convert(samples, s, keys)
    same?.let { path -> writeSet(path, "$setPath: the samples `shuangpin` typed, as they were", samples.filter { ShuangpinSet.convert(listOf(it), s, keys).isNotEmpty() }) }
    val on = keys?.let { " on the ${keysName(it)}" }.orEmpty()
    writeSet(outPath, "$setPath typed in $scheme 双拼$on by `shuangpin`; samples without one exact reading left out", converted)
    out.appendLine("${converted.size} of ${samples.size} samples typed in $scheme$on")
    return 0
}

private fun writeSlipSet(setPath: String, outPath: String, scheme: String?, out: Appendable): Int {
    val samples = readSet(setPath)
    val slips = if (scheme == null) SlipSet.generate(samples) else SlipSet.generate(samples, ShuangpinSet.SCHEMES.getValue(scheme))
    val typed = if (scheme == null) "" else " typed in 双拼 $scheme"
    writeSet(outPath, "$setPath$typed with one syllable mixed up or slipped, by `slips`; the tag says how", slips)
    out.appendLine("${slips.size} samples")
    return 0
}

private fun tune(dataPath: String, setPath: String, slipsPath: String, threads: Int, out: Appendable): Int {
    val tuning = Tuning({ loadData(dataPath) }, readSet(setPath), readSet(slipsPath), threads)
    // the label column as wide as the longest a slip's makes, so the rates sit under the header
    fun row(label: String, point: Tuning.Point) = out.appendLine(
        String.format(Locale.ROOT, "%-30s", label) +
            (point.rates + point.mean).joinToString("") { rate ->
                if (rate == null) String.format(Locale.ROOT, "%17s", "-") else String.format(Locale.ROOT, "%16.1f%%", rate * 100)
            },
    )
    fun header(label: String, point: Tuning.Point, columns: List<String> = Tuning.COLUMNS) = out.appendLine(
        String.format(Locale.ROOT, "%-30s", label) +
            (columns.zip(point.sizes) { c, n -> "$c/$n" } + "mean").joinToString("") { String.format(Locale.ROOT, "%17s", it) },
    )
    val default = tuning.measure(Penalties(), Halves.TUNE)
    val points = tuning.search()
    header("fuzzy typo (tune)", default)
    points.forEach { row("${it.penalties.fuzzy} ${it.penalties.typo}", it) }
    val chosen = tuning.choose(points, default)
    val heldOut = tuning.measure(Penalties(), Halves.HELD_OUT)
    header("held-out", heldOut)
    row("default", heldOut)
    row("chosen ${chosen.penalties.fuzzy} ${chosen.penalties.typo}", tuning.measure(chosen.penalties, Halves.HELD_OUT))

    val off = tuning.measureKeys(Penalties(), Halves.TUNE, read = false)
    header("neighbour (tune)", off, Tuning.KEY_COLUMNS)
    row("off", off)
    // the neighbour penalty is on top of a slip's own place cost; each row says both
    val fixed = tuning.slipFixed
    fun slip(p: Penalties) = "${p.neighbour} (${fixed + p.neighbour} a slip)"
    val keyPoints = tuning.searchKeys()
    keyPoints.forEach { row(slip(it.penalties), it) }
    val keyChoice = tuning.chooseKeys(keyPoints, off)
    val offHeldOut = tuning.measureKeys(Penalties(), Halves.HELD_OUT, read = false)
    header("held-out", offHeldOut, Tuning.KEY_COLUMNS)
    row("off", offHeldOut)
    row("default ${slip(Penalties())}", tuning.measureKeys(Penalties(), Halves.HELD_OUT, read = true))
    if (keyChoice == null) {
        out.appendLine("chosen: none keeps clean input")
    } else {
        row("chosen ${slip(keyChoice.penalties)}", tuning.measureKeys(keyChoice.penalties, Halves.HELD_OUT, read = true))
    }
    return 0
}

private fun ksc(dataPath: String, setPath: String, options: RunOptions, models: Models, outcomesPath: String?, out: Appendable): Int {
    val outcomes = dealt(Halves.select(readSet(setPath), options.half), options.threads, {
        val data = loadData(dataPath)
        Typist(data, options.segmenter(data), options.spell, models.reranker, models.refiner)
    }) { hand -> hand.map { type(it) } }
    out.appendLine(KeystrokeRun.report(outcomes))
    // a line a sample, for two runs to be compared on the samples both reached
    outcomesPath?.let { path -> File(path).printWriter().use { w -> outcomes.forEach { w.println(KeystrokeRun.line(it)) } } }
    return 0
}

/** Types one thread's samples through one session, as the app keeps one; the rerankers are new per sample, as one keeps state. */
internal class Typist(
    data: PinyinData,
    segmenter: Segmenter,
    spell: Boolean = false,
    private val reranker: (() -> SentencePicker)? = null,
    private val refiner: (() -> SentenceRefiner)? = null,
) {
    private lateinit var sampleReranker: SentencePicker
    private lateinit var sampleRefiner: SentenceRefiner

    private val forwardingRefiner = object : SentenceRefiner {
        override val slices: Int get() = sampleRefiner.slices

        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? =
            sampleRefiner.refine(context, readings, scores, budget)
    }

    // no prediction: KeystrokeRun reads none
    private val session = PinyinSession(
        data, segmenter, spell = spell, prediction = false,
        refiner = if (refiner == null) null else forwardingRefiner,
        reranker = if (reranker == null) null else SentencePicker { context, readings, scores -> sampleReranker.pick(context, readings, scores) },
    )
    private val keys = KeystrokeRun(session)

    fun type(sample: Sample): KeystrokeRun.Outcome {
        reranker?.let { sampleReranker = it() }
        refiner?.let { sampleRefiner = it() }
        session.apply(Action.Reset)
        return keys.type(sample)
    }
}

private fun learn(dataPath: String, setPath: String, options: RunOptions, models: Models, packPath: String?, use: LearningUse, missesPath: String?, out: Appendable): Int {
    val data = loadData(dataPath)
    val pack = packPath?.let { path -> File(path).useLines { WordPack.parse(it, path) } }
    val rows = Learning(data, options.segmenter(data), models.reranker, models.refiner, pack, use).measure(readSet(setPath))
    out.appendLine(KeystrokeRun.HEADER)
    rows.forEach { (label, outcomes) -> out.appendLine(KeystrokeRun.row(label, outcomes)) }
    // each row's samples not first once typed: the row, the keys, what was meant
    missesPath?.let { path ->
        File(path).printWriter().use { w ->
            for ((label, outcomes) in rows) outcomes.filterNot { it.first }.forEach { w.println("$label\t${it.sample.input}\t${it.sample.expected}") }
        }
    }
    return 0
}

/** `--user-counts` and `--short-guard`: how learning reaches the scores; the app's where not given. */
private fun learningUse(o: Map<String, String>) = LearningUse(
    counts = o["user-counts"]?.let { LearningUse.Counts.valueOf(it.uppercase(Locale.ROOT)) } ?: LearningUse.DEFAULT.counts,
    shortGuard = o["short-guard"] != "off",
)

/** The [LearnUx] scripts at [scripts]; `--gate` exits 1 where an expectation failed, `--seed <n>` commits samples first. */
private fun learnUx(dataPath: String, scripts: File, o: Map<String, String>, out: Appendable): Int {
    val files = if (scripts.isDirectory) scripts.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name } else listOf(scripts)
    val scenarios = files.flatMap { LearnUxScript.parse(it.name, it.readLines()) }
    val seed = o["seed"]?.toInt()?.let { n ->
        val sets = (if (scripts.isDirectory) scripts else scripts.absoluteFile.parentFile).absoluteFile.parentFile
        SEED_SETS.flatMap { readSet(File(sets, "$it.tsv").path) }.shuffled(Random(SEED_SHUFFLE)).take(n)
    }.orEmpty()
    val models = Models(o)
    val results = LearnUx(loadData(dataPath), models.reranker, models.refiner, learningUse(o), seed).run(scenarios)
    out.appendLine(LearnUx.report(results))
    val checked = results.any { it.status != LearnUx.Status.NOTE }
    if (!checked) out.appendLine("no expectation checked in $scripts")
    return if ("gate" in o && (!checked || results.any { it.status == LearnUx.Status.FAIL })) 1 else 0
}

private val SEED_SETS = listOf("pinyin-web", "pinyin-chat", "pinyin-dialog", "pinyin-mixed", "pinyin-collide", "pinyin-slips")
private const val SEED_SHUFFLE = 7L

private const val USE_USAGE = "[--user-counts full|none|multi] [--short-guard on|off]"

/** Whether [options] give a model option without its partner, which would be ignored. */
private fun modelsAlone(options: Map<String, String>, need: Pair<String, String>) =
    ("refine" in options && "rerank" !in options) || (need.first in options && need.second !in options)

/** The rows read by ink alone, then as a sentence at each ink weight, then with the sentence models of each 性能档位. */
private fun handwriting(dataPath: String, rowsPath: String, options: Map<String, String>, out: Appendable): Int {
    val run = HandwritingRun(loadData(dataPath))
    val rows = HandwritingRun.read(rowsPath).let { r -> options["limit"]?.toIntOrNull()?.let { r.take(it) } ?: r }
    val alternatives = options["alternatives"]?.toIntOrNull() ?: HandwritingDecoder.ALTERNATIVES
    out.appendLine("ink alone            ${run.ink(rows)}")
    val weights = options["weights"]?.split(',')?.map { it.toDouble() } ?: listOf(HandwritingDecoder.INK_WEIGHT)
    for (w in weights) out.appendLine(String.format(Locale.ROOT, "sentence, ink %-5s %s", w, run.sentences(rows, run.decoder(w, alternatives))))
    val small = options["rerank"]?.let { SentenceModel.load(mapFile(it), unpack = true) } ?: return 0
    val large = options["refine"]?.let { SentenceModel.load(mapFile(it), unpack = true) }
    val modelWeights = options["model-weights"]?.split(',')?.map { it.toFloat() } ?: listOf(Reranker.WEIGHT)
    for (mw in modelWeights) {
        val reranker = Reranker(small, mw)
        val medium = { before: String, d: HandwritingDecoder.Decoding -> reranker.pick(before, d.best, d.bestScores) }
        val decoder = run.decoder(weights.first(), alternatives)
        out.appendLine(String.format(Locale.ROOT, "medium %-5s     %s", mw, run.sentences(rows, decoder, medium)))
        val refiner = large?.let { Reranker(it, mw, limit = Reranker.REFINE_LIMIT) } ?: continue
        val high = HandwritingRun.refined(refiner, medium)
        out.appendLine(String.format(Locale.ROOT, "high %-5s       %s", mw, run.sentences(rows, decoder, high)))
    }
    return 0
}

private fun table(dataPath: String, setPath: String, preset: String, out: Appendable): Int {
    val run = TableRun(CodeTable.load(mapFile(dataPath)), TableRun.PRESETS.getValue(preset))
    val texts = readSet(setPath).map { it.expected }.distinct()
    val total = texts.fold(TableRun.Outcome.ZERO) { sum, text -> sum + run.type(text) }
    out.appendLine(TableRun.report(total, run.entries(ENTRY_STEP)))
    return 0
}

private fun tableHomographs(dataPath: String, setPath: String, preset: String, pinyinPath: String, rerank: String?, refine: String?, out: Appendable) {
    val run = TableRun(CodeTable.load(mapFile(dataPath)), TableRun.PRESETS.getValue(preset))
    val d = loadData(pinyinPath)
    val words = TextWords(d.model, d.wordIndex)
    val small = rerank?.let { Reranker(SentenceModel.load(mapFile(it), unpack = true), Reranker.WEIGHT) }
    val large = refine?.let { Reranker(SentenceModel.load(mapFile(it), unpack = true), Reranker.WEIGHT, limit = Reranker.REFINE_LIMIT) }
    val languages = listOfNotNull(
        "table's order" to null,
        "word model (low)" to TableLanguage(words),
        small?.let { "+ small model (medium)" to TableLanguage(words, reranker = it) },
        large?.let { "+ large model (high)" to TableLanguage(words, reranker = small, refiner = it) },
    )
    val texts = readSet(setPath).map { it.expected }.distinct()
    var n = 0
    val first = IntArray(languages.size)
    for (text in texts) {
        val (k, f) = run.homographs(text, languages.map { it.second })
        n += k
        f.indices.forEach { first[it] += f[it] }
    }
    out.appendLine("重码 words: $n")
    languages.forEachIndexed { i, (label, _) -> out.appendLine(String.format(Locale.ROOT, "  %-24s first %.1f%%", label, 100.0 * first[i] / maxOf(n, 1))) }
}

// a table's codes tapped: words of [setPath]'s texts, clean and with one key slipped, typed with slips read and without
private fun tableSlips(dataPath: String, setPath: String, preset: String, o: Map<String, String>, out: Appendable, err: Appendable): Int {
    val options = TableRun.PRESETS[preset] ?: run {
        err.appendLine("table-slips: no preset $preset; one of ${TableRun.PRESETS.keys.joinToString()}")
        return 2
    }
    if (o["plan"] !in setOf(null, "whole", "prefix")) {
        err.appendLine("table-slips: --plan whole or prefix")
        return 2
    }
    val floor = o["floor"]?.toFloatOrNull() ?: TableSlips.SLIPS.cost.floor
    val topping = o["topping-floor"]?.toFloatOrNull() ?: TableSlips.SLIPS.toppingFloor
    val language = o["words"]?.let { loadData(it) }?.let { TableLanguage(TextWords(it.model, it.wordIndex)) }
    val run = TableSlipRun(CodeTable.load(mapFile(dataPath)), options, TableSlips.Costs(TableSlips.SLIPS.cost.copy(floor = floor), topping), language)
    val texts = Halves.select(readSet(setPath), o["half"]).map { it.expected }.distinct()
    val hand = o["hand"] ?: "s0.2"
    val seed = o["seed"]?.toLongOrNull() ?: 1L
    // prefix: each word by its shortest code prefix that shows it first, then the next word's key
    val prefix = o["plan"] == "prefix"
    val clean = run.units(texts, hand, slip = false, seed, prefix)
    val slipped = run.units(texts, hand, slip = true, seed, prefix)
    val misses = o["misses"]?.let { File(it).printWriter() }
    val outcome = misses.use { run.measure(clean, slipped, it) }
    val plan = if (prefix) "parts that show the word first (${run.partial(clean)} of ${clean.size} no whole code)" else "whole codes"
    out.appendLine(TableSlipRun.report("$preset, $plan, floor $floor, topping $topping, $hand, ${o["half"] ?: "all"}: ${texts.size} texts", outcome))
    o["touched"]?.let { path -> File(path).writeText((run.touchedSet(clean, TOUCHED_KEYS) + run.touchedSet(slipped, TOUCHED_KEYS)).joinToString("\n", postfix = "\n")) }
    return 0
}

// a line of a set the device runner times: about as many keys as pinyin's
private const val TOUCHED_KEYS = 60

private val ON_OFF = setOf("on", "off")

private const val ENTRY_STEP = 50
