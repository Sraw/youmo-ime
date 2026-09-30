/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

/**
 * What reading input other than as whole standard syllables costs, in log10 like the language
 * model's scores, so each is how much less likely such a reading is taken to be. Each applies
 * per syllable. Tuned on the evaluation set (`lib/ime-eval`). [fuzzy] and [typo] were chosen by
 * `ime-eval tune` on generated slips: -1 is best for [fuzzy], and [typo] changes nothing from -0.5
 * to -4, a slip's reading competing only with reading its letters as 简拼.
 */
data class Penalties(
    /** A [Fuzzy][org.fcitx.fcitx5.android.engine.pinyin.Fuzzy] partner of what was typed. */
    val fuzzy: Float = -1f,
    /** A common slip: `zhagn` for zhang. */
    val typo: Float = -1.5f,
    /**
     * A letter slipped onto the key next to it: `nihap` for nihao. From -0.5 to -2 it reads as
     * many slips right and costs clean input nothing; the most of that, to prune the most.
     * Twice [initial], so where input has a vowel elsewhere, a consonant pair read as a slip
     * (`xh` for xu in `woxhni`) ties with the two initials (喜欢) and the model decides.
     */
    val neighbour: Float = -2f,
    /**
     * An initial standing for its syllable (简拼): `n` in `nh`. Accuracy barely depends on it, as
     * abbreviated readings mostly compete among themselves; a larger one prunes more.
     */
    val initial: Float = -1f,
    /** A syllable still being typed at the end: `zho` for zhong. */
    val partial: Float = -1f,
    /**
     * A whole syllable read as the start of a longer one: `xian` for xiang. Dearer than [partial],
     * as the user more likely meant what they typed: at -1 有点像 beat 有点咸 for `youdianxian`.
     * From -1.5 to -5 all read the same on the evaluation set (0.4 points more than -1 on it,
     * 1.3 on the one with text before), with keystrokes to reach each sample unchanged.
     */
    val extended: Float = -2f,
    /**
     * A word reached by a syllable still being typed, scored as the likelier longer word it starts
     * (咖 of `k` as 咖啡). At -0.5 the fewest keys: 0 and -0.25 read as many samples right and
     * need more keys, -1 fewer right.
     */
    val lookAhead: Float = -0.5f,
    /** Input kept as typed, being no pinyin at all. */
    val raw: Float = -10f,
)
