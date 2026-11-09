/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Detected
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Reason
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Refused
import org.junit.Assert.assertEquals
import org.junit.Test

/** A GBK file's words alone held to the guess only where they are its most. */
class DictionaryFormatAloneTest {

    private val words = listOf("你好" to "ni'hao", "幽默" to "you'mo", "输入法" to "shu'ru'fa", "词库" to "ci'ku", "拼音" to "pin'yin")

    private val readsNone: (List<String>) -> Map<String, String?> = { texts -> texts.associateWith { null } }

    // a GBK export with pinyin and a line or two the dictionary cannot read (a rare name): not refused for them
    @Test
    fun aFewWordsAloneInAGbkFileWithPinyinRefuseNothing() {
        val lines = List(20) { words }.flatten().map { (t, p) -> "$t $p" } + "幽默输入"
        val bytes = lines.joinToString("\n", postfix = "\n").toByteArray(WordLists.GB18030)
        val read = DictionaryFormat.analyse({ bytes.inputStream() }, guess = readsNone)
        assertEquals(1, (read as Detected).unguessed)
        // the words alone the file's most: held to the guess as before
        val most = (List(30) { "幽默输入" } + "你好 ni'hao").joinToString("\n", postfix = "\n").toByteArray(WordLists.GB18030)
        assertEquals(Reason.MISMATCH, (DictionaryFormat.analyse({ most.inputStream() }, guess = readsNone) as Refused).reason)
    }
}
