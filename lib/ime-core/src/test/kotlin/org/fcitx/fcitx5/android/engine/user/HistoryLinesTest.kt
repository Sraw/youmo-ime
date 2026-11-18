/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.session.pinyinDataOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** [HistoryLines]: the step `ime-eval --history` and the device's EngineEvalRunner share (R27 F8532). */
class HistoryLinesTest {

    private val data = pinyinDataOf(
        Triple("行", "xing", -3f),
        Triple("行", "hang", -4f),
        Triple("好", "hao", -3f),
        Triple("你好", "ni hao", -3f),
    )

    @Test
    fun eachLineIsAWordCommittedWholeAtItsReadingOrAtEachTheDictionaryHas() {
        val rows = HistoryLines.rows(UserModel(data.dictionary, data.vocabulary), data, listOf("行", "", "你好\tni hao", "好\tni", "无", "好"))
        // 行 at both its readings; 好 read ni and 无 are none of the dictionary's words
        assertEquals(
            listOf(setOf(Word("行", "hang"), Word("行", "xing")), setOf(Word("你好", "ni hao")), setOf(Word("好", "hao"))),
            rows.groupBy { it.words.list.single().text }.values.map { group -> group.map { it.words.list.single() }.toSet() },
        )
        val row = rows.single { it.words.list.single().text == "你好" }
        assertEquals(Code(Kinds.FULL, "nihao"), row.code)
        assertEquals(Row.PICK or Row.WHOLE, row.flags)
        assertEquals(Row.DICTIONARY, row.origin)
    }
}
