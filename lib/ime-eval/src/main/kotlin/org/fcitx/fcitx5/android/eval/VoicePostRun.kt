/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.input.voice.VoicePolishStream
import org.fcitx.fcitx5.android.input.voice.VoicePolishTags
import org.fcitx.fcitx5.android.input.voice.VoicePost
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The voice post-processing measured on recorded stretches (voice-eval's `seg_app.py`: a clip a
 * line, each stretch's n-best, the app's rerank and its time): no recognizer runs, so a change to
 * [VoicePost] is measured in seconds. Against the set's reference text, which carries punctuation:
 * the error rate on the words alone (CER, as voice-eval's MER), on the written text (the three kinds
 * of mark as tokens too), how the marks inside the text land (P/R/F1 by kind) and how a text ends.
 */
internal object VoicePostRun {

    /** The marks scored, by kind; any other mark (quotes, brackets, dashes) is left out of both texts. */
    enum class Mark(val symbol: Char) { COMMA('，'), PERIOD('。'), QUESTION('？') }

    private val MARKS = mapOf(
        '，' to Mark.COMMA, ',' to Mark.COMMA, '、' to Mark.COMMA, '；' to Mark.COMMA, ';' to Mark.COMMA,
        '：' to Mark.COMMA, ':' to Mark.COMMA,
        '。' to Mark.PERIOD, '.' to Mark.PERIOD, '！' to Mark.PERIOD, '!' to Mark.PERIOD, '…' to Mark.PERIOD,
        '？' to Mark.QUESTION, '?' to Mark.QUESTION,
    )

    /** [text] as words (a Han character, or a run of Latin letters and digits, lower case), each with the mark after it. */
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

    /** Counts over a set, or over several. */
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

        /** clips of a set with written references that have none (written_refs.py could not make one): left out */
        var unwritten = 0

        /**
         * `--source tagged`: clips voice-eval gave no labels, windows of the app's stream not found in its labelled text,
         * and clips labelled longer than the app's window ([Replay]: their labels saw more than the app's model would)
         */
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

    /**
     * One clip scored: [ref] the reference, [hyp] what was typed. Numbers are the tagger's (not run here):
     * both are left as written.
     */
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

    /**
     * A clip of a `seg_app.py` file: its reference and stretches, the [source] text of each (`app`, the rerank's; `top`, the
     * recognizer's first). The reference is the written one where written_refs.py made it (marks in, fillers and stammers out:
     * what should be typed), else the set's own ([written] false).
     */
    class Clip(val wav: String, val ref: String, val written: Boolean, val stretches: List<VoicePost.Stretch>, val tagged: Tagged? = null)

    /**
     * What the polish tagger made of a clip, run by voice-eval on the text the app's tagger sees (the
     * stretches' `app` texts joined, normed: VoicePolishStream's), for `--source tagged`: [text]'s code
     * points and a label each, indices into the tagger json's `ops`, `after` and `num`. The clip's
     * `"tagged": {"text": …, "op": […], "after": […], "num": […]}`.
     */
    class Tagged(val text: IntArray, val labels: VoicePolishStream.Labels)

    /**
     * [tagged]'s labels for each window the app's stream asks about, found in its text from where the
     * last one was; one not found (the texts differ) all kept, counted in [misses].
     *
     * The labels are the whole clip's, tagged once by voice-eval, not the app's: on the phone the model
     * sees only the window it is asked about ([VoicePolishStream.WINDOW] characters at most, nothing after
     * its end) and a character's labels are kept once [VoicePolishStream.FREEZE_AFTER] follow it. So a label
     * here may have seen more context than the app's would; clips longer than a window, where that is
     * largest, are counted in the run's `tagged:` line (`long`). A window whose text also comes earlier
     * from where the last one was (a stammer of a whole window, 对对对…) takes the first one's labels:
     * nothing counts that.
     */
    class Replay(private val tagged: Tagged) : VoicePolishStream.Tagger {
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
            text.indices.all { tagged.text[i + it] == text[it] }
        }
    }

    /**
     * A file of the run's input that cannot be read as one: said as a failed run (`voice-post: <message>`, exit 1),
     * not a stack trace. Only [read] and [tags] throw it, so an IllegalArgumentException anywhere else is the
     * tool's own and still ends the run loudly.
     */
    class BadInput(message: String, cause: Throwable? = null) : Exception(message, cause)

    // what a json not of the shape asked for throws: not json or a json shape the accessors refuse (both
    // IllegalArgumentException, kotlinx's SerializationException among them; a label not a number too), a key missing;
    // and a file not there or not readable (a path mistyped, the likeliest wrong input: R25r5 F8020)
    private inline fun <T> parsing(where: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw BadInput("$where: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw BadInput("$where: ${e.message}", e)
    } catch (e: NoSuchElementException) {
        throw BadInput("$where: ${e.message}", e)
    }

    /**
     * The tag tables of a polish tagger's json (the voice pack's `polish-tagger.json`), as VoicePolisher reads them;
     * a file VoicePolishTags refuses (as the app's tagger would fail to load) is [BadInput].
     */
    fun tags(file: File): VoicePolishTags = parsing(file.name) {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        fun strings(key: String) = o.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        VoicePolishTags(strings("ops"), strings("after"), strings("num"))
    }

    /** [file]'s clips; a line that is not one ([BadInput]) is named by the file and its line number. */
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
                // a label a character, or Replay would fail halfway through the run on a slice past the end
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


    /**
     * Scores each file of [paths] and all together; [misses], a TSV of the clips whose written text is not the reference's;
     * [texts], one of every clip scored (what a layer after these, voice-eval's, starts from).
     * [polish]: with `--source tagged`, the tagger's tables and whether its labels are [VoicePolishTags.guarded]
     * as the app's are: the whole of the app's post-processing but the homophones (the engine's), so a
     * retrained tagger is scored much as the user gets it, and each guard is measured by turning them off.
     * Not quite: the labels are the whole clip's, not the app's windows' ([Replay]); the `tagged:` line
     * after the table counts the clips longer than a window.
     */
    fun run(
        paths: List<String>,
        source: String,
        misses: String?,
        out: Appendable,
        texts: String? = null,
        polish: Polish? = null,
    ): Int {
        misses?.let { File(it).bufferedWriter() }.use { m ->
            texts?.let { File(it).bufferedWriter() }.use { t -> run(paths, source, m, t, out, polish) }
        }
        return 0
    }

    class Polish(val tags: VoicePolishTags, val guard: Boolean = true)

    private fun run(paths: List<String>, source: String, missed: Appendable?, texts: Appendable?, out: Appendable, polish: Polish?) {
        val all = Score()
        missed?.appendLine("set\twav\tref\thyp")
        texts?.appendLine("set\twav\tref\thyp")
        out.appendLine(HEADER)
        for (path in paths) {
            val name = File(path).name.removePrefix("seg-").removeSuffix(".jsonl")
            val score = Score()
            val clips = read(File(path), source)
            // a set with written references: a clip without one would be scored against no marks at all;
            // left out of every column, the CER too (not to be set beside a run before written references)
            val scored = if (clips.any { it.written }) clips.filter { it.written } else clips
            score.unwritten = clips.size - scored.size
            for (clip in scored) {
                val replay = polish?.let { p -> clip.tagged?.let(::Replay)?.let { it to VoicePolishStream(p.tags, it, guard = p.guard) } }
                // a clip voice-eval did not tag: as the app's with the tagger failing, and counted
                if (polish != null && replay == null) score.untagged++
                if (polish != null && (clip.tagged?.text?.size ?: 0) > VoicePolishStream.WINDOW) score.long++
                val hyp = VoicePost.text(clip.stretches, replay?.second)
                replay?.first?.let { score.unfound += it.misses }
                score.stretches += clip.stretches.size
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
        // after the table, not a column of it: the table's columns are what voice-eval reads
        if (polish != null) out.appendLine("tagged: guard=${polish.guard} untagged=${all.untagged} unfound=${all.unfound} long=${all.long}")
    }

    private val WHITE = Regex("[\t\r\n]")

    private val HEADER = listOf("set", "clips", "stretches", "CER", "written", "，P/R/F1", "。P/R/F1", "？P/R/F1", "marks F1", "ends ref/hyp/same", "doubled", "unwritten")
        .joinToString("\t")

    fun row(name: String, s: Score): String {
        fun pct(n: Int, d: Int) = if (d == 0) "-" else String.format(Locale.ROOT, "%.2f%%", 100.0 * n / d)
        fun prf(tp: Int, fp: Int, fn: Int): String {
            // no mark in the references nor the text: nothing to score (a set without written references)
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
