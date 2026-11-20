/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

/**
 * Log10 costs, per syllable, of reading input other than as whole standard syllables. Tuned on
 * `lib/ime-eval`; [fuzzy] and [typo] by `ime-eval tune` on generated slips ([typo] barely matters,
 * competing only with 简拼).
 */
data class Penalties(
    val fuzzy: Float = -1f,
    /** A common slip: `zhagn` for zhang. */
    val typo: Float = -1.5f,
    /**
     * Extra cost of a key slipped onto its neighbour (`nihap`), on top of the tap-position cost
     * ([SyllableGraph.slipCost][org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.slipCost]).
     */
    val neighbour: Float = 0f,
    /** An initial standing for its syllable (简拼): `n` in `nh`. Barely affects accuracy; larger prunes more. */
    val initial: Float = -1f,
    /**
     * On top of [initial]: 双拼's key read as an initial alone where it and the next make a syllable
     * (简拼 mid-input, [SyllableMatches.ABBREV][org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.ABBREV]).
     * -2 is the least that leaves whole-syllable input reading as before.
     */
    val abbrev: Float = -2f,
    /** A syllable still being typed at the end: `zho` for zhong. */
    val partial: Float = -1f,
    /**
     * A whole syllable read as the start of a longer one: `xian` for xiang. Dearer than [partial], as the
     * user more likely meant what they typed (at -1 有点像 beat 有点咸).
     */
    val extended: Float = -2f,
    /** A word via a syllable still being typed, scored as the likelier longer word it starts (咖 of `k` as 咖啡). */
    val lookAhead: Float = -0.5f,
    /** Input kept as typed: no pinyin at all. */
    val raw: Float = -10f,
    /** A Latin word's letter typed in lower case (`iphone` for iPhone). */
    val letter: Float = 0f,
    /** A nasal alone (m, n, ng: 呣, 嗯) read whole: hardly anyone types 嗯 as ng, so this lets the model pick 那个 for n'g. */
    val nasal: Float = -2f,
    /**
     * An imported-only word: scored as the model's unknown word, this below it, so it stays after the
     * dictionary's words read the same where ranking does not place it.
     */
    val imported: Float = -2f,
    /**
     * A grouped key's letter read where the tap fell on its other letter's side, at the far edge; scaled by
     * the distance past the centre ([SyllableGraph.against][org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.against]),
     * 0 at the centre, so a central tap reads as no place. No dead zone. Chosen on simulated taps
     * (`ime-eval pinyin --taps`), not real touches.
     */
    val tap: Float = -1f,
    /** A character lacking the tone typed after it on the zhuyin keyboards: kept after those that have it (the data is sometimes wrong). */
    val tone: Float = -4f,
)
