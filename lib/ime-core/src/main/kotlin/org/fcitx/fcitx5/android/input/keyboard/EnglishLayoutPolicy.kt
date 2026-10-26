/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * Which letters English shows, and how the nine keys type it there.
 *
 * 九键's 中/英 key goes between 九键拼音 and English on the nine keys (九键英文), as 搜狗's does
 * (owner 2026-10-20); English come to any other way (the language key, the space's long press, a
 * hardware shortcut) is an input method of its own, on the full keyboard; the grouped keyboards (14,
 * 18 keys) have no 中/英 (R16 F4346, owner 2026-10-26). The key's way in is kept
 * (`englishOnNineKeys`) while English stays, so the input view made anew (a turn of the screen, a
 * setting changed) shows it as it was, and dropped once anything else shows.
 */
object EnglishLayoutPolicy {

    /**
     * Where 九键's 中/英 key goes from input method [current]: English from 九键拼音, back from
     * English; else, or to one not [enabled] (fcitx would switch to none, quietly), nowhere.
     */
    fun toggled(current: String, enabled: List<String>): String? = when (current) {
        Engines.T9 -> Engines.ENGLISH
        Engines.ENGLISH -> Engines.T9
        else -> null
    }?.takeIf { it in enabled }

    /** Whether [ime] shown is English on the nine keys, [onNineKeys] whether the 中/英 key brought it, still kept. */
    fun nineKeys(ime: String, onNineKeys: Boolean): Boolean = onNineKeys && ime == Engines.ENGLISH

    /**
     * Whether [ime] shown ends what 中/英 brought: anything but English, unless fcitx is on English
     * again by now ([current]): the update is one queued behind 中/英 pressed again (three times
     * quickly), which set it anew.
     */
    fun endsNineKeys(ime: String, current: String): Boolean = ime != Engines.ENGLISH && current != Engines.ENGLISH

    /**
     * Where the language key's round of input methods goes from 九键英文: on from 九键拼音, the two
     * being one input method to the user, of those [enabled] in their order; null without it.
     * Straight there, not through 九键拼音, whose keyboard would show for a moment.
     */
    fun nextFromNineKeys(enabled: List<String>): String? = nextFrom(Engines.T9, enabled)

    // on from [from] in the round of those [enabled]; null without it
    private fun nextFrom(from: String, enabled: List<String>): String? {
        val at = enabled.indexOf(from)
        return if (at < 0) null else enabled[(at + 1) % enabled.size]
    }

    /**
     * Whether English on the nine keys spells words from the keys ([predictive], 预测) rather than
     * a letter at a time (连按): only where English [suggests] words at all (its setting on, the
     * field neither a password, nor an address, nor one asking for no suggestions), as the engine
     * hands the keys to no word there.
     */
    fun spellsWords(predictive: Boolean, suggests: Boolean, password: Boolean, address: Boolean, noSuggestions: Boolean): Boolean =
        predictive && suggests && !password && !address && !noSuggestions
}
