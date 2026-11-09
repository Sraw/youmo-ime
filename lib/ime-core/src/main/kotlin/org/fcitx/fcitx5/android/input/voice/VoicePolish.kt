/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The polish tagger's labels (060-voice-tagger.md; voice-eval's `tags.py`, which this follows
 * character for character): each character of the recognised text gets an op, kept, deleted or
 * written as one of [ops]' replacements, and an after, nothing or one of [afters]' marks written
 * after it, and a number class, none or one of [nums] ([ChineseNumbers.Kind]): adjacent characters of a
 * class not none are a number, written as the class most of them have ([ChineseNumbers.written]).
 * [ops], [afters] and [nums] are the model's own lists (its `polish-tagger.json`), in its order.
 *
 * Characters are code points, as the model's vocabulary and Python's strings have them.
 */
class VoicePolishTags(val ops: List<String>, val afters: List<String>, val nums: List<String>) {

    private val kinds = nums.map(ChineseNumbers.Kind::of)

    init {
        require(ops.getOrNull(KEEP) == "keep" && ops.getOrNull(DEL) == "del") { "ops start keep, del: $ops" }
        require(afters.firstOrNull() == "") { "afters start with nothing: $afters" }
        require(kinds.firstOrNull() == ChineseNumbers.Kind.NONE) { "nums start with none: $nums" }
    }

    /**
     * What the op and after labels of each character of [text] (normed, [norm]) write; a kept
     * character with [spaced] (the space [norm] took out between two English words, [spaces]) after
     * an English letter or digit written with it. [num]: each character's number class (none if
     * not given); a character [op] deletes is in no number, so one that went (a pause's 「，」 inside
     * it) leaves the number whole.
     */
    fun apply(text: IntArray, op: IntArray, after: IntArray, spaced: BooleanArray? = null, num: IntArray? = null): String {
        require(op.size == text.size && after.size == text.size) { "a label each: ${text.size}, ${op.size}, ${after.size}" }
        require(num == null || num.size == text.size) { "a number class each: ${text.size}, ${num?.size}" }
        val out = StringBuilder()
        val run = StringBuilder()
        val runKinds = ArrayList<ChineseNumbers.Kind>()
        fun flush() {
            if (run.isEmpty()) return
            // the class most of its characters have, the first of them on a tie
            val kind = runKinds.groupingBy { it }.eachCount().let { counts -> runKinds.maxBy { counts.getValue(it) } }
            out.append(ChineseNumbers.written(run.toString(), kind))
            run.clear()
            runKinds.clear()
        }
        for (i in text.indices) {
            val written = when (op[i]) {
                KEEP -> kept(text[i], out, spaced != null)
                DEL -> ""
                else -> ops[op[i]]
            }
            val kind = num?.let { kinds[it[i]] } ?: ChineseNumbers.Kind.NONE
            if (written.isNotEmpty()) {
                // an English letter or digit is no part of one (Windows11: the space before 11 kept)
                if (kind != ChineseNumbers.Kind.NONE && written !in NUMBER_BREAKS && written.codePointAt(0) >= ASCII) {
                    run.append(written)
                    runKinds += kind
                } else {
                    flush()
                    if (op[i] == KEEP && spaced?.getOrNull(i) == true && endsAWord(out)) out.append(' ')
                    out.append(written)
                }
            }
            if (after[i] != 0) {
                flush()
                out.append(afters[after[i]])
            }
        }
        flush()
        // a pause's mark before another mark goes (。 written after a 、 the input had); 「……」 is one mark inside
        return PAUSE_BEFORE_MARK.replace(out, "").replace("…", "……")
    }

    /**
     * The model's labels of [text] (normed) with what it does wrong in English undone, in place:
     * an English letter is kept (its ops are Chinese words and a del, which took the second r of
     * 「sorry」 as a stutter: 「I'm sory」 typed on a OnePlus 6T, 2026-11-06), and nothing is written
     * after a full stop, question or exclamation mark (「I'm sorry.。」, 「。。」 read as an ellipsis). Applied by [VoicePolishStream] to what the model says; not in
     * [apply], which is voice-eval's tags.py to the letter.
     */
    fun guarded(text: IntArray, op: IntArray, after: IntArray): Pair<IntArray, IntArray> {
        for (i in text.indices) {
            if (text[i] < 0x80 && Character.isLetter(text[i])) op[i] = KEEP
            if (text[i] in STOPS) after[i] = 0
        }
        return op to after
    }

    companion object {
        const val KEEP = 0
        const val DEL = 1

        // U+0085 too, which Python's \s takes and Kotlin's isWhitespace does not
        private const val DROPPED = "《》·\u0085"

        private const val ASCII = 0x80

        // norm's full width marks, and what English writes for each
        private val WIDE_MARKS = "，？！：；".codePoints().toArray()
        private const val ASCII_MARKS = ",?!:;"
        private val STOPS = ".。？！".codePoints().toArray()

        // marks end a number though the tagger put them in one (numeval.py render); each of VoiceLive.ENDS
        // among them (VoicePolishTest checks), so what is typed as it is said is never cut inside a number
        internal val NUMBER_BREAKS = setOf("，", "。", "？", "！", "、", "…", "：", "；", ",", "?", "!", ";")

        private val PAUSE_BEFORE_MARK = Regex("[，、；：]+(?=[。？！…，])")

        // a mark after an English letter as English writes it (「Yes, I know?」): norm made it full width
        private fun kept(c: Int, out: StringBuilder, english: Boolean): String {
            val afterLetter = out.lastOrNull()?.let { it.code < ASCII && it.isLetter() } == true
            return if (english && afterLetter && c in WIDE_MARKS) ASCII_MARKS[WIDE_MARKS.indexOf(c)].toString()
            else String(Character.toChars(c))
        }

        private fun isAsciiWord(c: Int) = c < 0x80 && Character.isLetterOrDigit(c)

        // an English word, or its mark (「Yes, I」: the space after the comma kept)
        private fun endsAWord(out: StringBuilder) = out.isNotEmpty() && (isAsciiWord(out.last().code) || out.last() in ASCII_MARKS)

        /**
         * For each character of [norm]`(raw)`, whether [raw] had a space before it between two
         * English words (letters or digits), or after an English word's mark (「Yes, I」): the tagger
         * reads none, the text written keeps them. A space is what [norm] takes out (Kotlin's
         * isWhitespace, NBSP among them, and U+0085).
         * [norm] neither drops nor changes a letter or digit, so the k-th of them is the raw text's k-th.
         */
        fun spaces(raw: String, normed: IntArray): BooleanArray {
            val wanted = ArrayList<Boolean>()
            var lastWord = false
            var gap = false
            raw.codePoints().forEach { c ->
                when {
                    isAsciiWord(c) -> {
                        wanted += lastWord && gap
                        lastWord = true
                        gap = false
                    }
                    isSpace(c) -> gap = true
                    // the word goes on through its mark: 「Yes, I」
                    lastWord && !gap && c < ASCII && c.toChar() in ASCII_MARKS -> Unit
                    else -> {
                        lastWord = false
                        gap = false
                    }
                }
            }
            var k = 0
            return BooleanArray(normed.size) { i -> isAsciiWord(normed[i]) && wanted.getOrElse(k++) { false } }
        }

        private fun isSpace(c: Int) = c == 0x85 || (Character.isBmpCodePoint(c) && c.toChar().isWhitespace())

        /** [text] as the tagger reads it: no spaces, title marks or ·, 「……」 and ... as one 「…」, ASCII marks full width. */
        fun norm(text: String): IntArray {
            val s = StringBuilder()
            for (c in text) if (!c.isWhitespace() && c !in DROPPED) s.append(c)
            val t = s.toString().replace("……", "…").replace("...", "…")
                .replace(',', '，').replace('?', '？').replace('!', '！').replace(':', '：').replace(';', '；')
            return t.codePoints().toArray()
        }
    }
}

/** The model's inputs and outputs (voice-eval's `export_onnx.py`): ids `[CLS] text [SEP]`, logits a label each. */
class VoicePolishVocab(vocab: List<String>, private val cls: Long, private val sep: Long, private val unk: Long) {

    private val ids = HashMap<Int, Long>(vocab.size * 2).apply {
        vocab.forEachIndexed { i, ch -> if (ch.codePointCount(0, ch.length) == 1) putIfAbsent(ch.codePointAt(0), i.toLong()) }
    }

    /** The model's `ids` input for [text] (code points), a row. */
    fun encode(text: IntArray): LongArray =
        LongArray(text.size + 2) { i ->
            when (i) {
                0 -> cls
                text.size + 1 -> sep
                else -> ids[text[i - 1]] ?: unk
            }
        }

    companion object {
        /** Each of [n] rows' best label of [logits] (rows of [width], one after another): the model's `op` or `after`. */
        fun best(logits: FloatArray, n: Int, width: Int): IntArray {
            require(logits.size == n * width) { "${logits.size} logits, not $n rows of $width" }
            return IntArray(n) { row ->
                var best = 0
                for (k in 1 until width) if (logits[row * width + k] > logits[row * width + best]) best = k
                best
            }
        }
    }
}

/**
 * The tagger run as streaming recognition hands text over (060-voice-tagger.md §4.1–4.2): each
 * time the press's text so far is tagged on its last [window] characters, and a character's labels
 * are kept for good ("frozen") once [freezeAfter] characters follow it, or once it leaves the
 * window; at the end, what is left is tagged with the rest and frozen. Frozen characters are always
 * a prefix of the text.
 *
 * The recogniser may write a character again differently (its best hypothesis changing): from where
 * the new text first differs the labels are dropped, frozen ones too, and tagged again.
 */
class VoicePolishStream(
    private val tags: VoicePolishTags,
    private val tagger: Tagger,
    private val freezeAfter: Int = FREEZE_AFTER,
    private val window: Int = WINDOW,
    /** the labels [VoicePolishTags.guarded]; false only for voice-eval's goldens, which have none */
    private val guard: Boolean = true,
) {
    /** The op, after and number labels of [text] (code points), a character each; the model run. */
    fun interface Tagger {
        fun labels(text: IntArray): Labels
    }

    /** A label each of the characters tagged; [num] none (0) if not given. */
    class Labels(val op: IntArray, val after: IntArray, val num: IntArray = IntArray(op.size))

    /**
     * What to show: the frozen characters polished, then the rest of the text as recognised (normed).
     * Not always where [finish] ends up word for word: a pause's mark at the frozen end before a mark
     * in the rest goes only once both are polished, and the rest's 「…」 is 「……」 then.
     */
    data class Shown(val polished: String, val rest: String)

    init {
        require(freezeAfter in 0 until window) { "freezeAfter $freezeAfter, window $window" }
    }

    private var text = IntArray(0)
    private var spaced = BooleanArray(0)

    // the length of the prefix last tagged, and each of its characters' labels as last tagged
    private var done = 0
    private var seenOp = IntArray(0)
    private var seenAfter = IntArray(0)
    private var seenNum = IntArray(0)

    // the frozen prefix's length; its labels are seen's
    private var frozen = 0

    /** The press's text so far, [raw] as recognised; what to show. */
    fun update(raw: String): Shown {
        take(raw)
        // a step at most window - freezeAfter long: the characters it pushes out of the window were tagged at
        // the step before, with freezeAfter characters after them
        while (done < text.size) step(minOf(text.size, done + window - freezeAfter), last = false)
        return shown()
    }

    /** The press's text when it ends, [raw] as recognised: polished whole. */
    fun finish(raw: String): String {
        take(raw)
        if (text.isEmpty()) return ""
        while (text.size - done > window - freezeAfter) step(done + window - freezeAfter, last = false)
        step(text.size, last = true)
        return tags.apply(text, seenOp.copyOf(text.size), seenAfter.copyOf(text.size), spaced, seenNum.copyOf(text.size))
    }

    /**
     * The first [count] characters of the text last [update]d, no more than are frozen, polished:
     * what they write whatever is said after them, but for a pause's mark at its very end, which
     * a mark coming next takes away ([VoicePolishTags.apply]).
     */
    fun frozenPrefix(count: Int): String {
        val n = minOf(count, frozen)
        return tags.apply(text.copyOf(n), seenOp.copyOf(n), seenAfter.copyOf(n), spaced.copyOf(n), seenNum.copyOf(n))
    }

    private fun take(raw: String) {
        val next = VoicePolishTags.norm(raw)
        var same = 0
        val most = minOf(text.size, next.size)
        while (same < most && text[same] == next[same]) same++
        done = minOf(done, same)
        frozen = minOf(frozen, same)
        text = next
        val wasSpaced = spaced
        spaced = VoicePolishTags.spaces(raw, next)
        // the polished prefix shown holds while its characters and their spaces do
        if (shownCut > frozen || (0 until shownCut).any { spaced[it] != wasSpaced[it] }) shownCut = -1
        if (seenOp.size < text.size) {
            seenOp = seenOp.copyOf(text.size)
            seenAfter = seenAfter.copyOf(text.size)
            seenNum = seenNum.copyOf(text.size)
        }
    }

    private fun step(end: Int, last: Boolean) {
        val start = maxOf(0, end - window)
        // left the window before freezeAfter characters followed: frozen as last tagged
        frozen = maxOf(frozen, start)
        val part = text.copyOfRange(start, end)
        val labels = tagger.labels(part)
        val op = labels.op
        val after = labels.after
        val num = labels.num
        check(op.size == end - start && after.size == end - start && num.size == end - start) { "a label each of ${end - start} characters" }
        if (guard) tags.guarded(part, op, after)
        // a frozen character keeps its labels, though tagged again in the window
        op.copyInto(seenOp, frozen, frozen - start, op.size)
        after.copyInto(seenAfter, frozen, frozen - start, after.size)
        num.copyInto(seenNum, frozen, frozen - start, num.size)
        frozen = maxOf(frozen, if (last) end else end - freezeAfter)
        done = end
    }

    // the frozen prefix last polished for shown, and its length: polished again only when it changed
    private var shownCut = -1
    private var shownPolished = ""

    private fun shown(): Shown {
        // a number going on past the frozen end shows whole in the rest, not half written (二零二 202 then 六)
        var cut = frozen
        if (cut < done && seenNum[cut] != 0) while (cut > 0 && seenNum[cut - 1] != 0) cut--
        if (cut != shownCut) {
            shownPolished = tags.apply(text.copyOf(cut), seenOp.copyOf(cut), seenAfter.copyOf(cut), spaced, seenNum.copyOf(cut))
            shownCut = cut
        }
        val polished = shownPolished
        val rest = StringBuilder()
        for (i in cut until text.size) {
            if (spaced[i]) rest.append(' ')
            rest.appendCodePoint(text[i])
        }
        return Shown(polished, rest.toString())
    }

    companion object {
        /** K: 16 characters after a character before its labels are kept (§4.1: no worse than tagging whole) */
        const val FREEZE_AFTER = 16

        /** the characters tagged at a time, which bounds a step's cost (§4.1: 64 tag as well as the whole prefix) */
        const val WINDOW = 64
    }
}
