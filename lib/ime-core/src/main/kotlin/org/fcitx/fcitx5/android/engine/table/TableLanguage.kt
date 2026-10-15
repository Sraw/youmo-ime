/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * What a table shares with the other Chinese input methods (specs/engine.md, "What every Chinese
 * input method has"): [words], the word model with the user's words mixed in, ranks a code's 重码
 * after the text before; [user], the one user model, learns each word put in by its [reading] (the
 * syllables a word of the model's is read by, as the user typed it if they did); [predictor] offers
 * what may follow (联想); [reranker] and [refiner] are the 性能档位's sentence models.
 */
class TableLanguage(
    val words: TextWords,
    val user: UserModel? = null,
    val reading: (String) -> IntArray? = { null },
    val predictor: Predictor? = null,
    val reranker: SentencePicker? = null,
    val refiner: SentenceRefiner? = null,
)
