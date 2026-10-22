/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LearningBackupsFragmentTest {

    // review-uat-fixes S12: the words' export is the learning's copy, saved only once the warning is accepted
    @Test
    fun aCopyIsSavedOnlyAfterTheWarningIsAccepted() {
        val saved = ArrayList<String>()
        var asked: Int? = null
        var accept: () -> Unit = {}
        LearningBackupsFragment.askSaveCopy(RuntimeEnvironment.getApplication(), R.string.export_words, "youmo-words", saved::add) { _, message, ok ->
            asked = message
            accept = ok
        }
        assertEquals(R.string.learning_save_copy_warning, asked)
        assertTrue(saved.isEmpty())
        accept()
        assertTrue(saved.single().let { it.startsWith("youmo-words-") && it.endsWith(LearnBackup.EXTENSION) })
    }
}
