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

/** A reading of a character: a [syllable] (an id of [Syllables]) in a [tone] (1–4, [ToneMark.NEUTRAL]). */
data class Reading(val syllable: Int, val tone: Int) {
    /** In pinyin, its tone marked: háng. */
    val pinyin: String get() = ToneMark.pinyin(Syllables.spelling(syllable), tone)

    /** In zhuyin, its tone after: ㄏㄤˊ; empty for a syllable zhuyin does not write. */
    val zhuyin: String get() = Zhuyin.of(syllable)?.let { ToneMark.zhuyin(it, tone) }.orEmpty()
}

/**
 * A character's readings, as its card and handwriting's candidates list them (the order and which
 * ones: the compile's, `CharReadingsBuilder`), and the commonest words reading it each way (行 xíng
 * 行走 进行, háng 银行): pinyin data's sections, mapped where they lie.
 *
 * Unlike [ReadingTable], which tells the tone a character has read a way, this says which ways there are.
 * One table, the mainland's, as there; another would go by a name as [ReadingTable.name] does.
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

    /**
     * [codePoint]'s readings, the first the commonest; [card], the card's, the dictionaries' neutral tones
     * the candidates leave out (子 zi) after them; none for a code point with none (no Han character, or one no source reads).
     */
    fun of(codePoint: Int, card: Boolean = false): List<Reading> {
        val i = at(codePoint)
        if (i < 0) return emptyList()
        return (start[i] until start[i + 1]).mapNotNull { r ->
            val t = tones.get(r).toInt()
            if (!card && t and CARD_ONLY != 0) null else Reading(syllables[r], t and CARD_ONLY.inv())
        }
    }

    /** The commonest words reading [codePoint] as its [reading]th reading of the card's ([of]), at most two; none for a character of one reading. */
    fun examples(codePoint: Int, reading: Int): List<String> {
        val i = at(codePoint)
        if (i < 0 || reading !in 0 until start[i + 1] - start[i]) return emptyList()
        val r = start[i] + reading
        return (exampleStart[r] until exampleStart[r + 1]).map { vocabulary.word(examples[it]) }
    }

    /** The [index]th character it has readings for, by code point: for a walk of them all. */
    fun codePointAt(index: Int): Int = chars[index]

    /** How many characters it has readings for: what the compile reports. */
    val characters: Int get() = chars.size

    companion object {
        /** Set in a reading's tone byte: one only the card lists. */
        const val CARD_ONLY = 0x10
    }
}
