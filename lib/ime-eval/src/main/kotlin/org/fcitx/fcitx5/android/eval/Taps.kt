/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.util.Random
import kotlin.math.roundToInt

/**
 * Taps on the 14 keys simulated for `pinyin --taps <hand>:<α>[:<seed>]`: each sample's pinyin
 * typed as the keys' codes, each with where across its key a [Hand] tapped it, as the keyboard
 * sends it (to the nearest of [Keyboard.TAP_STEPS]); α the cost of a letter at its key's far edge
 * (`Penalties.tap`, negated). A key of two letters has its left one's centre at 0.25 and its right
 * one's at 0.75. Seeded by a sample's place in the set read: every α of a hand, on the same set and
 * --half, has the same taps, compared sample by sample.
 */
object Taps {

    /** How a hand taps a letter whose centre on its key is `centre`, drawing from `random`. */
    enum class Hand(val label: String, val tap: (centre: Double, random: Random) -> Double) {
        /** On the letter's centre: the most there is to gain. */
        PERFECT("perfect", { centre, _ -> centre }),

        /** Around the letter's centre, σ 0.2 of the key (some 10.6% on the other half). */
        SIGMA02("sigma0.2", { centre, r -> centre + r.nextGaussian() * SIGMA_NARROW }),

        /** σ 0.3 (some 20% on the other half). */
        SIGMA03("sigma0.3", { centre, r -> centre + r.nextGaussian() * SIGMA_WIDE }),

        /** σ 0.2, every tap 0.15 of the key to the right: a thumb reaching across. */
        RIGHT015("right0.15", { centre, r -> centre + SHIFT + r.nextGaussian() * SIGMA_NARROW }),

        /** As [RIGHT015], to the left. */
        LEFT015("left0.15", { centre, r -> centre - SHIFT + r.nextGaussian() * SIGMA_NARROW }),

        /** σ 0.2, 0.1 to the right. */
        RIGHT01("right0.1", { centre, r -> centre + SHIFT_SMALL + r.nextGaussian() * SIGMA_NARROW }),

        /** Around the key's centre whatever the letter, σ 0.15: one who does not know of this, noise and no signal. */
        MIDDLE("middle", { _, r -> CENTRE + r.nextGaussian() * SIGMA_MIDDLE }),

        /** Anywhere on the key: one who never looks. */
        UNIFORM("uniform", { _, r -> r.nextDouble() }),
        ;

        companion object {
            fun of(label: String): Hand = entries.firstOrNull { it.label == label }
                ?: throw IllegalArgumentException("no hand $label: ${entries.joinToString { it.label }}")
        }
    }

    /** A [hand] tapping, its letters weighed by [alpha], drawn from [seed]. */
    data class Spec(val hand: Hand, val alpha: Float, val seed: Long = 1L)

    /** `<hand>:<α>[:<seed>]`. */
    fun parse(text: String): Spec {
        val parts = text.split(':')
        require(parts.size in 2..3) { "--taps <hand>:<alpha>[:<seed>], not $text" }
        val alpha = parts[1].toFloat()
        require(alpha.isFinite() && alpha >= 0) { "--taps: α a cost, finite and not negative, not ${parts[1]}" }
        return Spec(Hand.of(parts[0]), alpha, parts.getOrNull(2)?.toLong() ?: 1L)
    }

    private val KEYS = NineKeyLetters.G14

    /**
     * [samples] as typed on the 14 keys by [spec]'s hand: their input the keys' codes and the
     * place of each tap; those with no one exact reading, or a letter on no key, dropped, as
     * `t9 --keys 14` drops them.
     */
    fun convert(samples: List<Sample>, spec: Spec): List<Sample> = samples.mapIndexedNotNull { index, sample ->
        val reading = ExactReading.of(sample) { !Syllables.spelling(it)[0].isUpperCase() } ?: return@mapIndexedNotNull null
        val letters = reading.joinToString("") { Syllables.spelling(it.syllable) }
        // the keys as T9Set.convert presses them, so a weighted and an unweighted run type the same keys
        val codes = T9Set.press(letters, T9Set.Presses.ONCE, KEYS) ?: return@mapIndexedNotNull null
        val random = Random(spec.seed * SEED_STRIDE + index)
        val taps = letters.mapIndexed { i, c ->
            val group = KEYS.letters(codes[i])
            // a key of one letter (L, M) sends no place, as the keyboard does: drawn all the same, so
            // the draws of the rest stay the ones every run so far was measured with
            val at = placed(spec.hand.tap(if (group.length < 2) CENTRE else if (group.indexOf(c) == 0) LEFT else RIGHT, random).coerceIn(0.0, 1.0))
            if (group.length < 2) Float.NaN else at
        }
        sample.copy(input = codes, taps = taps)
    }

    // to the nearest of the places the keyboard sends (Keyboard.tapKey)
    private fun placed(at: Double): Float = (at * (Keyboard.TAP_STEPS - 1)).roundToInt().toFloat() / (Keyboard.TAP_STEPS - 1)

    private const val SEED_STRIDE = 1_000_003L
    private const val CENTRE = 0.5
    private const val LEFT = 0.25
    private const val RIGHT = 0.75
    private const val SIGMA_NARROW = 0.2
    private const val SIGMA_WIDE = 0.3
    private const val SIGMA_MIDDLE = 0.15
    private const val SHIFT = 0.15
    private const val SHIFT_SMALL = 0.1
}
