/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.nio.ByteBuffer

private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

/**
 * The words the handwriting tests read: 谈 and 淡 alike to the pad, 谈话 a word; 人 likelier than
 * 山 alone, 山 after 高.
 */
internal val handwritingData: PinyinData by lazy {
    PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("人", -2f, 0f)
                .unigram("山", -3f, 0f)
                .unigram("高", -3f, -1f)
                .unigram("谈", -4f, 0f)
                .unigram("淡", -3.5f, 0f)
                .unigram("话", -3f, 0f)
                .unigram("谈话", -4.5f, 0f)
                .unigram("天", -2.5f, 0f)
                .bigram("高", "山", -0.2f, 0f)
                .entry("人", syl("ren"))
                .entry("山", syl("shan"))
                .entry("高", syl("gao"))
                .entry("谈", syl("tan"))
                .entry("淡", syl("dan"))
                .entry("话", syl("hua"))
                .entry("谈话", syl("tan", "hua"))
                .entry("天", syl("tian"))
                .build().toByteArray(),
        ),
    )
}

internal fun handwritingDecoder(
    user: UserWords? = null,
    scorer: WordScorer = WordScorer.of(handwritingData.model),
    coarse: CoarseWords = CoarseWords.NONE,
): HandwritingDecoder {
    val d = handwritingData
    val readings = Predictor.charReadingsOf(d.dictionary, d.vocabulary)
    return HandwritingDecoder(d.dictionary, d.vocabulary, d.wordIndex, scorer, { readings }, user, coarse)
}

/** A position's readings, best first, each [step] less likely than the one before. */
internal fun read(vararg texts: String, step: Double = 1.0) = InkReading(texts.toList(), DoubleArray(texts.size) { -step * it })

/** What [this] counted: each word and pair, as the user model has them. */
internal fun UserModel.learned(): Set<String> {
    val out = HashSet<String>()
    forEachCount({ e, _ -> out += "$e" }, { a, b, _ -> out += "$a $b" })
    return out
}
