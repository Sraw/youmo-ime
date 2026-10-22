/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.ListedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class UserWordsClearTest {

    // F2730: 全部清空's warning counts the tables' blocks under a code with the blocked words
    @Test
    fun theBlockedThatComeBackAreTheWordsAndTheTablesBlocks() {
        val blocks = LearningResult.Listed(listOf(ListedItem(null, "", "寸", blocked = true)))
        assertEquals(2, blockedThatReturn(mapOf(UserWord.Kind.BLOCKED to 1, UserWord.Kind.ADDED to 5), blocks))
        // the engine not there: what was counted
        assertEquals(1, blockedThatReturn(mapOf(UserWord.Kind.BLOCKED to 1), LearningResult.Unavailable))
        assertEquals(0, blockedThatReturn(emptyMap(), null))
    }

    // 全部清空 ticked: the dialog lists what goes, all that was kept but the custom phrases
    @Test
    fun theDialogSaysWhatTheTickClears() {
        assertEquals(R.string.learning_clear_learned_message, clearMessage(all = false))
        assertEquals(R.string.learning_clear_all_message, clearMessage(all = true))
    }

    // F3231: 清掉学到的 tells cleared, a disk that failed, or not now (anything else: locked, no engine)
    @Test
    fun clearingTellsWhatHappened() {
        assertEquals(R.string.learning_cleared, clearedMessage(LearningResult.Cleared(0)))
        assertEquals(R.string.learning_clear_failed, clearedMessage(LearningResult.Failed))
        assertEquals(R.string.learning_not_now, clearedMessage(LearningResult.Unavailable))
        assertEquals(R.string.learning_not_now, clearedMessage(null))
    }
}
