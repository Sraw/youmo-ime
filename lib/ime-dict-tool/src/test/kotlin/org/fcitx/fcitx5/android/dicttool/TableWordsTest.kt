/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class TableWordsTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    @Test
    fun theCommonestWordsGoFirstThePacksAmongThem() {
        val data = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("工作", -3f, 0f)
                    .unigram("内卷", -5f, 0f)
                    .unigram("工", -2f, 0f)
                    .unigram("A股", -4f, 0f)
                    .entry("工作", syl("gong", "zuo"))
                    .entry("内卷", syl("nei", "juan"))
                    .entry("工", syl("gong"))
                    // in the dictionary, not the model
                    .entry("功做", syl("gong", "zuo"))
                    .build()
                    .toByteArray(),
            ),
        )
        val pack = listOf("躺平" to -4.5f, "内卷" to -4f, "工" to -1f)
        // single characters and words not all Han left out; 内卷 by the pack's better score
        assertEquals(listOf("工作", "内卷", "躺平", "功做"), wordsByUse(pack, data))
        assertEquals(listOf("内卷", "躺平"), wordsByUse(pack, null))
    }
}
