/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.ReadingSource
import org.fcitx.fcitx5.android.engine.data.ToneSources
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.nio.ByteBuffer

/**
 * Pinyin data with a few characters' readings as Unihan's five fields list them (pinyin-data's text,
 * in the order the app's build reads them) and words reading them: 行's four, 和's many, 子 zi beside zǐ.
 */
object ReadingData {

    private val SOURCES = listOf(
        ReadingSource.MANDARIN to """
            U+884C: xíng  # 行
            U+597D: hǎo  # 好
            U+662F: shì  # 是
            U+5B50: zǐ  # 子
            U+7684: de  # 的
            U+4E00: yī  # 一
            U+548C: hé  # 和
            U+8363: róng  # 荣
            U+94F6: yín  # 银
            U+8D70: zǒu  # 走
            U+9053: dào  # 道
            U+6811: shù  # 树
            U+8FDB: jìn  # 进
            U+4EBB: rén  # 亻
            U+5934: tóu  # 头
            U+8FD8: hái  # 还
            U+6C5F: jiāng  # 江
            U+5DE5: gōng  # 工
            U+6C35: shuǐ  # 氵
            U+5F73: chì  # 彳
            U+4E8D: chù  # 亍
            U+4EEC: men  # 们
            U+7231: ài  # 爱
            U+4EBA: rén  # 人
            U+5E73: píng  # 平
            U+4E7E: qián  # 乾
            U+957F: cháng  # 长
            U+5927: dà  # 大
        """,
        ReadingSource.PINLU to """
            U+884C: xíng,háng  # 行
            U+597D: hǎo,hāo,hào  # 好
            U+5B50: zi,zǐ  # 子
            U+7684: de,dì,dí  # 的
            U+4E00: yī  # 一
            U+548C: hé,huo,he,hè  # 和
            U+5934: tóu,tou  # 头
            U+8FD8: hái,huán  # 还
            U+4EEC: men  # 们
            U+5DE5: gōng  # 工
        """,
        ReadingSource.XHC to """
            U+884C: háng,hàng,héng,xíng  # 行
            U+4E00: yī,yí,yì  # 一
            U+548C: hé,hè,hú,huó,huò  # 和
            U+5934: tóu,tou  # 头
            U+4E7E: gān,qián  # 乾
            U+957F: cháng,zhǎng  # 长
        """,
        ReadingSource.TGHZ to """
            U+884C: háng,héng,xíng  # 行
            U+597D: hǎo,hào  # 好
            U+5B50: zǐ  # 子
            U+7684: de,dī,dí,dì  # 的
            U+548C: hé,hè,hú,huó,huò,huo  # 和
            U+94F6: yín  # 银
            U+5934: tóu  # 头
            U+8FD8: hái,huán  # 还
            U+4EEC: mén,men  # 们
            U+4E7E: gān,qián  # 乾
        """,
        ReadingSource.HANYU_PINYIN to """
            U+884C: háng,xìng,xíng,hàng,héng  # 行
            U+4EBB: rén  # 亻
            U+8FD8: fú,huán,hái  # 还
        """,
    )

    private val PHRASES = """
        树行子: shù hàng zi
        爱好: ài hào
        进行: jìn xíng
        行走: xíng zǒu
        银行: yín háng
        银行: yín xíng
        道行: dào héng
        和平: hé píng
        还是: hái shì
    """.trimIndent()

    private fun syl(vararg s: String) = IntArray(s.size) { Syllables.id(s[it]) }

    /** The file, as the app's assets have it. */
    val bytes: ByteArray by lazy {
        val b = PinyinDataBuilder().unigram(PinyinDataBuilder.UNKNOWN, -7f, 0f)
        listOf(
            Triple("进行", "jin xing", -2.5f), Triple("银行", "yin hang", -2.8f), Triple("行走", "xing zou", -3f),
            Triple("道行", "dao heng", -4f), Triple("树行子", "shu hang zi", -5f), Triple("爱好", "ai hao", -3f),
            Triple("好人", "hao ren", -3.5f), Triple("和平", "he ping", -3f), Triple("行", "xing", -2f), Triple("和", "he", -2f),
            Triple("银", "yin", -3f), Triple("江", "jiang", -3f), Triple("还是", "hai shi", -3f),
            // the model's, not a dictionary's: two words run together, commoner than 和平 but no example
            Triple("荣和", "rong he", -2f),
            // the model's alone, yet what puts 长's zhǎng before cháng
            Triple("长大", "zhang da", -2f),
        ).forEach { (w, r, p) -> b.unigram(w, p, 0f).entry(w, syl(*r.split(' ').toTypedArray())) }
        // a rarer reading of the word, by the dictionary's weight: no example of it
        b.entry("行", syl("hang"), -1f).entry("行", syl("heng"), -3f).entry("银行", syl("yin", "xing"), -2f)
        // each character as the dictionary has it alone, which reads a word by its text (Predictor.reading)
        for ((c, r) in listOf("大" to "da", "荣" to "rong", "道" to "dao", "树" to "shu", "子" to "zi", "进" to "jin", "走" to "zou", "爱" to "ai", "人" to "ren", "平" to "ping", "好" to "hao")) {
            b.entry(c, syl(r))
        }
        for ((source, text) in SOURCES) {
            ToneSources.chars(text.trimIndent().lineSequence(), source.field) { cp, s, t ->
                b.charTone(cp, s, t)
                b.charReading(source, cp, s, t)
            }
        }
        ToneSources.phrases(PHRASES.lineSequence(), "phrases", b::wordTones)
        b.build().toByteArray()
    }

    val data: PinyinData by lazy { PinyinData.load(ByteBuffer.wrap(bytes)) }
}
