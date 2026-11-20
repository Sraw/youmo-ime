/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/** A dictionary of five words and user models over it, shared by the tests of the user's words and of the sessions. */
internal object UserFixtures {
    fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    val data: PinyinData = pinyinDataOf(
        Triple("你", "ni", -2f),
        Triple("拟", "ni", -5f),
        Triple("好", "hao", -2.5f),
        Triple("你好", "ni hao", -3f),
        Triple("吗", "ma", -3.5f),
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
