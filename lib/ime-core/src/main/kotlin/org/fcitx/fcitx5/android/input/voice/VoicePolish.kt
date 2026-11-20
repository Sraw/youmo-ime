/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The polish tagger's labels, following voice-eval's `tags.py` character for character: per code point an op
 * (keep, delete, or one of [ops]), an after mark ([afters]) and a number class ([nums]), in the model's
 * `polish-tagger.json` order. Adjacent characters of one class form a number ([ChineseNumbers.written]).
 */
class VoicePolishTags(val ops: List<String>, val afters: List<String>, val nums: List<String>) {

    private val kinds = nums.map(ChineseNumbers.Kind::of)

    init {
        require(ops.getOrNull(KEEP) == "keep" && ops.getOrNull(DEL) == "del") { "ops start keep, del: $ops" }
        require(afters.firstOrNull() == "") { "afters start with nothing: $afters" }
        require(kinds.firstOrNull() == ChineseNumbers.Kind.NONE) { "nums start with none: $nums" }
    }

    /**
     * What the labels of normed [text] write. A deleted character is in no number, so a pause's 「，」 inside one
     * leaves it whole. [spaced] and [ascii] restore the space and ASCII mark English had (「Yes, I」).
     */
    fun apply(
        text: IntArray,
        op: IntArray,
        after: IntArray,
        spaced: BooleanArray? = null,
        num: IntArray? = null,
        ascii: BooleanArray? = null,
    ): String {
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
                KEEP -> kept(text[i], run, out, ascii?.getOrNull(i) == true)
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
        // a pause's mark before another mark goes; 「……」 is one mark inside
        return PAUSE_BEFORE_MARK.replace(out, "").replace("…", "……")
    }

    /**
     * Undoes, in place, what the model gets wrong: an English letter is always kept (its del took sorry's
     * second r as a stutter) and nothing is written after 。？！. Also, whatever the model: a Chinese number's
     * character ([NUMBER_CHARS]) or an ASCII digit is never deleted (the vocabulary reads 0 and 9 as <unk>), and
     * nothing inside an [IDIOMS] entry is a number. Applied by [VoicePolishStream], not in [apply] (which is
     * tags.py to the letter). [lead]: the text before [text], so an idiom cut by a window's start is found.
     */
    fun guarded(text: IntArray, op: IntArray, after: IntArray, num: IntArray? = null, lead: IntArray = IntArray(0)) {
        for (i in text.indices) {
            if (text[i] < 0x80 && Character.isLetter(text[i])) op[i] = KEEP
            if (text[i] in STOPS) after[i] = 0
            if (op[i] == DEL && (text[i] in NUMBER_CHARS || text[i] in '0'.code..'9'.code)) op[i] = KEEP
        }
        if (num == null) return
        for (span in idioms(lead + text)) for (i in span) if (i >= lead.size) num[i - lead.size] = 0
    }

    companion object {
        const val KEEP = 0
        const val DEL = 1

        // U+0085 too, which Python's \s takes and Kotlin's isWhitespace does not
        private const val DROPPED = "《》·\u0085"

        private const val ASCII = 0x80

        private val WIDE_MARKS = VoiceMarks.WIDE.codePoints().toArray()
        private const val ASCII_MARKS = VoiceMarks.ASCII
        private val STOPS = VoiceMarks.STOPS.codePoints().toArray()

        // marks that end a number; each of VoiceLive.ENDS among them (VoicePolishTest), so typing never cuts inside one
        internal val NUMBER_BREAKS = VoiceMarks.NUMBER_BREAKS

        /** Never deleted ([guarded]); 点 among them (三点五). */
        val NUMBER_CHARS = "一二两三四五六七八九十百千万亿零〇幺点".codePoints().toArray().toSet()

        /**
         * Idioms said with number characters that are no number (「十全十美」): here, not the pinyin dictionary,
         * which the voice path cannot reach. Four characters and more, so 「三个一心一意的人」 still has its number.
         * Each once (VoicePolishTest checks).
         */
        val IDIOMS: List<String> = """
            十全十美 一心一意 七上八下 三心二意 一五一十 四面八方 五颜六色 乱七八糟 一模一样 万无一失 一干二净
            九牛一毛 千方百计 千言万语 三言两语 一举两得 一石二鸟 独一无二 接二连三 三番五次 七嘴八舌 横七竖八
            五花八门 六神无主 百发百中 千军万马 千变万化 千家万户 万紫千红 十拿九稳 九死一生 一清二楚 不三不四
            丢三落四 颠三倒四 低三下四 说三道四 朝三暮四 四分五裂 七零八落 八九不离十 百里挑一 一日千里 一刀两断
            三长两短 一穷二白 一差二错 一来二去 两全其美 一分为二 一时半刻 三三两两 七七八八 千千万万 万万千千
            五湖四海 三头六臂 四平八稳 一言九鼎 一字千金 一诺千金 千篇一律 万众一心 一呼百应 举一反三
            一劳永逸 一本正经 一帆风顺 一路顺风 二话不说 三思而行 十字路口 九九归一 八仙过海 七十二变
            三更半夜 五光十色 十有八九 十万八千里 一千零一夜
        """.trim().split(Regex("\\s+"))

        private val IDIOM_CODES = IDIOMS.map { it.codePoints().toArray() }

        /** How far back of a stream window idioms are looked for. */
        internal val IDIOM_LONGEST = IDIOM_CODES.maxOf { it.size }

        private fun idioms(text: IntArray): List<IntRange> {
            val found = ArrayList<IntRange>()
            for (idiom in IDIOM_CODES) for (i in 0..text.size - idiom.size) {
                if (idiom.indices.all { text[i + it] == idiom[it] }) found += i until i + idiom.size
            }
            return found
        }

        private val PAUSE_BEFORE_MARK = Regex("[${VoiceMarks.PAUSE_BEFORE}]+(?=[。？！…，])")

        // a mark English wrote after an English letter, as English writes it: norm made it full width
        private fun kept(c: Int, run: CharSequence, out: CharSequence, english: Boolean): String {
            val afterLetter = (run.lastOrNull() ?: out.lastOrNull())?.let { it.code < ASCII && it.isLetter() } == true
            return if (english && afterLetter && c in WIDE_MARKS) ASCII_MARKS[WIDE_MARKS.indexOf(c)].toString()
            else String(Character.toChars(c))
        }

        private fun isAsciiWord(c: Int) = c < 0x80 && Character.isLetterOrDigit(c)

        // an English word, or its mark (「Yes, I」: the space after the comma kept)
        private fun endsAWord(out: StringBuilder) = out.isNotEmpty() && (isAsciiWord(out.last().code) || out.last() in ASCII_MARKS)

        /**
         * For each normed character, whether the raw text had a space before it between English words or after an
         * English word's mark: the tagger reads none. [norm] keeps every letter and digit, so the k-th matches.
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

        /** For each normed character, whether it was an ASCII mark: [norm] widens both, [apply] writes this one back. */
        fun asciiMarks(raw: String, normed: IntArray): BooleanArray {
            val wasAscii = ArrayList<Boolean>()
            raw.codePoints().forEach { c ->
                if (c in WIDE_MARKS || (c < ASCII && c.toChar() in ASCII_MARKS)) wasAscii += c < ASCII
            }
            var k = 0
            return BooleanArray(normed.size) { i -> normed[i] in WIDE_MARKS && wasAscii.getOrElse(k++) { false } }
        }

        private fun isSpace(c: Int) = c == 0x85 || (Character.isBmpCodePoint(c) && c.toChar().isWhitespace())

        /** As the tagger reads it: no spaces, title marks or ·, 「……」 and ... as one 「…」, ASCII marks full width. */
        fun norm(text: String): IntArray {
            val s = StringBuilder()
            for (c in text) if (!c.isWhitespace() && c !in DROPPED) s.append(c)
            val t = s.toString().replace("……", "…").replace("...", "…")
                .replace(',', '，').replace('?', '？').replace('!', '！').replace(':', '：').replace(';', '；')
            return t.codePoints().toArray()
        }
    }
}

/** The model's I/O (voice-eval's `export_onnx.py`): ids `[CLS] text [SEP]`, logits a label each. */
class VoicePolishVocab(vocab: List<String>, private val cls: Long, private val sep: Long, private val unk: Long) {

    private val ids = HashMap<Int, Long>(vocab.size * 2).apply {
        vocab.forEachIndexed { i, ch -> if (ch.codePointCount(0, ch.length) == 1) putIfAbsent(ch.codePointAt(0), i.toLong()) }
    }

    fun encode(text: IntArray): LongArray =
        LongArray(text.size + 2) { i ->
            when (i) {
                0 -> cls
                text.size + 1 -> sep
                else -> ids[text[i - 1]] ?: unk
            }
        }

    companion object {
        /** Each of [n] rows' best label of [logits]. */
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
 * The tagger over streaming text: tagged on the last [window] characters, a character's labels frozen once
 * [freezeAfter] follow it or it leaves the window. Where the recogniser rewrites text, labels from the first
 * difference on are dropped, frozen ones too.
 */
class VoicePolishStream(
    private val tags: VoicePolishTags,
    private val tagger: Tagger,
    private val freezeAfter: Int = FREEZE_AFTER,
    private val window: Int = WINDOW,
    /** False only for voice-eval's goldens and `ime-eval voice-post --guard off`. */
    private val guard: Boolean = true,
) {
    fun interface Tagger {
        fun labels(text: IntArray): Labels
    }

    class Labels(val op: IntArray, val after: IntArray, val num: IntArray = IntArray(op.size))

    /** The frozen part polished, then the rest as recognised (a pause's mark before a mark in the rest goes only at [finish]). */
    data class Shown(val polished: String, val rest: String)

    init {
        require(freezeAfter in 0 until window) { "freezeAfter $freezeAfter, window $window" }
    }

    private var text = IntArray(0)
    private var spaced = BooleanArray(0)
    private var ascii = BooleanArray(0)

    private var done = 0
    private var seenOp = IntArray(0)
    private var seenAfter = IntArray(0)
    private var seenNum = IntArray(0)

    private var frozen = 0

    fun update(raw: String): Shown {
        take(raw)
        // a step at most window - freezeAfter long: what it pushes out was tagged with freezeAfter after it
        while (done < text.size) step(minOf(text.size, done + window - freezeAfter), last = false)
        return shown()
    }

    fun finish(raw: String): String {
        take(raw)
        if (text.isEmpty()) return ""
        while (text.size - done > window - freezeAfter) step(done + window - freezeAfter, last = false)
        step(text.size, last = true)
        return tags.apply(text, seenOp.copyOf(text.size), seenAfter.copyOf(text.size), spaced, seenNum.copyOf(text.size), ascii)
    }

    /** No more than are frozen; a pause's mark at its very end may still go ([VoicePolishTags.apply]). */
    fun frozenPrefix(count: Int): String {
        val n = minOf(count, frozen)
        return tags.apply(text.copyOf(n), seenOp.copyOf(n), seenAfter.copyOf(n), spaced.copyOf(n), seenNum.copyOf(n), ascii.copyOf(n))
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
        val wasAscii = ascii
        spaced = VoicePolishTags.spaces(raw, next)
        ascii = VoicePolishTags.asciiMarks(raw, next)
        if (shownCut > frozen || (0 until shownCut).any { spaced[it] != wasSpaced[it] || ascii[it] != wasAscii[it] }) shownCut = -1
        if (seenOp.size < text.size) {
            seenOp = seenOp.copyOf(text.size)
            seenAfter = seenAfter.copyOf(text.size)
            seenNum = seenNum.copyOf(text.size)
        }
    }

    private fun step(end: Int, last: Boolean) {
        val start = maxOf(0, end - window)
        frozen = maxOf(frozen, start)
        val part = text.copyOfRange(start, end)
        val labels = tagger.labels(part)
        val op = labels.op
        val after = labels.after
        val num = labels.num
        check(op.size == end - start && after.size == end - start && num.size == end - start) { "a label each of ${end - start} characters" }
        if (guard) tags.guarded(part, op, after, num, text.copyOfRange(maxOf(0, start - (VoicePolishTags.IDIOM_LONGEST - 1)), start))
        op.copyInto(seenOp, frozen, frozen - start, op.size)
        after.copyInto(seenAfter, frozen, frozen - start, after.size)
        num.copyInto(seenNum, frozen, frozen - start, num.size)
        frozen = maxOf(frozen, if (last) end else end - freezeAfter)
        done = end
    }

    // polished again only when the frozen prefix changed
    private var shownCut = -1
    private var shownPolished = ""

    private fun shown(): Shown {
        // a number going on past the frozen end shows whole in the rest, not half written (二零二 202 then 六)
        var cut = frozen
        if (cut < done && seenNum[cut] != 0) while (cut > 0 && seenNum[cut - 1] != 0) cut--
        if (cut != shownCut) {
            shownPolished = tags.apply(text.copyOf(cut), seenOp.copyOf(cut), seenAfter.copyOf(cut), spaced, seenNum.copyOf(cut), ascii)
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
        const val FREEZE_AFTER = 16

        const val WINDOW = 64
    }
}
