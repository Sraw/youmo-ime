/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Simplifier
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/** What a table shares with the other Chinese input methods: the word model ranking 重码, the user model, 联想, and the sentence models. */
class TableLanguage(
    val words: TextWords,
    val user: UserModel? = null,
    val reading: (String) -> IntArray? = { null },
    val predictor: Predictor? = null,
    val reranker: SentencePicker? = null,
    val block: ((Entry) -> Unit)? = null,
    /** Told a forgotten prediction's text, for the tables' copy too: [user] forgets it by its reading alone. */
    val forgotten: (String) -> Unit = {},
    val emoji: (String) -> List<String> = { emptyList() },
    val phrases: (String) -> List<String> = { emptyList() },
    /** The record what is put in is written to, its words by text. */
    val habits: Habits = Habits.OFF,
    val kind: String = "",
    /** The texts a code shows no more: read whatever [habits] are, as blocks stay with 「用我的输入习惯」 off. */
    val blockedUnder: (Code) -> Set<String> = habits::blockedUnder,
    /** Whether a text has a character 生僻字屏蔽 holds back: never offered, nor counted among the 重码 a code commits alone. */
    val held: (String) -> Boolean = { false },
    /** Traditional to simplified: a traditional table's words are learned for 联想 simplified; codes stay the table's. */
    val simplify: (String) -> String = { it },
) {
    /** The word [text] is learned as: by its reading, or for the user's own ([theirs]) the reading they typed most; null: nothing learned. */
    fun entryOf(text: String, theirs: Boolean = false): Entry? =
        reading(text)?.let { Entry(text, it) } ?: user?.takeIf { theirs }?.newEntryOf(text)
}
