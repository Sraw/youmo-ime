/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.rules.ScreenShape

/**
 * 键盘样式 (061-wide-keyboard-style): how the keyboard is shown on a shape of screen, 竖屏 and 横屏 each its own
 * (the user, 2026-11-14). One at a time: 0.6.0's three settings (split, one-handed, floating) lay over each other.
 */
enum class KeyboardStyle {
    /** 普通: across the screen, docked. */
    NORMAL,

    /** 分体: the letters in two halves, the other boards at a side ([SplitKeyboard]); split whatever the width. */
    SPLIT,

    /** 悬浮: the keyboard over the app, smaller. */
    FLOATING,

    /** 单手: the whole keyboard at a side ([SplitKeyboard.placement]), at the side remembered. */
    ONE_HANDED,
}

/**
 * What turning floating or one-handed off goes back to, kept with the style: 横屏 split, floating turned on, and
 * dragged back down is split again, not 普通. For [KeyboardStyle.NORMAL] and [KeyboardStyle.SPLIT] it is the style
 * itself; one-handed is over one of those two; floating over any of the four.
 */
enum class KeyboardStyleUnder {
    NORMAL,
    SPLIT,
    ONE_HANDED_OVER_NORMAL,
    ONE_HANDED_OVER_SPLIT,
}

/**
 * A shape's [style] and what is [under] it, always one of the 8 pairs that mean something ([of] makes any other
 * so). The moves are those of the settings page, the keyboard's toggles and the floating handle (§3.2).
 */
@ConsistentCopyVisibility
data class KeyboardStyleState private constructor(val style: KeyboardStyle, val under: KeyboardStyleUnder) {

    val floating: Boolean get() = style == KeyboardStyle.FLOATING
    val splits: Boolean get() = style == KeyboardStyle.SPLIT
    val oneHanded: Boolean get() = style == KeyboardStyle.ONE_HANDED

    /** Picked on the settings page: one-handed over what was under floating, floating over what there is. */
    fun set(to: KeyboardStyle): KeyboardStyleState = when (to) {
        KeyboardStyle.NORMAL, KeyboardStyle.SPLIT -> twoHanded(to)
        KeyboardStyle.ONE_HANDED -> when (style) {
            KeyboardStyle.NORMAL, KeyboardStyle.SPLIT -> KeyboardStyleState(to, underOf(style))
            KeyboardStyle.ONE_HANDED -> this
            KeyboardStyle.FLOATING -> KeyboardStyleState(to, underOf(twoHandedUnder()))
        }
        KeyboardStyle.FLOATING -> when (style) {
            KeyboardStyle.NORMAL, KeyboardStyle.SPLIT -> KeyboardStyleState(to, underOf(style))
            KeyboardStyle.ONE_HANDED -> KeyboardStyleState(to, oneHandedOver(under))
            KeyboardStyle.FLOATING -> this
        }
    }

    fun floatingOn(): KeyboardStyleState = set(KeyboardStyle.FLOATING)

    /** Docked again: what was under it, one-handed as it was. */
    fun floatingOff(): KeyboardStyleState = when {
        !floating -> this
        under == KeyboardStyleUnder.ONE_HANDED_OVER_NORMAL -> KeyboardStyleState(KeyboardStyle.ONE_HANDED, KeyboardStyleUnder.NORMAL)
        under == KeyboardStyleUnder.ONE_HANDED_OVER_SPLIT -> KeyboardStyleState(KeyboardStyle.ONE_HANDED, KeyboardStyleUnder.SPLIT)
        else -> twoHanded(styleOf(under))
    }

    fun oneHandedOn(): KeyboardStyleState = set(KeyboardStyle.ONE_HANDED)

    /** Both hands again: 普通 or split, as it was before. */
    fun oneHandedOff(): KeyboardStyleState = if (oneHanded) twoHanded(styleOf(under)) else this

    // the two-handed style one-handed goes back to, of what is under floating
    private fun twoHandedUnder(): KeyboardStyle = when (under) {
        KeyboardStyleUnder.NORMAL, KeyboardStyleUnder.ONE_HANDED_OVER_NORMAL -> KeyboardStyle.NORMAL
        KeyboardStyleUnder.SPLIT, KeyboardStyleUnder.ONE_HANDED_OVER_SPLIT -> KeyboardStyle.SPLIT
    }

    companion object {

        /**
         * The shape's state from what is stored, [style] and [under] each null where none is, as a new install's; a
         * pair that means nothing (a damaged file, a key alone) read as the style over [fallback], the shape's
         * two-handed default ([default]). Where no style is stored, [fallback] itself.
         */
        fun of(style: KeyboardStyle?, under: KeyboardStyleUnder?, fallback: KeyboardStyle): KeyboardStyleState {
            require(fallback == KeyboardStyle.NORMAL || fallback == KeyboardStyle.SPLIT) { "two-handed: $fallback" }
            return when (style ?: fallback) {
                KeyboardStyle.NORMAL -> twoHanded(KeyboardStyle.NORMAL)
                KeyboardStyle.SPLIT -> twoHanded(KeyboardStyle.SPLIT)
                KeyboardStyle.ONE_HANDED -> KeyboardStyleState(
                    KeyboardStyle.ONE_HANDED,
                    under?.takeIf { it == KeyboardStyleUnder.NORMAL || it == KeyboardStyleUnder.SPLIT } ?: underOf(fallback),
                )
                KeyboardStyle.FLOATING -> KeyboardStyleState(KeyboardStyle.FLOATING, under ?: underOf(fallback))
            }
        }

        /**
         * The two-handed style of a shape whose style is not stored: 竖屏 普通; 横屏 split where the screen is
         * [ScreenShape.WIDE_DP] wide or wider, as 0.6.0's 屏幕够宽时 was, else 普通 (the user, 2026-11-14: a phone
         * on its side under 600 dp, as Android 14 and before measure it beside a navigation bar of three buttons,
         * looks as it did).
         */
        fun default(landscape: Boolean, widthDp: Int): KeyboardStyle =
            if (landscape && widthDp >= ScreenShape.WIDE_DP) KeyboardStyle.SPLIT else KeyboardStyle.NORMAL

        private fun twoHanded(style: KeyboardStyle) = KeyboardStyleState(style, underOf(style))

        private fun underOf(style: KeyboardStyle): KeyboardStyleUnder = when (style) {
            KeyboardStyle.SPLIT -> KeyboardStyleUnder.SPLIT
            else -> KeyboardStyleUnder.NORMAL
        }

        private fun styleOf(under: KeyboardStyleUnder): KeyboardStyle = when (under) {
            KeyboardStyleUnder.SPLIT, KeyboardStyleUnder.ONE_HANDED_OVER_SPLIT -> KeyboardStyle.SPLIT
            else -> KeyboardStyle.NORMAL
        }

        // one-handed over [under] (普通 or split) as what floating keeps under it
        private fun oneHandedOver(under: KeyboardStyleUnder) =
            if (styleOf(under) == KeyboardStyle.SPLIT) KeyboardStyleUnder.ONE_HANDED_OVER_SPLIT else KeyboardStyleUnder.ONE_HANDED_OVER_NORMAL
    }
}

/**
 * 0.6.0's three settings as the two shapes' styles (§6.1): each shape as it showed in 0.6.0. A null is not
 * written, its default read: 横屏 under 屏幕够宽时 split from [ScreenShape.WIDE_DP], 普通 below, as 0.6.0.
 */
object KeyboardStyleMigration {

    /** 0.6.0's 分体键盘. */
    enum class Split060 { AUTO, ALWAYS, OFF }

    /** What a shape is written: [style] and [under], each null where nothing is. */
    data class Stored(val style: KeyboardStyle?, val under: KeyboardStyleUnder?)

    data class Migrated(val portrait: Stored, val landscape: Stored)

    fun from060(split: Split060, oneHanded: Boolean, floating: Boolean): Migrated {
        // 竖屏 is under 600 dp: 屏幕够宽时 did not split there
        val portrait = docked(split == Split060.ALWAYS, oneHanded)
        val landscape = when (split) {
            Split060.AUTO -> null
            Split060.ALWAYS -> docked(true, oneHanded)
            Split060.OFF -> docked(false, oneHanded)
        }
        return Migrated(floated(portrait, floating), landscape?.let { floated(it, floating) } ?: Stored(if (floating) KeyboardStyle.FLOATING else null, null))
    }

    // split held one-handed down in 0.6.0
    private fun docked(split: Boolean, oneHanded: Boolean): Stored = when {
        split -> Stored(KeyboardStyle.SPLIT, KeyboardStyleUnder.SPLIT)
        oneHanded -> Stored(KeyboardStyle.ONE_HANDED, KeyboardStyleUnder.NORMAL)
        else -> Stored(KeyboardStyle.NORMAL, KeyboardStyleUnder.NORMAL)
    }

    private fun floated(docked: Stored, floating: Boolean): Stored {
        if (!floating) return docked
        val under = when (docked.style) {
            KeyboardStyle.SPLIT -> KeyboardStyleUnder.SPLIT
            KeyboardStyle.ONE_HANDED -> KeyboardStyleUnder.ONE_HANDED_OVER_NORMAL
            else -> KeyboardStyleUnder.NORMAL
        }
        return Stored(KeyboardStyle.FLOATING, under)
    }
}
