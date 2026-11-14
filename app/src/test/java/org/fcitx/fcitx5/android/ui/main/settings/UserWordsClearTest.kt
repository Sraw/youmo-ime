/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.engine.runtime.UserWord
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
}
