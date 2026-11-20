/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.rules.ScreenShape

/** 键盘样式, 竖屏 and 横屏 each its own; one at a time, where 0.6.0's three settings lay over each other. */
enum class KeyboardStyle {
    NORMAL,

    /** The other boards at a side ([SplitKeyboard]); split whatever the width. */
    SPLIT,

    FLOATING,

    /** At the side remembered ([SplitKeyboard.placement]). */
    ONE_HANDED,
}

/** What turning floating or one-handed off goes back to (横屏 split, floated, docked again is split). One-handed is over 普通 or split; floating over any. */
enum class KeyboardStyleUnder {
    NORMAL,
    SPLIT,
    ONE_HANDED_OVER_NORMAL,
    ONE_HANDED_OVER_SPLIT,
}

/** Always one of the 8 meaningful pairs ([of] makes any other so). */
@ConsistentCopyVisibility
data class KeyboardStyleState private constructor(val style: KeyboardStyle, val under: KeyboardStyleUnder) {

    val floating: Boolean get() = style == KeyboardStyle.FLOATING
    val splits: Boolean get() = style == KeyboardStyle.SPLIT
    val oneHanded: Boolean get() = style == KeyboardStyle.ONE_HANDED

    /** One-handed over what was under floating, floating over what there is. */
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

    /** What was under it, one-handed as it was. */
    fun floatingOff(): KeyboardStyleState = when {
        !floating -> this
        under == KeyboardStyleUnder.ONE_HANDED_OVER_NORMAL -> KeyboardStyleState(KeyboardStyle.ONE_HANDED, KeyboardStyleUnder.NORMAL)
        under == KeyboardStyleUnder.ONE_HANDED_OVER_SPLIT -> KeyboardStyleState(KeyboardStyle.ONE_HANDED, KeyboardStyleUnder.SPLIT)
        else -> twoHanded(styleOf(under))
    }

    fun oneHandedOn(): KeyboardStyleState = set(KeyboardStyle.ONE_HANDED)

    fun oneHandedOff(): KeyboardStyleState = if (oneHanded) twoHanded(styleOf(under)) else this

    private fun twoHandedUnder(): KeyboardStyle = when (under) {
        KeyboardStyleUnder.NORMAL, KeyboardStyleUnder.ONE_HANDED_OVER_NORMAL -> KeyboardStyle.NORMAL
        KeyboardStyleUnder.SPLIT, KeyboardStyleUnder.ONE_HANDED_OVER_SPLIT -> KeyboardStyle.SPLIT
    }

    companion object {

        /** A pair that means nothing (damaged, a key alone) reads as the style over [fallback]; none stored: [fallback]. */
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

        /** 竖屏 普通; 横屏 split from [ScreenShape.WIDE_DP], as 0.6.0's 屏幕够宽时 (a phone on its side with a 3-button bar measures under 600 dp). */
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

        private fun oneHandedOver(under: KeyboardStyleUnder) =
            if (styleOf(under) == KeyboardStyle.SPLIT) KeyboardStyleUnder.ONE_HANDED_OVER_SPLIT else KeyboardStyleUnder.ONE_HANDED_OVER_NORMAL
    }
}

/** 0.6.0's three settings as each shape showed them; a null is not written, its default read. */
object KeyboardStyleMigration {

    enum class Split060 { AUTO, ALWAYS, OFF }

    /** Null where nothing is. */
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
