/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.glide.PinyinGlide
import org.fcitx.fcitx5.android.engine.glide.TableGlide
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** What the glide figures of the commits (and PinyinGlide.WEIGHTS) are counted by. */
class GlideRunTest {

    private val english = EnglishData.load(
        ByteBuffer.wrap(
            EnglishDataBuilder()
                .unigram(EnglishDataBuilder.UNKNOWN, -7f, 0f)
                .unigram(EnglishText.START, -9f, -0.5f)
                .unigram("the", -1.3f, -0.3f)
                .unigram("cat", -4.2f, 0f)
                .bigram(EnglishText.START, "the", -1.5f, 0f)
                .bigram("the", "cat", -1.5f, 0f)
                .build()
                .toByteArray(),
        ),
    )

    private val pinyin = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("你好", -3f, 0f)
                .entry("你", intArrayOf(Syllables.id("ni")))
                .entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
                .build().toByteArray(),
        ),
    )

    @Test
    fun cleanEnglishPathsAreReadAsTheirWordsAndOneKeyWordsAreNotGlided() {
        val r = GlideRun(english).run(listOf("the cat.", "a"), wobble = 0f, seed = 1, threads = 1)
        assertEquals(2, r.words)
        assertEquals(2, r.right)
        assertEquals(2, r.top3)
        assertEquals(1, r.skipped)
        assertEquals(2, r.steps.size)
    }

    @Test
    fun aCleanPinyinPathIsRightByItsSeparatedReading() {
        val r = PinyinGlideRun(pinyin).run(listOf("nihao" to "你好"), syllables = 0, wobble = 0f, seed = 1)
        assertEquals(1, r.words)
        assertEquals(1, r.right)
        assertEquals(1, r.top3)
    }

    @Test
    fun samplesWhoseSyllablesAreNotOneACharacterAreSkippedInEveryMode() {
        val samples = listOf("nihao" to "你好!", "nihao" to "你", "ni1" to "你")
        assertEquals(0, PinyinGlideRun(pinyin).run(samples, syllables = 0, wobble = 0f, seed = 1).words)
        val r = PinyinGlideRun(pinyin).run(samples, syllables = 2, wobble = 0f, seed = 1)
        assertEquals(0, r.words)
        assertEquals(3, r.samplesSkipped)
    }

    @Test
    fun readingsThatDifferOnlyInWhereTheSyllablesPartAreNotTheSame() {
        // fangan cut longest first is fang'an (方案); the dictionary knows only fan'gan (反感), the
        // same keys: a miss, not the hit comparing them without the separator made it
        val data = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("反感", -3f, 0f)
                    .entry("反感", intArrayOf(Syllables.id("fan"), Syllables.id("gan")))
                    .build().toByteArray(),
            ),
        )
        val r = PinyinGlideRun(data).run(listOf("fangan" to "方案"), syllables = 2, wobble = 0f, seed = 1)
        assertEquals(1, r.words)
        assertEquals(0, r.right)
        assertEquals(listOf("fang'an\t方案\tfan'gan:反感"), r.misses)
    }

    @Test
    fun aShuangpinGlideIsRightByItsUnseparatedKeys() {
        // 自然码: ni ni, hao hk
        val spelling = PinyinGlide.Spelling.shuangpin(ShuangpinScheme.ZIRANMA)
        val r = PinyinGlideRun(pinyin).run(listOf("nihao" to "你好"), syllables = 0, wobble = 0f, seed = 1, spelling = spelling)
        assertEquals(1, r.words)
        assertEquals(1, r.right)
    }

    @Test
    fun aWordWithAKeyTheLayoutLacksIsCountedAsLeftOut() {
        // 微软 types ing with ;, no key of the QWERTY glide's
        val spelling = PinyinGlide.Spelling.shuangpin(ShuangpinScheme.MICROSOFT)
        val r = PinyinGlideRun(pinyin).run(listOf("ming" to "明"), syllables = 1, wobble = 0f, seed = 1, spelling = spelling)
        assertEquals(0, r.words)
        assertEquals(1, r.skipped)
    }

    @Test
    fun aTablesCodeIsGlidedAsItsKeysTheApostropheOneToo() {
        // 二笔's ' is a key, no syllable mark: a code with it is left out on QWERTY, not glided without it
        val table = CodeTable.Builder()
            .header("键码", "abcdefghijklmnopqrstuvwxyz'")
            .header("码长", "4")
            .entry("w'qy", "你")
            .entry("vbg", "好")
            .build().toByteArray()
            .let { TableDictionary(CodeTable.load(ByteBuffer.wrap(it))) }
        val r = PinyinGlideRun(pinyin).run(
            listOf("ni" to "你", "hao" to "好"), syllables = 1, wobble = 0f, seed = 1,
            table = table to TableGlide(table, { -2f }),
        )
        assertEquals(1, r.words)
        assertEquals(1, r.right)
        assertEquals(1, r.skipped)
    }
}
