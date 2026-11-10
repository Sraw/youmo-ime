/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** 候选词注音 of polyphones, with the app's data (A5): one reading above each, toned, as its word has it. */
class PolyphoneReadingUatTest {

    // a polyphone found by its strokes or parts: one reading above it, toned, none beside, the one it has
    // after what was picked before it, else its commonest; typed, each in a word as the word has it (A5,
    // the user, 2026-11-06: 了 and 能 had le/liao, neng/nai beside them)
    @Test
    @Uat("PY-111", "PY-118")
    fun aPolyphoneIsReadOnceAboveAsItsWordHasIt() {
        for ((style, want) in listOf(
            ReadingStyle.PINYIN to listOf("le", "néng", "xíng", "xíng", "háng"),
            ReadingStyle.OFF to listOf("le", "néng", "xíng", "xíng", "háng"),
            ReadingStyle.ZHUYIN to listOf("˙ㄌㄜ", "ㄋㄥˊ", "ㄒㄧㄥˊ", "ㄒㄧㄥˊ", "ㄏㄤˊ"),
        )) {
            with(EngineReplay(settings(EngineSettings(candidateReading = style)))) {
                for ((case, reading) in listOf("uz" to "了", "uz" to "能", "uppshhs" to "行", "uchichu" to "行").zip(want)) {
                    val (keys, char) = case
                    type(keys)
                    expectAmong(char)
                    assertEquals("$style $keys", reading, readingOf(char))
                    assertEquals("$style $keys", "", hintOf(char))
                    backspace(keys.length)
                }
                type("yinuchichu")
                pick("银")
                expectAmong("行")
                assertEquals("$style 银 then 行", want.last(), readingOf("行"))
                assertEquals("", hintOf("行"))
            }
        }
        with(EngineReplay(settings(EngineSettings(candidateReading = ReadingStyle.PINYIN)))) {
            for ((keys, word, reading) in listOf(
                Triple("haole", "好了", "hǎo le"), Triple("liaojie", "了解", "liǎo jiě"), Triple("neng", "能", "néng"),
                Triple("yinhang", "银行", "yín háng"), Triple("xingzou", "行走", "xíng zǒu"),
            )) {
                type(keys)
                expectAmong(word)
                assertEquals(word, reading, readingOf(word))
                backspace(keys.length)
            }
        }
    }
}
