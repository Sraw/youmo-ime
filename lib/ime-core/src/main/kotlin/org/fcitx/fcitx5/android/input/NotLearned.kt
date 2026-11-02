/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit

/**
 * Why the focused field learns nothing, or less than others: what the bar's 「不学」 mark says when
 * held (D5). One mark for every reason, whole or partial, each said in its own words.
 */
enum class NotLearned {
    /** A password: nothing learned. */
    PASSWORD,

    /** The app asks for nothing to be kept (a browser's 无痕 tab). */
    APP_ASKS,

    /** A field of this app itself ([FieldPolicy.ownApp]). */
    OWN_APP,

    /** 「不用我的输入习惯」 is on: nothing learned anywhere. */
    HABITS_OFF,

    /** An address, an email or a number: words learned, not the pairs between them ([Commit.NO_PAIRS]). */
    NO_PAIRS,

    /** A search: no 联想 offered or learned ([Commit.NO_PREDICT]). */
    NO_PREDICT;

    companion object {
        /**
         * The reasons for a field: [password] and [sensitive] as [FieldPolicy.focusAnother] has them,
         * [ownApp] whether the field is this app's, [learnFlags] as [FieldPolicy.learnFlags] gives
         * them, [habitsOn] 「用我的输入习惯」. Where nothing is learned that one reason alone, the most
         * particular first (a password in this app's own field is a password); else each partial one.
         * Empty: everything learned, no mark.
         */
        fun of(password: Boolean, sensitive: Boolean, ownApp: Boolean, learnFlags: Int, habitsOn: Boolean): List<NotLearned> = when {
            password -> listOf(PASSWORD)
            sensitive && ownApp -> listOf(OWN_APP)
            sensitive -> listOf(APP_ASKS)
            !habitsOn -> listOf(HABITS_OFF)
            else -> buildList {
                if (learnFlags and Commit.NO_PAIRS != 0) add(NO_PAIRS)
                if (learnFlags and Commit.NO_PREDICT != 0) add(NO_PREDICT)
            }
        }
    }
}
