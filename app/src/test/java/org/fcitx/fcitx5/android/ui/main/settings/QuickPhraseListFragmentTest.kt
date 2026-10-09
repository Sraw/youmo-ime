/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A new quick phrase list is the file `<name>.mb`. A name with a slash crashed the app, and a name
 * already listed added a second row over the same file, so removing either deleted both.
 */
class QuickPhraseListFragmentTest {

    @Test
    fun aNameWithASlashIsRefused() {
        assertEquals(R.string.invalid_value, newQuickPhraseNameError("a/b", emptyList()))
    }

    @Test
    fun aNameAlreadyListedIsRefusedWhateverItsCase() {
        assertEquals(R.string.quickphrase_already_exists, newQuickPhraseNameError("emoji", listOf("emoji")))
        assertEquals(R.string.quickphrase_already_exists, newQuickPhraseNameError("Emoji", listOf("emoji")))
    }

    @Test
    fun anUnusedNameIsAccepted() {
        assertNull(newQuickPhraseNameError("work", listOf("emoji")))
    }
}
