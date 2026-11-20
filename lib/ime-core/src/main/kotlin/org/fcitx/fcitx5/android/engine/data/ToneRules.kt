/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.ToneMark

/**
 * Tones by rule for words no curated source reads: the tones of the longest listed word it ends with (个人爱好 as
 * 爱好), 为 as wéi at the end but in 因为; then ([full]) a neutral syllable takes its character's usual tone
 * (桌子 zhuō zǐ) unless that is neutral too (的, 吗).
 */
object ToneRules {

    /** [own]: the tones a source gave, or null; [usual]: its characters' usual tones; [listed]: another word's listed tones. */
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
        val from = (1..n - 2).firstOrNull { listed(String(cps, it, n - it), reading.subList(it, n)) != null }
        if (from != null) listed(String(cps, from, n - from), reading.subList(from, n))!!.copyInto(out, from)
        if (cps.last() == '为'.code && reading.last() == WEI && word != "因为") out[n - 1] = 2
        return out
    }

    /** Each neutral tone replaced by [usual] where that is known and not neutral. */
    fun full(tones: ByteArray, usual: ByteArray): ByteArray = ByteArray(tones.size) { i ->
        if (tones[i] == NEUTRAL && usual[i] in 1 until NEUTRAL) usual[i] else tones[i]
    }

    private val WEI = Syllables.id("wei")

    private const val NEUTRAL = ToneMark.NEUTRAL.toByte()
}
