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
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * What a table shares with the other Chinese input methods (specs/engine.md, "What every Chinese
 * input method has"): [words], the word model with the user's words mixed in, ranks a code's 重码
 * after the text before; [user], the one user model, learns each word put in as [entryOf] has it
 * (by its [reading], the syllables a word of the model's is read by, as the user typed it if they
 * did); [predictor] offers what may follow (联想); [reranker] and [refiner] are the 性能档位's
 * sentence models.
 */
class TableLanguage(
    val words: TextWords,
    val user: UserModel? = null,
    val reading: (String) -> IntArray? = { null },
    val predictor: Predictor? = null,
    val reranker: SentencePicker? = null,
    val refiner: SentenceRefiner? = null,
    /** Keeps a prediction the user blocked, blocked in [user] too; none offered without. */
    val block: ((Entry) -> Unit)? = null,
    /**
     * Told the text of a prediction forgotten, for the tables' copy of it to go too: what [user]
     * learned of it goes by its reading alone, as handwriting's, not every reading of the text.
     */
    val forgotten: (String) -> Unit = {},
    /** The emoji a word stands for, offered after its first 重码 (EmojiCandidates); none, the setting off. */
    val emoji: (String) -> List<String> = { emptyList() },
    /** The built-in phrases' texts a word stands for (PhraseBook.builtInsOf), placed as its emoji; none, the setting off. */
    val phrases: (String) -> List<String> = { emptyList() },
    /** The learning record what is put in is written to, under [kind] (Kinds.table): its words, by their text (impl/design-1b.md §4). */
    val habits: Habits = Habits.OFF,
    val kind: String = "",
    /**
     * The texts a code shows no more ([Habits.blockedUnder]): the record's, read whatever [habits]
     * are, as blocks stay with 「用我的输入习惯」 off, where the habits are none (U-93, C26).
     */
    val blockedUnder: (Code) -> Set<String> = habits::blockedUnder,
    /**
     * Traditional text to simplified ([Simplifier]): a traditional table's words are learned for 联想
     * as the predictor reads them, simplified (PLAN §2.1); its codes keep the table's own.
     */
    val simplify: (String) -> String = { it },
) {
    /**
     * The word [text] is learned as: by its [reading], or for one of the user's words ([theirs]: a
     * phrase saved here, a word of the other input methods) one they made, which the word model
     * lacks, as [user] has it, the reading they typed most. Null for none: nothing learned of it.
     */
    fun entryOf(text: String, theirs: Boolean = false): Entry? =
        reading(text)?.let { Entry(text, it) } ?: user?.takeIf { theirs }?.newEntryOf(text)
}
