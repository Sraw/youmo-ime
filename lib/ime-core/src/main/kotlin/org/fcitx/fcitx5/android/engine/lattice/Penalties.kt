/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

/**
 * What reading input other than as whole standard syllables costs, in log10 like the language
 * model's scores, so each is how much less likely such a reading is taken to be. Each applies
 * per syllable. Tuned on the evaluation set (`lib/ime-eval`); [fuzzy] and [typo] are guesses
 * until it has such input.
 */
data class Penalties(
    /** A [Fuzzy][org.fcitx.fcitx5.android.engine.pinyin.Fuzzy] partner of what was typed. */
    val fuzzy: Float = -1f,
    /** A common slip: `zhagn` for zhang. */
    val typo: Float = -1.5f,
    /**
     * An initial standing for its syllable (简拼): `n` in `nh`. Accuracy barely depends on it, as
     * abbreviated readings mostly compete among themselves; a larger one prunes more.
     */
    val initial: Float = -1f,
    /** A syllable still being typed at the end: `zho` for zhong. */
    val partial: Float = -1f,
    /** Input kept as typed, being no pinyin at all. */
    val raw: Float = -10f,
)
