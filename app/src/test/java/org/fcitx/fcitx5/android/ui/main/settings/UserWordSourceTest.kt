/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.junit.Assert.assertEquals
import org.junit.Test

class UserWordSourceTest {

    private val words = listOf(UserWord("你好", "ni hao", UserWord.Kind.ADDED))

    // U-80: the list page's delete is the learning's RemoveWords, its token the 撤销 offered
    @Test
    fun aPinyinListsDeleteIsTheLearningsAndGivesItsUndo() = runTest {
        val fcitx = FakeFcitxAPI()
        var asked: LearningOp? = null
        fcitx.learned = { asked = it; LearningResult.Done(undo = 7) }
        assertEquals(7L, PinyinWords(UserWord.Kind.ADDED).remove(fcitx, words))
        assertEquals(LearningOp.RemoveWords(words), asked)
    }

    // not done (the record not open): nothing to take back, so no 撤销
    @Test
    fun noUndoWhenTheLearningDidNotDoIt() = runTest {
        val fcitx = FakeFcitxAPI()
        fcitx.learned = { LearningResult.Unavailable }
        assertEquals(LearningResult.NO_UNDO, PinyinWords(UserWord.Kind.ADDED).remove(fcitx, words))
    }

    // English's forget has no undo
    @Test
    fun englishOffersNoUndo() = runTest {
        val fcitx = FakeFcitxAPI()
        assertEquals(LearningResult.NO_UNDO, EnglishWords.remove(fcitx, words))
        assertEquals(listOf("forgetEnglishWords(你好)"), fcitx.calls)
    }
}
