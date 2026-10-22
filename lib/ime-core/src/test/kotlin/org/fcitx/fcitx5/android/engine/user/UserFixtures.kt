/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.nio.ByteBuffer

/** A dictionary of five words and user models over it, shared by the tests of the user's words and of the sessions. */
internal object UserFixtures {
    fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    val data: PinyinData = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("拟", -5f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("吗", -3.5f, 0f)
                .entry("你", syl("ni"))
                .entry("拟", syl("ni"))
                .entry("好", syl("hao"))
                .entry("你好", syl("ni", "hao"))
                .entry("吗", syl("ma"))
                .build().toByteArray(),
        ),
    )

    fun model() = UserModel(data.dictionary, data.vocabulary)

    fun entry(text: String, vararg syllables: String) = Entry(text, syl(*syllables))

    /** Whether the decoder finds [text] among [m]'s words, read as [syllables]. */
    fun inTrie(m: UserModel, text: String, vararg syllables: String): Boolean {
        var node = m.root
        for (s in syllables) node = m.child(node, Syllables.id(s)).also { if (it < 0) return false }
        return (0 until m.wordCount(node)).any { m.text(m.word(node, it)) == text }
    }
}
