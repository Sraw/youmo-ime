/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.input.NotLearned.APP_ASKS
import org.fcitx.fcitx5.android.input.NotLearned.HABITS_OFF
import org.fcitx.fcitx5.android.input.NotLearned.NO_PAIRS
import org.fcitx.fcitx5.android.input.NotLearned.NO_PREDICT
import org.fcitx.fcitx5.android.input.NotLearned.OWN_APP
import org.fcitx.fcitx5.android.input.NotLearned.PASSWORD
import org.fcitx.fcitx5.android.input.editing.EditorTraits
import org.junit.Assert.assertEquals
import org.junit.Test

/** The reasons the bar's 「不学」 mark gives for each kind of field (D5). */
class NotLearnedTest {

    private fun of(
        password: Boolean = false,
        sensitive: Boolean = false,
        ownApp: Boolean = false,
        learnFlags: Int = 0,
        habitsOn: Boolean = true,
    ) = NotLearned.of(password, sensitive, ownApp, learnFlags, habitsOn)

    @Test
    fun anOrdinaryFieldHasNoMark() = assertEquals(emptyList<NotLearned>(), of())

    @Test
    fun aPasswordIsAPasswordWhereverItIs() {
        assertEquals(listOf(PASSWORD), of(password = true, sensitive = true))
        assertEquals(listOf(PASSWORD), of(password = true, sensitive = true, ownApp = true, learnFlags = Commit.UNPAIRED, habitsOn = false))
    }

    @Test
    fun aFieldAskingNotToBeLearnedFromIsTheAppsOrThisAppsOwn() {
        assertEquals(listOf(APP_ASKS), of(sensitive = true))
        assertEquals(listOf(OWN_APP), of(sensitive = true, ownApp = true))
        // the app's package alone is no reason: a debug build's test field is not sensitive
        assertEquals(emptyList<NotLearned>(), of(ownApp = true))
    }

    @Test
    fun habitsOffLearnsNothingAnywhereSoThePartialReasonsAreNotSaid() {
        assertEquals(listOf(HABITS_OFF), of(habitsOn = false))
        assertEquals(listOf(HABITS_OFF), of(habitsOn = false, learnFlags = Commit.UNPAIRED))
        assertEquals(listOf(APP_ASKS), of(sensitive = true, habitsOn = false))
    }

    @Test
    fun theFieldsLearnFlagsEachTheirOwnReason() {
        fun flags(traits: EditorTraits) = of(learnFlags = FieldPolicy.learnFlags(traits))
        assertEquals(listOf(NO_PAIRS), flags(EditorTraits(isUri = true)))
        assertEquals(listOf(NO_PAIRS), flags(EditorTraits(isEmail = true)))
        assertEquals(listOf(NO_PAIRS), flags(EditorTraits(isNumber = true)))
        assertEquals(listOf(NO_PREDICT), flags(EditorTraits(isSearch = true)))
        assertEquals(listOf(NO_PAIRS, NO_PREDICT), flags(EditorTraits(isUri = true, isSearch = true)))
    }
}
