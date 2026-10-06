/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter

/**
 * Types the full-pinyin samples of an evaluation set on the nine keys (九键), as [ShuangpinSet]
 * does in 双拼: the [ExactReading] spelt in digits, no separators, as a phone's user types it.
 * Samples with a Latin letter are left out: the nine keys type it in another mode.
 */
object T9Set {

    /** The segmenters measured: `t9` reads 简拼 where no longer syllable starts, `t9-strict` only where nothing else is read. */
    val SCHEMES = setOf("t9", "t9-strict")

    fun convert(samples: List<Sample>): List<Sample> = samples.mapNotNull { sample ->
        val reading = ExactReading.of(sample) { !Syllables.spelling(it)[0].isUpperCase() } ?: return@mapNotNull null
        sample.copy(input = reading.joinToString("") { T9Segmenter.digits(Syllables.spelling(it.syllable)) })
    }
}
