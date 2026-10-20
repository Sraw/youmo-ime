/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSettingsTest {

    // read for the keyboard: 九键英文 spells a letter at a time where English suggests nothing
    @Test
    fun englishSuggestionsAreOnTillTurnedOff() {
        assertTrue(EngineSettings.parse("").englishSuggest)
        assertFalse(EngineSettings.parse("${EngineSettings.ENGLISH_SUGGEST}=False").englishSuggest)
        assertTrue(EngineSettings.parse("${EngineSettings.ENGLISH_SUGGEST}=True").englishSuggest)
        assertTrue(EngineSettings.parse("${EngineSettings.ENGLISH_SUGGEST}=maybe").englishSuggest)
    }
}
