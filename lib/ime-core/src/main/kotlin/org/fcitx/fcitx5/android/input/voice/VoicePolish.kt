/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The polish tagger's labels (060-voice-tagger.md; voice-eval's `tags.py`, which this follows
 * character for character): each character of the recognised text gets an op, kept, deleted or
 * written as one of [ops]' replacements, and an after, nothing or one of [afters]' marks written
 * after it. [ops] and [afters] are the model's own lists (its `polish-tagger.json`), in its order.
 *
 * Characters are code points, as the model's vocabulary and Python's strings have them.
 */
class VoicePolishTags(val ops: List<String>, val afters: List<String>) {

    init {
        require(ops.getOrNull(KEEP) == "keep" && ops.getOrNull(DEL) == "del") { "ops start keep, del: $ops" }
        require(afters.firstOrNull() == "") { "afters start with nothing: $afters" }
    }

    /** What the op and after labels of each character of [text] (normed, [norm]) write. */
    fun apply(text: IntArray, op: IntArray, after: IntArray): String {
        require(op.size == text.size && after.size == text.size) { "a label each: ${text.size}, ${op.size}, ${after.size}" }
        val out = StringBuilder()
        for (i in text.indices) {
            when (op[i]) {
                KEEP -> out.appendCodePoint(text[i])
                DEL -> {}
                else -> out.append(ops[op[i]])
            }
            out.append(afters[after[i]])
        }
        // a pause's mark before another mark goes (。 written after a 、 the input had); 「……」 is one mark inside
        return PAUSE_BEFORE_MARK.replace(out, "").replace("…", "……")
    }

    companion object {
        const val KEEP = 0
        const val DEL = 1

        // U+0085 too, which Python's \s takes and Kotlin's isWhitespace does not
        private const val DROPPED = "《》·\u0085"

        private val PAUSE_BEFORE_MARK = Regex("[，、；：]+(?=[。？！…，])")

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
) {
    /** The op and after labels of [text] (code points), a character each; the model run. */
    fun interface Tagger {
        fun labels(text: IntArray): Pair<IntArray, IntArray>
    }

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

    // the length of the prefix last tagged, and each of its characters' labels as last tagged
    private var done = 0
    private var seenOp = IntArray(0)
    private var seenAfter = IntArray(0)

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
        return tags.apply(text, seenOp.copyOf(text.size), seenAfter.copyOf(text.size))
    }

    private fun take(raw: String) {
        val next = VoicePolishTags.norm(raw)
        var same = 0
        val most = minOf(text.size, next.size)
        while (same < most && text[same] == next[same]) same++
        done = minOf(done, same)
        frozen = minOf(frozen, same)
        text = next
        if (seenOp.size < text.size) {
            seenOp = seenOp.copyOf(text.size)
            seenAfter = seenAfter.copyOf(text.size)
        }
    }

    private fun step(end: Int, last: Boolean) {
        val start = maxOf(0, end - window)
        // left the window before freezeAfter characters followed: frozen as last tagged
        frozen = maxOf(frozen, start)
        val (op, after) = tagger.labels(text.copyOfRange(start, end))
        check(op.size == end - start && after.size == end - start) { "a label each of ${end - start} characters" }
        // a frozen character keeps its labels, though tagged again in the window
        op.copyInto(seenOp, frozen, frozen - start, op.size)
        after.copyInto(seenAfter, frozen, frozen - start, after.size)
        frozen = maxOf(frozen, if (last) end else end - freezeAfter)
        done = end
    }

    private fun shown(): Shown {
        val polished = tags.apply(text.copyOf(frozen), seenOp.copyOf(frozen), seenAfter.copyOf(frozen))
        return Shown(polished, String(text, frozen, text.size - frozen))
    }

    companion object {
        /** K: 16 characters after a character before its labels are kept (§4.1: no worse than tagging whole) */
        const val FREEZE_AFTER = 16

        /** the characters tagged at a time, which bounds a step's cost (§4.1: 64 tag as well as the whole prefix) */
        const val WINDOW = 64
    }
}
