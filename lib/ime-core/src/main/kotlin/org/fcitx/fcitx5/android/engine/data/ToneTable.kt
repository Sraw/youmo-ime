/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.ReadingTable
import org.fcitx.fcitx5.android.engine.reading.ToneMark
import java.nio.ByteBuffer

/**
 * The tones section of pinyin data ([PinyinDataBuilder.charTone], [PinyinDataBuilder.wordTones]):
 * for each character read as a syllable, its usual tone and every tone it may have; for the few
 * words whose tones are not their characters' usual ones (爱好 ài hào, 东西 dōng xi), those.
 *
 * A character's key is its code point times [KEY_SPAN] plus the syllable, sorted; a word's
 * exceptions are runs of syllables and tones, sorted by word id, a word having one a reading.
 */
class ToneTable internal constructor(
    private val charKeys: BitPacked,
    private val charTones: ByteBuffer,
    private val words: BitPacked,
    private val wordStart: BitPacked,
    private val syllables: BitPacked,
    private val tones: ByteBuffer,
) : ReadingTable {

    init {
        ensureFormat(charTones.capacity() == charKeys.size) { "tones disagree with their characters" }
        ensureFormat(wordStart.size == words.size + 1) { "tone exceptions disagree on their sizes" }
        wordStart.checkRuns("tone exceptions", 0, syllables.size)
        ensureFormat(tones.capacity() == syllables.size) { "exception tones disagree with their syllables" }
        syllables.checkBelow("exception syllables", Syllables.count)
        for (i in 0 until tones.capacity()) ensureFormat(tones.get(i) in 0..ToneMark.NEUTRAL) { "exception tone at $i" }
    }

    override val name: String get() = ReadingTable.MAINLAND

    private fun packed(codePoint: Int, syllable: Int): Int {
        if (codePoint !in 0 until MAX_CODE_POINT || syllable !in 0 until KEY_SPAN) return 0
        val key = codePoint * KEY_SPAN + syllable
        val at = charKeys.lowerBound(0, charKeys.size, key)
        return if (at < charKeys.size && charKeys[at] == key) charTones.get(at).toInt() and 0xff else 0
    }

    /** The usual tone of [codePoint] read as [syllable], or [ToneMark.NONE]. */
    fun usual(codePoint: Int, syllable: Int): Int = packed(codePoint, syllable) and USUAL_MASK

    override fun allows(codePoint: Int, syllable: Int, tone: Int): Boolean {
        val p = packed(codePoint, syllable)
        return p == 0 || tone in 1..ToneMark.NEUTRAL && p and (1 shl (tone - 1 + MASK_SHIFT)) != 0
    }

    override fun tones(text: String, syllables: IntArray, word: Int): ByteArray? {
        if (text.codePointCount(0, text.length) != syllables.size) return null
        if (word >= 0) exception(word, syllables)?.let { return it }
        val out = ByteArray(syllables.size)
        var i = 0
        var at = 0
        while (at < text.length) {
            val cp = text.codePointAt(at)
            out[i] = usual(cp, syllables[i]).toByte()
            i++
            at += Character.charCount(cp)
        }
        return out
    }

    private fun exception(word: Int, reading: IntArray): ByteArray? {
        var at = words.lowerBound(0, words.size, word)
        while (at < words.size && words[at] == word) {
            val from = wordStart[at]
            val to = wordStart[at + 1]
            if (to - from == reading.size && reading.indices.all { syllables[from + it] == reading[it] }) {
                return ByteArray(reading.size) { tones.get(from + it) }
            }
            at++
        }
        return null
    }

    /** How many characters and word exceptions it has: what the compile reports. */
    val characters: Int get() = charKeys.size
    val exceptions: Int get() = words.size

    companion object {
        // a syllable id below this, as Syllables.count is; a code point below MAX_CODE_POINT, which
        // covers CJK up to Extension H, so a key fits an Int
        internal const val KEY_SPAN = 512
        internal const val MAX_CODE_POINT = 0x40000

        // a character's byte: the usual tone in the low three bits, then one bit for each tone 1–5
        internal const val USUAL_MASK = 7
        internal const val MASK_SHIFT = 3
    }
}
