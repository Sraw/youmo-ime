/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.ToneMark

/**
 * The tones of a dictionary word no curated source (`lexicon/tones.tsv`, the misreadings) reads, by rule
 * rather than a word at a time.
 *
 * 1. A word no source lists takes the tones of the longest word it ends with that one does
 *    (个人爱好 as 爱好 ài hào).
 * 2. A word no source lists ending in 为 has it wéi (沦为, 视为), but 因为.
 *
 * Then, for every word ([full]), curated or not: no neutral tone. A syllable a source reads neutral
 * takes its character's usual tone (东西 dōng xī, 桌子 zhuō zǐ): the user's choice (2026-11-02), every
 * reading in its characters' own tones. A character whose usual tone is the neutral one (的, 吗, 们)
 * has no other to take, and keeps it.
 */
object ToneRules {

    /**
     * [word] (its code points [cps]) read as [reading]: [own] the tones a source gave it, null if none did;
     * [usual] its characters' usual tones; [listed] another word's listed tones for a reading, if any.
     */
    fun tones(
        word: String,
        cps: IntArray,
        reading: List<Int>,
        own: ByteArray?,
        usual: ByteArray,
        listed: (String, List<Int>) -> ByteArray?,
    ): ByteArray {
        val out = (own ?: usual).copyOf()
        val n = cps.size
        if (n < 2 || own != null) return out
        // the longest listed word this one ends with
        val from = (1..n - 2).firstOrNull { listed(String(cps, it, n - it), reading.subList(it, n)) != null }
        if (from != null) listed(String(cps, from, n - from), reading.subList(from, n))!!.copyInto(out, from)
        if (cps.last() == '为'.code && reading.last() == WEI && word != "因为") out[n - 1] = 2
        return out
    }

    /** [tones] with each neutral one its character's [usual] tone, where that is known and not neutral too. */
    fun full(tones: ByteArray, usual: ByteArray): ByteArray = ByteArray(tones.size) { i ->
        if (tones[i] == NEUTRAL && usual[i] in 1 until NEUTRAL) usual[i] else tones[i]
    }

    private val WEI = Syllables.id("wei")

    private const val NEUTRAL = ToneMark.NEUTRAL.toByte()
}
