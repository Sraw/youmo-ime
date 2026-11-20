/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.nio.ByteBuffer

/** [s]'s syllable ids. */
fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

/** A dictionary and model of [words]: each its text, its syllables apart by spaces, and its score (a text at two readings: its first's, one unigram). */
fun pinyinDataOf(vararg words: Triple<String, String, Float>) = PinyinData.load(
    ByteBuffer.wrap(
        PinyinDataBuilder().unigram("<unk>", -7f, 0f)
            .apply {
                val texts = HashSet<String>()
                words.forEach { (w, reading, score) ->
                    if (texts.add(w)) unigram(w, score, 0f)
                    entry(w, reading.split(' ').map(Syllables::id).toIntArray())
                }
            }
            .build().toByteArray(),
    ),
)
