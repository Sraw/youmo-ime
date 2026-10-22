/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer

/** [PinyinSession] when a key it does not read ends the input. */
class PinyinSessionLeaveTest {

    // 你 and 好 alone: nihao reads only as 你好, whole
    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .entry("你", syl("ni"))
                .entry("好", syl("hao"))
                .build().toByteArray(),
        ),
    )

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun aKeyNotReadKeepsWhatTheFirstCandidateLeavesAsTyped() {
        // every reading of it all coarse, held past the first page: the first, 啊, reads only a
        // (a phrase no longer moves the decoder's of its text: impl/PLAN.md §2.7). The five are words of
        // lattice/coarse.txt (CoarseWords.LIST): one taken off that list puts its reading first here
        val held = listOf("屄", "骚屄", "嫩屄", "肉棒", "鸡巴").mapIndexed { i, w -> Triple(w, -0.1f * (i + 1), "bi") }
        val coarse = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                    .apply { (held + Triple("啊", -2f, "a")).forEach { (w, score, reading) -> unigram(w, score, 0f).entry(w, syl(reading)) } }
                    .build().toByteArray(),
            ),
        )
        val session = PinyinSession(coarse, PinyinSegmenter())
        assertEquals("啊", session.type("abi").candidates.first())
        val left = session.apply(Key(','))
        assertEquals("啊bi", left.commit)
        assertFalse(left.handled)
        assertEquals("", left.preedit)
    }
}
