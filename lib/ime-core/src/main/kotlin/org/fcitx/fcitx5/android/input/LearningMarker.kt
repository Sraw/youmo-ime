/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * The mark the idle bar shows while learning is not as usual: one at a time, 这里不学 before
 * 已不用我的输入习惯 (U-87). None where nothing is out of the usual: no mark is learning.
 */
enum class LearningMarker {
    /** U-87: the field asks not to be learned from ([FieldPolicy.sensitive]). */
    NOT_HERE,

    /** U-93: the user turned their habits off. */
    HABITS_OFF,
    ;

    companion object {
        fun of(learnsHere: Boolean, habits: Boolean): LearningMarker? = when {
            !learnsHere -> NOT_HERE
            !habits -> HABITS_OFF
            else -> null
        }
    }
}
