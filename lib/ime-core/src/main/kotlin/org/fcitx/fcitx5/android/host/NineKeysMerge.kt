/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard

/**
 * 0.4's 九键拼音 (`engine-t9`), an input method of its own, as 0.5's pinyin on the nine keys (I1 §4):
 * the input method gone from the lists, its place pinyin's, pinyin's keyboard the nine keys where it
 * was the one used. Run at every read of the profile ([Profile.mergeNineKeys]), before anything drops
 * what it does not know, which would drop `engine-t9` and leave its user on the 26 keys: no mark of
 * it done, only a group that still names it changes. A 0.4 backup restored is read the same way at the
 * next start. Nothing else moves: the 九键 settings keep their keys, and the learning its kinds (`t9:`).
 */
object NineKeysMerge {

    /** 0.4's 九键拼音, as a user's profile may still name it. */
    const val OLD = InputMethodCatalog.T9

    /**
     * A group after the merge: its [items] and [defaultIm]; [pinyin], pinyin's keyboard to set (the
     * one passed in where it stays); [both], 拼音 and 九键拼音 were both on, the user to be told once
     * where the 26 keys and the nine keys are switched now.
     */
    data class Outcome(val items: List<String>, val defaultIm: String, val pinyin: ImKeyboard, val both: Boolean)

    /**
     * The group of [items] and [defaultIm] as 0.5 has it, pinyin's keyboard [pinyin] as it was set;
     * null where it has no [OLD]. [OLD] becomes pinyin, at the earlier of the two places, once. Pinyin
     * shows the nine keys where [OLD] alone was on, or with both where [OLD] came first in the list;
     * else as it was (the 26 or 14 keys). The default is not asked (the user's I1 Q2): fcitx's is never
     * the first of a list (ImGroup.withDefault), so it says what the globe goes to, not what was typed on.
     */
    fun group(items: List<String>, defaultIm: String, pinyin: ImKeyboard): Outcome? {
        if (OLD !in items && defaultIm != OLD) return null
        val pinyinAt = items.indexOf(InputMethodCatalog.PINYIN)
        val oldAt = items.indexOf(OLD)
        val both = pinyinAt >= 0 && oldAt >= 0
        val nine = oldAt >= 0 && (!both || oldAt < pinyinAt)
        val merged = items.map { if (it == OLD) InputMethodCatalog.PINYIN else it }.distinct()
        val default = if (defaultIm == OLD) InputMethodCatalog.PINYIN else defaultIm
        return Outcome(merged, default, if (nine) ImKeyboard.NINE_KEYS else pinyin, both)
    }
}
