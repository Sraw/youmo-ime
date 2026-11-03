/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.reading.ToneMark
import org.fcitx.fcitx5.android.engine.reading.Zhuyin
import java.io.File

/**
 * Types the full-pinyin samples of an evaluation set in zhuyin, as [T9Set] does on the nine keys:
 * the [ExactReading]'s symbols on 注音九键's groups or 大千's keys, as the session's input has them
 * ([ZhuyinKeys]), no separators; with [Tones], each syllable's tone after it, as one who types
 * tones does. Samples with a Latin letter are left out.
 */
object ZhuyinSet {

    /** The keyboards, `zhuyin --keys`, by the scheme a set typed on them is read with (`pinyin --scheme`). */
    val KEYS = mapOf("nine" to ZhuyinKeys.NINE, "full" to ZhuyinKeys.DACHEN)

    /** `pinyin --scheme` for a set typed on [KEYS]' keyboard of the same name. */
    val SCHEMES = mapOf("zy9" to ZhuyinKeys.NINE, "zy" to ZhuyinKeys.DACHEN)

    fun convert(samples: List<Sample>, keys: NineKeyLetters, tones: Tones? = null): List<Sample> = samples.mapNotNull { sample ->
        val reading = ExactReading.of(sample) { !Syllables.spelling(it)[0].isUpperCase() } ?: return@mapNotNull null
        val syllables = reading.map { it.syllable }.toIntArray()
        val toned = tones?.of(sample.expected, syllables)
        val input = StringBuilder()
        for ((k, syllable) in syllables.withIndex()) {
            val symbols = Zhuyin.of(syllable) ?: return@mapNotNull null
            for (c in symbols) input.append(if (keys == ZhuyinKeys.DACHEN) ZhuyinKeys.symbolCode(c) else keys.keysOf(c).firstOrNull() ?: return@mapNotNull null)
            toned?.get(k)?.takeIf { it > 0 }?.let { input.append(ZhuyinKeys.toneCode(it)) }
        }
        sample.copy(input = input.toString())
    }

    /**
     * Each character's tones read as a syllable, and each word's, from a local copy of the lists the
     * reading table is made of (mozillazg's pinyin-data `pinyin.txt` lines `U+4F60: nǐ  # 你`, its
     * phrase-pinyin-data `pinyin.txt` lines `你好: nǐ hǎo`). For measuring only: the app's own
     * table is compiled into its pinyin data.
     */
    class Tones(chars: File, phrases: File?) {
        // a character's syllable's tones, the most common first: code point, syllable → tones
        private val byChar = HashMap<Long, IntArray>()
        private val byPhrase = HashMap<String, List<Pair<Int, Int>>>()

        init {
            chars.forEachLine { line ->
                val body = line.substringBefore('#').trim()
                if (!body.startsWith("U+")) return@forEachLine
                val cp = body.substring(2).substringBefore(':').toInt(HEX)
                for (r in body.substringAfter(':').split(',')) {
                    val (spelling, tone) = ToneMark.parse(r.trim()) ?: (" " to 0)
                    val id = Syllables.id(spelling)
                    if (id < 0) continue
                    val k = key(cp, id)
                    byChar[k] = (byChar[k] ?: IntArray(0)).let { if (tone in it) it else it + tone }
                }
            }
            phrases?.forEachLine { line ->
                if (line.startsWith('#') || ':' !in line) return@forEachLine
                val text = line.substringBefore(':').trim()
                val readings = line.substringAfter(':').substringBefore('#').trim().split(' ').map { ToneMark.parse(it) ?: return@forEachLine }
                byPhrase[text] = readings.map { (s, t) -> (Syllables.id(s).takeIf { it >= 0 } ?: return@forEachLine) to t }
            }
        }

        /** The tone of each character of [text] read as [syllables] (1 to 5), 0 for one not known; null if they are not one a character. */
        fun of(text: String, syllables: IntArray): IntArray? {
            val cps = text.codePoints().toArray()
            if (cps.size != syllables.size) return null
            byPhrase[text]?.takeIf { p -> p.map { it.first } == syllables.toList() }?.let { p -> return IntArray(p.size) { p[it].second } }
            return IntArray(cps.size) { byChar[key(cps[it], syllables[it])]?.first() ?: 0 }
        }

        /** Whether [codePoint] read as [syllable] may have [tone]: true for one not known. */
        fun allows(codePoint: Int, syllable: Int, tone: Int): Boolean {
            val known = byChar[key(codePoint, syllable)] ?: return true
            // 轻声 is the reading's own tone too
            return tone in known || tone == ToneMark.NEUTRAL
        }

        private fun key(cp: Int, syllable: Int) = (cp.toLong() shl SYLLABLE_BITS) or syllable.toLong()

        companion object {
            private const val HEX = 16
            private const val SYLLABLE_BITS = 16

            /** `--tones <chars.txt>[,<phrases.txt>]`. */
            fun of(option: String): Tones = option.split(',').let { Tones(File(it[0]), it.getOrNull(1)?.let(::File)) }
        }
    }
}
