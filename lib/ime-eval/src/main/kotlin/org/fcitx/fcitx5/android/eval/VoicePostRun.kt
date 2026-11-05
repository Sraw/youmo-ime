/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.input.voice.ChineseNumbers
import org.fcitx.fcitx5.android.input.voice.VoicePost
import java.io.File
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

        fun add(o: Score) {
            clips += o.clips; stretches += o.stretches; refWords += o.refWords; wordErrors += o.wordErrors
            refWritten += o.refWritten; writtenErrors += o.writtenErrors
            for (k in tp.indices) { tp[k] += o.tp[k]; fp[k] += o.fp[k]; fn[k] += o.fn[k] }
            refEnds += o.refEnds; hypEnds += o.hypEnds; endsSame += o.endsSame; doubled += o.doubled; unwritten += o.unwritten
        }
    }

    /**
     * One clip scored: [ref] the reference, [hyp] what was typed. The reference's numbers in digits as the
     * app types them ([ChineseNumbers]; AISHELL writes 二六六万): what is measured is the post-processing, not that.
     */
    fun score(ref: String, hyp: String, into: Score) {
        val r = tokens(ChineseNumbers.convert(ref))
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
    class Clip(val wav: String, val ref: String, val written: Boolean, val stretches: List<VoicePost.Stretch>)

    fun read(file: File, source: String): List<Clip> = file.readLines().filter { it.isNotBlank() }.map { line ->
        val o = Json.parseToJsonElement(line).jsonObject
        Clip(
            o.getValue("wav").jsonPrimitive.content,
            (o["written"] ?: o.getValue("ref")).jsonPrimitive.content,
            "written" in o,
            o.getValue("stretches").jsonArray.map { s ->
                val st = s.jsonObject
                VoicePost.Stretch(text(st, source), (st.seconds("start") * 1000).toLong(), (st.seconds("end") * 1000).toLong())
            },
        )
    }

    private fun text(st: JsonObject, source: String) = when (source) {
        "top" -> st.getValue("nbest").jsonArray.first().jsonPrimitive.content
        else -> st.getValue("app").jsonPrimitive.content
    }

    private fun JsonObject.seconds(key: String) = getValue(key).jsonPrimitive.content.toDouble()

    /**
     * Scores each file of [paths] and all together; [misses], a TSV of the clips whose written text is not the reference's;
     * [texts], one of every clip scored (what a layer after these, voice-eval's, starts from).
     */
    fun run(
        paths: List<String>,
        source: String,
        misses: String?,
        out: Appendable,
        layers: VoicePost.Layers = VoicePost.Layers(),
        texts: String? = null,
    ): Int {
        misses?.let { File(it).bufferedWriter() }.use { m ->
            texts?.let { File(it).bufferedWriter() }.use { t -> run(paths, source, m, t, out, layers) }
        }
        return 0
    }

    private fun run(paths: List<String>, source: String, missed: Appendable?, texts: Appendable?, out: Appendable, layers: VoicePost.Layers) {
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
                val hyp = VoicePost.text(clip.stretches, layers)
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
