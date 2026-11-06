/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommitTest {

    @Test
    fun whatWasSaidIsTypedIntoTheFieldItWasSaidFor() {
        assertEquals("你好", VoiceCommit.typed("你好", sameField = true, passwordField = false))
    }

    @Test
    fun notIntoAnotherFieldNorAPasswordNorNothing() {
        assertNull(VoiceCommit.typed("你好", sameField = false, passwordField = false))
        assertNull(VoiceCommit.typed("你好", sameField = true, passwordField = true))
        assertNull(VoiceCommit.typed("", sameField = true, passwordField = false))
    }

    @Test
    fun theSameFieldIsTheSameAppAndViewIdAcrossARestart() {
        val chat = VoiceCommit.Field("org.example.chat", 7)
        // input restarted in it: a new EditorInfo, the same field
        assertTrue(VoiceCommit.sameField(chat, VoiceCommit.Field("org.example.chat", 7), identical = false))
        assertFalse(VoiceCommit.sameField(chat, VoiceCommit.Field("org.example.chat", 8), identical = false))
        assertFalse(VoiceCommit.sameField(chat, VoiceCommit.Field("org.example.mail", 7), identical = false))
        assertFalse(VoiceCommit.sameField(chat, null, identical = false))
        // ids not set tell nothing: two such fields are not one, unless the very EditorInfo
        assertFalse(VoiceCommit.sameField(VoiceCommit.Field("a", 0), VoiceCommit.Field("a", 0), identical = false))
        assertFalse(VoiceCommit.sameField(VoiceCommit.Field("a", -1), VoiceCommit.Field("a", -1), identical = false))
        assertTrue(VoiceCommit.sameField(VoiceCommit.Field("a", 0), VoiceCommit.Field("a", 0), identical = true))
    }

    @Test
    fun aWebViewsInputsAreToldApartByWhatTheySayOfThemselves() {
        // one view (one id) holds them all: its type, name and hint tell them apart, as ContextMemory.field does
        val search = VoiceCommit.Field("org.example.browser", 9, inputType = 1, fieldName = "q", hintText = "Search")
        assertTrue(VoiceCommit.sameField(search, search.copy(), identical = false))
        assertFalse(VoiceCommit.sameField(search, search.copy(hintText = "Message"), identical = false))
        assertFalse(VoiceCommit.sameField(search, search.copy(fieldName = "body"), identical = false))
        assertFalse(VoiceCommit.sameField(search, search.copy(inputType = 0x21), identical = false))
    }
}
