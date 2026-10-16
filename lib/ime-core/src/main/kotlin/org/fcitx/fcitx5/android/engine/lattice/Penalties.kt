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
     * A run of a key pressed again (九键 连按) read as the letter it came to, not as that many keys.
     * ime-eval t9 sets, 2026-10-12, each repeat fast: 0 read 2.7 to 4 points fewer right than 连按
     * off, -2 as many (86.1, 51.2, 48.5 against 86.1, 51.5, 48.7); typed letter by letter, -2 read
     * more right than off (89.4, 58.8), -3 far fewer (80.1, 48.0). Again 2026-10-15 (`t9 --presses`,
     * pinyin/chat/dialog, the small model weighing), on chat: fast, -1 52.0, -2 53.1, -3 53.4, off
     * 53.7; letter by letter, -1 65.6, -2 61.2, -3 52.6. -2 is the compromise kept: 0.3 behind -3
     * on fast typing (连按 on by default: most type so), 4.4 behind -1 letter by letter.
     */
    val tapped: Float = -2f,
)
