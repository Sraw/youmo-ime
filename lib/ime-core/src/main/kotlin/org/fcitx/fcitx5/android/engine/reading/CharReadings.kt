/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.data.BitPacked
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.data.ensureFormat
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.nio.ByteBuffer

/** [tone] is 1–4 or [ToneMark.NEUTRAL]. */
data class Reading(val syllable: Int, val tone: Int) {
    /** háng */
    val pinyin: String get() = ToneMark.pinyin(Syllables.spelling(syllable), tone)

    /** ㄏㄤˊ; empty for a syllable zhuyin does not write. */
    val zhuyin: String get() = Zhuyin.of(syllable)?.let { ToneMark.zhuyin(it, tone) }.orEmpty()
}

/**
 * A character's readings as its card and handwriting candidates list them (order and selection from the
 * compile, `CharReadingsBuilder`), with the commonest words per reading (行 xíng 行走 进行, háng 银行). Unlike
 * [ReadingTable] (which tone a reading has), this says which readings exist.
 */
class CharReadings internal constructor(
    private val chars: BitPacked,
    private val start: BitPacked,
    private val syllables: BitPacked,
    private val tones: ByteBuffer,
    private val exampleStart: BitPacked,
    private val examples: BitPacked,
    private val vocabulary: Vocabulary,
) {
    init {
        ensureFormat(start.size == chars.size + 1) { "character readings disagree with their characters" }
        start.checkRuns("character readings", 0, syllables.size)
        ensureFormat(tones.capacity() == syllables.size) { "reading tones disagree with their syllables" }
        syllables.checkBelow("reading syllables", Syllables.count)
        for (i in 0 until tones.capacity()) {
            val t = tones.get(i).toInt()
            ensureFormat(t and CARD_ONLY.inv() in 1..ToneMark.NEUTRAL) { "reading tone at $i" }
        }
        ensureFormat(exampleStart.size == syllables.size + 1) { "reading examples disagree with the readings" }
        exampleStart.checkRuns("reading examples", 0, examples.size)
        examples.checkBelow("reading examples", vocabulary.size)
    }

    private fun at(codePoint: Int): Int {
        val i = chars.lowerBound(0, chars.size, codePoint)
        return if (i < chars.size && chars[i] == codePoint) i else -1
    }

    /** Commonest first; with [card], the dictionaries' neutral tones the candidates omit (子 zi) come after. None for no reading. */
    fun of(codePoint: Int, card: Boolean = false): List<Reading> {
        val i = at(codePoint)
        if (i < 0) return emptyList()
        return (start[i] until start[i + 1]).mapNotNull { r ->
            val t = tones.get(r).toInt()
            if (!card && t and CARD_ONLY != 0) null else Reading(syllables[r], t and CARD_ONLY.inv())
        }
    }

    /** At most two; none for a character of one reading. */
    fun examples(codePoint: Int, reading: Int): List<String> {
        val i = at(codePoint)
        if (i < 0 || reading !in 0 until start[i + 1] - start[i]) return emptyList()
        val r = start[i] + reading
        return (exampleStart[r] until exampleStart[r + 1]).map { vocabulary.word(examples[it]) }
    }

    /** For a walk of them all, by code point. */
    fun codePointAt(index: Int): Int = chars[index]

    val characters: Int get() = chars.size

    companion object {
        /** In a reading's tone byte: only the card lists it. */
        const val CARD_ONLY = 0x10
    }
}
