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
    /** A letter of a Latin word typed in lower case (`iphone` for iPhone), a letter at a time. */
    val letter: Float = 0f,
    /**
     * A syllable of a nasal alone (m, n, ng: 呣, 嗯) read whole. Hardly anyone types 嗯 as ng
     * (en, rather), so `ng` read so put 嗯 before 那个 of n'g; at the cost of two initials the
     * model decides between them.
     */
    val nasal: Float = -2f,
    /**
     * A word only imported (U-101): the model's unknown word, and this below it, so it stays after
     * the dictionary's words read the same, a rare or coarse one too, where the ranking does not
     * place it (a glide, ends but the whole and the first word's; review-G S2).
     */
    val imported: Float = -2f,
    /**
     * A grouped key's letter read where the tap fell on its other letter's side, at the far edge:
     * scaled by how far past the centre ([SyllableGraph.against][org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.against]),
     * 0 at the centre, so a tap there reads as no place at all. No dead zone (user, 2026-10-25).
     * Simulated taps (`ime-eval pinyin --taps`), the 14 keys' `--same` sets, both sentence models:
     * α was first swept over 0.5, 1, 1.5 and 2 on the held-out halves (scratchpad/tap/grid.sh), and
     * the user chose 1 from that; the rule to choose it on the tune halves was written afterwards,
     * as a check: the best mean of four informed hands (σ 0.2, σ 0.3, shifted ±0.15) among those
     * costing a hand tapping near the centre, unaware of this, at most 1 point. It gives 1 again
     * (1.5 costs that hand 2.2 points). Held-out at 1 with this code, chat (983): 61.3% untapped,
     * 69.0% taps on their letters' centres, 67.3% σ 0.2, 65.9% σ 0.3, 59.9% near the centre;
     * pinyin.tsv (203): 95.6, 96.6, 96.1, 95.6, 95.1. Simulated hands, no real touches: for choosing
     * α only.
     */
    val tap: Float = -1f,
)
