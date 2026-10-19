/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.nio.ByteBuffer

internal fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

/** The dictionary and model the pinyin session tests read. */
internal fun sessionTestData() = PinyinData.load(
    ByteBuffer.wrap(
        PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("你", -2f, 0f)
            .unigram("好", -2.5f, 0f)
            .unigram("你好", -3f, 0f)
            .unigram("吗", -3.5f, 0f)
            .unigram("拟", -5f, 0f)
            .unigram("在", -2f, 0f)
            .unigram("再", -2.5f, 0f)
            .unigram("见", -3f, 0f)
            .unigram("我", -2f, -1f)
            .unigram("西安", -4f, 0f)
            .unigram("先", -3f, 0f)
            .unigram("中", -3.5f, 0f)
            // a word of the built-in phrases (PhraseBook.WORD_KEYS)
            .unigram("日期", -4f, 0f)
            .bigram("你", "好", -0.75f, 0f)
            .bigram("你好", "吗", -0.5f, 0f)
            .bigram("再", "见", -0.5f, 0f)
            .bigram("我", "再", -0.5f, 0f)
            .bigram("拟", "再", -0.1f, 0f)
            .entry("日期", syl("ri", "qi"))
            .entry("你", syl("ni"))
            .entry("拟", syl("ni"))
            .entry("好", syl("hao"))
            .entry("你好", syl("ni", "hao"))
            .entry("吗", syl("ma"))
            .entry("在", syl("zai"))
            .entry("再", syl("zai"))
            .entry("见", syl("jian"))
            .entry("我", syl("wo"))
            .entry("西安", syl("xi", "an"))
            .entry("先", syl("xian"))
            .entry("中", syl("zhong"))
            .build().toByteArray(),
    ),
)
