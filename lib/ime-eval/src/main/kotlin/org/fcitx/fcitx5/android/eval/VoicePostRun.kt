/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.input.voice.VoiceChoice
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones
import org.fcitx.fcitx5.android.input.voice.VoicePolishStream
import org.fcitx.fcitx5.android.input.voice.VoicePolishTags
import org.fcitx.fcitx5.android.input.voice.VoicePost
import java.io.File
import java.io.IOException
import java.util.Locale

/** The voice post-processing measured on recorded stretches, no recognizer: CER on words and on written text, the marks' P/R/F1. */
internal object VoicePostRun {

    /** The marks scored, by kind; any other mark (quotes, brackets, dashes) is left out of both texts. */
    enum class Mark(val symbol: Char) { COMMA('，'), PERIOD('。'), QUESTION('？') }

    private val MARKS = mapOf(
        '，' to Mark.COMMA, ',' to Mark.COMMA, '、' to Mark.COMMA, '；' to Mark.COMMA, ';' to Mark.COMMA,
        '：' to Mark.COMMA, ':' to Mark.COMMA,
        '。' to Mark.PERIOD, '.' to Mark.PERIOD, '！' to Mark.PERIOD, '!' to Mark.PERIOD, '…' to Mark.PERIOD,
        '？' to Mark.QUESTION, '?' to Mark.QUESTION,
    )

    /** [text] as words (a Han character, or a lower-cased run of Latin letters and digits), each with the mark after it. */
    class Tokens(val words: List<String>, val after: List<Mark?>)

    fun tokens(text: String): Tokens {
        val words = ArrayList<String>()
        val after = ArrayList<Mark?>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isLatinWord() -> {
                    val start = i
                    while (i < text.length && (text[i].isLatinWord() || pointInside(text, i))) i++
                    words += text.substring(start, i).lowercase(Locale.ROOT); after += null
                    continue
                }
                Character.isIdeographic(c.code) -> { words += c.toString(); after += null }
                c in MARKS -> if (words.isNotEmpty() && after.last() == null) after[after.size - 1] = MARKS.getValue(c)
            }
            i++
        }
        return Tokens(words, after)
    }

    // a point inside a number ("3.5") or a word ("e.g") is part of it, not a full stop
    private fun pointInside(text: String, i: Int): Boolean {
        if (text[i] != '.' || i + 1 >= text.length) return false
        return text[i + 1].isLatinWord() && text[i - 1].isLatinWord()
    }

    private fun Char.isLatinWord() = this < '\u0080' && isLetterOrDigit() || this in 'Ａ'..'Ｚ' || this in 'ａ'..'ｚ' || this in '０'..'９'

    class Score {
        var clips = 0
        var stretches = 0
        var refWords = 0
        var wordErrors = 0
        var refWritten = 0
        var writtenErrors = 0
        val tp = IntArray(Mark.entries.size)
        val fp = IntArray(Mark.entries.size)
        val fn = IntArray(Mark.entries.size)
        var refEnds = 0
        var hypEnds = 0
        var endsSame = 0
        var doubled = 0

        /** clips of a set with written references that have none: left out */
        var unwritten = 0

        /** `--source tagged`: clips with no labels, windows not found in the labelled text, and clips longer than a window ([Replay]) */
        var untagged = 0
        var unfound = 0
        var long = 0

        fun add(o: Score) {
            untagged += o.untagged; unfound += o.unfound; long += o.long
            clips += o.clips; stretches += o.stretches; refWords += o.refWords; wordErrors += o.wordErrors
            refWritten += o.refWritten; writtenErrors += o.writtenErrors
            for (k in tp.indices) { tp[k] += o.tp[k]; fp[k] += o.fp[k]; fn[k] += o.fn[k] }
            refEnds += o.refEnds; hypEnds += o.hypEnds; endsSame += o.endsSame; doubled += o.doubled; unwritten += o.unwritten
        }
    }

    /** One clip scored: [ref] the reference, [hyp] what was typed. Numbers are the tagger's (not run here): both left as written. */
    fun score(ref: String, hyp: String, into: Score) {
        val r = tokens(ref)
        val h = tokens(hyp)
        into.clips++
        into.refWords += r.words.size
        val path = align(r.words, h.words)
        into.wordErrors += path.count { it.first == null || it.second == null || r.words[it.first!!] != h.words[it.second!!] }
        val written = { t: Tokens -> t.words.indices.flatMap { listOfNotNull(t.words[it], t.after[it]?.symbol?.toString()) } }
        val rw = written(r)
        into.refWritten += rw.size
        into.writtenErrors += distance(rw, written(h))
        // the marks inside the text: after each word but the last of either
        for ((i, j) in path) {
            val rm = i?.takeIf { it < r.words.size - 1 }?.let { r.after[it] }
            val hm = j?.takeIf { it < h.words.size - 1 }?.let { h.after[it] }
            when {
                rm != null && rm == hm -> into.tp[rm.ordinal]++
                else -> {
                    rm?.let { into.fn[it.ordinal]++ }
                    hm?.let { into.fp[it.ordinal]++ }
                }
            }
        }
        val re = r.after.lastOrNull()
        val he = h.after.lastOrNull()
        if (re != null) into.refEnds++
        if (he != null) into.hypEnds++
        if (re == he) into.endsSame++
        into.doubled += DOUBLED.findAll(hyp).count()
    }

    private val DOUBLED = Regex("[，。？！、；：,.?!;:]\\s*[，。？！、；：,.?!;:]")

    /** The pairs of a least-cost alignment, a null on the side a word is missing from. */
    fun align(a: List<String>, b: List<String>): List<Pair<Int?, Int?>> {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) {
            d[i][j] = minOf(d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val out = ArrayList<Pair<Int?, Int?>>()
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1) {
                out += (i - 1) to (j - 1); i--; j--
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                out += (i - 1) to null; i--
            } else {
                out += null to (j - 1); j--
            }
        }
        return out.asReversed()
    }

    private fun distance(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) current[j] = minOf(previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, previous[j] + 1, current[j - 1] + 1)
            previous = current.also { current = previous }
        }
        return previous[b.size]
    }

    /** A `seg_app.py` clip: its reference ([written] if written_refs.py's) and the [source] text of each stretch. */
    class Clip(val wav: String, val ref: String, val written: Boolean, val stretches: List<VoicePost.Stretch>, val tagged: Tagged? = null)

    /** The polish tagger's labels for a clip, run by voice-eval, for `--source tagged`. */
    class Tagged(val text: IntArray, val labels: VoicePolishStream.Labels)

    /**
     * [tagged]'s labels for each window the stream asks about; one not found is all kept and counted in [misses]. A
     * character [swaps] has for the one tagged (a user's word put in for what was heard) is taken for it, its labels the heard one's.
     */
    class Replay(private val tagged: Tagged, private val swaps: Set<Long> = emptySet()) : VoicePolishStream.Tagger {
        var misses = 0
            private set
        private var from = 0

        override fun labels(text: IntArray): VoicePolishStream.Labels {
            val at = find(text, from) ?: find(text, 0)
            if (at == null) {
                misses++
                return VoicePolishStream.Labels(IntArray(text.size), IntArray(text.size))
            }
            from = at
            val l = tagged.labels
            return VoicePolishStream.Labels(l.op.copyOfRange(at, at + text.size), l.after.copyOfRange(at, at + text.size), l.num.copyOfRange(at, at + text.size))
        }

        private fun find(text: IntArray, start: Int): Int? = (start..tagged.text.size - text.size).firstOrNull { i ->
            text.indices.all { tagged.text[i + it] == text[it] || swap(text[it], tagged.text[i + it]) in swaps }
        }
    }

    private fun swap(put: Int, heard: Int) = put.toLong() shl Int.SIZE_BITS or heard.toLong()

    /**
     * The user's words of `--words` put in each stretch before the polish, as the app does ([VoiceChoice.pick]): the
     * engine's word model over [homophones], each stretch after the one kept before it.
     */
    class Words(private val engines: Engines, private val homophones: VoiceHomophones) {
        var put = 0
            private set

        // each stretch's pick, as the app's pickMs
        var picks = 0
            private set
        var pickNanos = 0L
            private set
        var pickMaxNanos = 0L
            private set

        /** The words [picked] put in, of its last call. */
        val putIn = LinkedHashSet<String>()

        fun picked(stretches: List<VoicePost.Stretch>): List<VoicePost.Stretch> {
            var before = ""
            putIn.clear()
            return stretches.map { s ->
                val t = System.nanoTime()
                val text = engines.voicePick(homophones, s.raw, before, emptyList())
                (System.nanoTime() - t).let { n ->
                    picks++
                    pickNanos += n
                    pickMaxNanos = maxOf(pickMaxNanos, n)
                }
                VoiceChoice.putIn(homophones, s.raw, text, putIn)
                if (text != s.raw) put++
                // as VoiceChoice.Talk.kept: a stretch of spaces is the next one's before too
                if (text.isNotEmpty()) before = text
                VoicePost.Stretch(text)
            }
        }

        fun summary(): String {
            val mean = if (picks == 0) 0.0 else pickNanos / 1e6 / picks
            return String.format(Locale.ROOT, "words: stretches changed=%d picks=%d pickMs mean=%.3f max=%.1f", put, picks, mean, pickMaxNanos / 1e6)
        }

        companion object {
            /** A TSV of `text, pinyin (spaced or with '), added|learned`; [bonus] the added and the learned bonus, null for the app's. */
            fun read(engines: Engines, file: File, bonus: Pair<Float, Float>?): Words = parsing(file.name) {
                val words = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                    val (text, pinyin, kind) = line.split('\t').also { if (it.size < 3) throw BadInput("${file.name}: not text, pinyin, kind: $line") }
                    val learned = when (kind) {
                        "added" -> false
                        "learned" -> true
                        else -> throw BadInput("${file.name}: added or learned, not $kind")
                    }
                    // a learned word counts once typed more than VoiceHomophones.LEARNED_MIN times
                    UserWord(text, pinyin.replace('\'', ' '), if (learned) UserWord.Kind.LEARNED else UserWord.Kind.ADDED, count = if (learned) VoiceHomophones.LEARNED_MIN + 1 else 0f)
                }
                val homophones = engines.voiceHomophones(words)
                Words(engines, bonus?.let { (a, l) -> homophones.withBonus(a, l) } ?: homophones)
            }
        }
    }

    /**
     * [path] again in [outDir], each stretch's `app` text with the user's words of [words] put in: for voice-eval's
     * tag_segs.py to tag what the polish now sees, the polish's marks then measured on it (`--source tagged`).
     */
    fun writePicked(path: String, words: Words, outDir: File) = parsing(File(path).name) {
        outDir.mkdirs()
        File(outDir, File(path).name).bufferedWriter().use { out ->
            for (line in File(path).readLines()) {
                if (line.isBlank()) continue
                val o = Json.parseToJsonElement(line).jsonObject
                val stretches = o.getValue("stretches").jsonArray.map { it.jsonObject }
                val picked = words.picked(stretches.map { VoicePost.Stretch(text(it, "app")) })
                val edited = stretches.zip(picked).map { (st, p) -> JsonObject(st + ("app" to JsonPrimitive(p.raw))) }
                out.appendLine(JsonObject(o + ("stretches" to JsonArray(edited))).toString())
            }
        }
    }

    /** An unreadable input file: a failed run (`voice-post: <message>`, exit 1), not a stack trace. Only [read] and [tags] throw it. */
    class BadInput(message: String, cause: Throwable? = null) : Exception(message, cause)

    // a json of the wrong shape (kotlinx's SerializationException is an IllegalArgumentException), a key missing, a file unreadable
    private inline fun <T> parsing(where: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw BadInput("$where: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw BadInput("$where: ${e.message}", e)
    } catch (e: NoSuchElementException) {
        throw BadInput("$where: ${e.message}", e)
    }

    /** The tag tables of a polish tagger's json, as VoicePolisher reads them; one VoicePolishTags refuses is [BadInput]. */
    fun tags(file: File): VoicePolishTags = parsing(file.name) {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        fun strings(key: String) = o.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        VoicePolishTags(strings("ops"), strings("after"), strings("num"))
    }

    /** [file]'s clips; a bad line ([BadInput]) is named by file and line number. */
    fun read(file: File, source: String): List<Clip> = parsing(file.name) { file.readLines() }.withIndex().filter { it.value.isNotBlank() }.map { (i, line) ->
        parsing("${file.name}:${i + 1}") { clip(file, line, source) }
    }

    private fun clip(file: File, line: String, source: String): Clip {
        val o = Json.parseToJsonElement(line).jsonObject
        // `"written": null` is no written reference, not the text "null"
        val written = (o["written"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        return Clip(
            o.getValue("wav").jsonPrimitive.content,
            written ?: o.getValue("ref").jsonPrimitive.content,
            written != null,
            o.getValue("stretches").jsonArray.map { s ->
                val st = s.jsonObject
                VoicePost.Stretch(text(st, source))
            },
            (o["tagged"] as? JsonObject)?.let { t ->
                fun ints(key: String) = t.getValue(key).jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
                val text = t.getValue("text").jsonPrimitive.content.codePoints().toArray()
                val labels = VoicePolishStream.Labels(ints("op"), ints("after"), ints("num"))
                // a label a character, or Replay would fail halfway through the run
                if (labels.op.size != text.size || labels.after.size != text.size || labels.num.size != text.size) {
                    throw BadInput(
                        "${file.name}: ${o.getValue("wav").jsonPrimitive.content}: tagged has ${text.size} characters and " +
                            "${labels.op.size}/${labels.after.size}/${labels.num.size} op/after/num labels",
                    )
                }
                Tagged(text, labels)
            },
        )
    }

    // `tagged`: the rerank's text, as `app`, polished with the tagger's labels
    private fun text(st: JsonObject, source: String) = when (source) {
        "top" -> st.getValue("nbest").jsonArray.firstOrNull()?.jsonPrimitive?.content.orEmpty()
        else -> st.getValue("app").jsonPrimitive.content
    }


    /** Scores each file of [paths] and all together; [misses] and [texts] are TSVs of clips. */
    fun run(
        paths: List<String>,
        source: String,
        misses: String?,
        out: Appendable,
        texts: String? = null,
        polish: Polish? = null,
        words: Words? = null,
    ): Int {
        misses?.let { File(it).bufferedWriter() }.use { m ->
            texts?.let { File(it).bufferedWriter() }.use { t -> run(paths, source, m, t, out, polish, words) }
        }
        return 0
    }

    class Polish(val tags: VoicePolishTags, val guard: Boolean = true)

    private fun run(paths: List<String>, source: String, missed: Appendable?, texts: Appendable?, out: Appendable, polish: Polish?, words: Words?) {
        val all = Score()
        missed?.appendLine("set\twav\tref\thyp")
        texts?.appendLine("set\twav\tref\thyp")
        out.appendLine(HEADER)
        for (path in paths) {
            val name = File(path).name.removePrefix("seg-").removeSuffix(".jsonl")
            val score = Score()
            val clips = read(File(path), source)
            // with written references, a clip without one would be scored against no marks, so it is left out
            val scored = if (clips.any { it.written }) clips.filter { it.written } else clips
            score.unwritten = clips.size - scored.size
            for (clip in scored) {
                val stretches = words?.picked(clip.stretches) ?: clip.stretches
                val swaps = swaps(clip.stretches, stretches)
                val putIn = words?.putIn?.toList().orEmpty()
                val replay = polish?.let { p -> clip.tagged?.let { Replay(it, swaps) }?.let { it to VoicePolishStream(p.tags, it, guard = p.guard, words = putIn) } }
                // a clip voice-eval did not tag: as the app's with the tagger failing, and counted
                if (polish != null && replay == null) score.untagged++
                if (polish != null && (clip.tagged?.text?.size ?: 0) > VoicePolishStream.WINDOW) score.long++
                val hyp = VoicePost.text(stretches, replay?.second)
                replay?.first?.let { score.unfound += it.misses }
                score.stretches += stretches.size
                val before = score.writtenErrors
                score(clip.ref, hyp, score)
                val line = listOf(name, clip.wav, clip.ref, hyp).joinToString("\t") { it.replace(WHITE, " ") }
                if (score.writtenErrors > before) missed?.appendLine(line)
                texts?.appendLine(line)
            }
            out.appendLine(row(name, score))
            all.add(score)
        }
        if (paths.size > 1) out.appendLine(row("all", all))
        // after the table: its columns are what voice-eval reads
        if (polish != null) out.appendLine("tagged: guard=${polish.guard} untagged=${all.untagged} unfound=${all.unfound} long=${all.long}")
        words?.let { out.appendLine(it.summary()) }
    }

    // each character a word put in for the one heard there, whose labels it takes
    private fun swaps(heard: List<VoicePost.Stretch>, put: List<VoicePost.Stretch>): Set<Long> = heard.zip(put)
        .filter { (h, p) -> h.raw.length == p.raw.length }
        .flatMap { (h, p) -> h.raw.indices.filter { h.raw[it] != p.raw[it] }.map { swap(p.raw[it].code, h.raw[it].code) } }
        .toSet()

    private val WHITE = Regex("[\t\r\n]")

    private val HEADER = listOf("set", "clips", "stretches", "CER", "written", "，P/R/F1", "。P/R/F1", "？P/R/F1", "marks F1", "ends ref/hyp/same", "doubled", "unwritten")
        .joinToString("\t")

    fun row(name: String, s: Score): String {
        fun pct(n: Int, d: Int) = if (d == 0) "-" else String.format(Locale.ROOT, "%.2f%%", 100.0 * n / d)
        fun prf(tp: Int, fp: Int, fn: Int): String {
            // nothing to score (a set without written references)
            if (tp + fp + fn == 0) return "-"
            val p = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
            val r = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
            val f = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
            return String.format(Locale.ROOT, "%.2f/%.2f/%.2f", p, r, f)
        }
        val marks = Mark.entries.map { prf(s.tp[it.ordinal], s.fp[it.ordinal], s.fn[it.ordinal]) }
        val f1 = if (s.tp.sum() + s.fn.sum() == 0) "-" else prf(s.tp.sum(), s.fp.sum(), s.fn.sum()).substringAfterLast('/')
        return (listOf(name, "${s.clips}", "${s.stretches}", pct(s.wordErrors, s.refWords), pct(s.writtenErrors, s.refWritten)) + marks +
            listOf(f1, "${pct(s.refEnds, s.clips)}/${pct(s.hypEnds, s.clips)}/${pct(s.endsSame, s.clips)}", "${s.doubled}", "${s.unwritten}")).joinToString("\t")
    }
}
