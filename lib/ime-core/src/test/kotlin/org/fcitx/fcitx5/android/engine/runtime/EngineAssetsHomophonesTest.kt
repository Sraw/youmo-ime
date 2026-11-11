/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.ReadingSource
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.runtime.UserWord.Kind.ADDED
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** EngineAssets' glue for VoiceHomophones: the readings, tones and counts it gives from pinyin data. */
class EngineAssetsHomophonesTest : EnginesFixture() {

    private val data = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        // log10, as COMMON_WORD (-5.5): 公式 common, 攻势 not
        .unigram("公式", -4.7f, 0f).entry("公式", syl("gong", "shi"))
        .unigram("攻势", -6f, 0f).entry("攻势", syl("gong", "shi"))
        .apply {
            for ((c, s, t) in listOf(
                Triple('徐', "xu", 2), Triple('许', "xu", 3), Triple('静', "jing", 4), Triple('公', "gong", 1),
                Triple('攻', "gong", 1), Triple('式', "shi", 4), Triple('势', "shi", 4), Triple('长', "chang", 2),
                Triple('长', "zhang", 3), Triple('掌', "zhang", 3),
            )) {
                charTone(c.code, Syllables.id(s), t)
                charReading(ReadingSource.MANDARIN, c.code, Syllables.id(s), t)
            }
            // 婧 with no tone in the tone table: its tone from its readings
            charReading(ReadingSource.MANDARIN, '婧'.code, Syllables.id("jing"), 4)
        }
        .build()
        .toByteArray()

    private val assets = EngineAssets({ if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(data) else load(it) }, { throw it }, null)

    private fun replaced(word: String, pinyin: String, text: String): String {
        val h = assets.voiceHomophones(listOf(UserWord(word, pinyin, ADDED)))
        var out: Result<String>? = null
        suspend { h.hold().replace(text) { assets.voiceHomophonesAllowed(it) } }.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    @Test
    fun `the readings, tones and counts pinyin data gives decide what is put in`() = assertRows(
        "a tone the tone table has not is the character's reading's" to { assertEquals("找徐婧", replaced("徐婧", "xu jing", "找徐静")) },
        "the tones must match: 许 is xu3" to { assertEquals("找许静", replaced("徐婧", "xu jing", "找许静")) },
        "a character of several readings matches by any" to { assertEquals("掌掌", replaced("掌掌", "zhang zhang", "长长")) },
        "a common word by the model's log10 count is left" to { assertEquals("这个公式", replaced("攻势", "gong shi", "这个公式")) },
        "a rare word of the dictionary read as the user's (攻势 gong1 shi4) is replaced" to { assertEquals("这个公式", replaced("公式", "gong shi", "这个攻势")) },
        "a word typed with a syllable it has no reading in takes no part" to { assertEquals("找徐静", replaced("徐婧", "xu qing", "找徐静")) },
    )
}
