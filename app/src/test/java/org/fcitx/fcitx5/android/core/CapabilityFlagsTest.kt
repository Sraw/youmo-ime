/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityFlagsTest {

    @Test
    fun aFieldIsPasswordOrSensitiveWithEitherFlag() {
        // a password field has no Sensitive, an incognito tab no Password
        val password = CapabilityFlags(CapabilityFlag.Password)
        val incognito = CapabilityFlags(CapabilityFlag.Sensitive)
        assertTrue(password.hasAny(CapabilityFlag.PasswordOrSensitive))
        assertTrue(incognito.hasAny(CapabilityFlag.PasswordOrSensitive))
        assertFalse(password.has(CapabilityFlag.PasswordOrSensitive))
        assertFalse(CapabilityFlags(CapabilityFlag.Preedit).hasAny(CapabilityFlag.PasswordOrSensitive))
    }

    @Test
    fun aFieldOfThisAppLearnsNothing() {
        // F2330: the emoji picker's recents read these flags, not fromEditorInfo
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "own" }
        assertTrue(CapabilityFlags.forField(info, "own").has(CapabilityFlag.Sensitive))
        assertFalse(CapabilityFlags.forField(info, "other").has(CapabilityFlag.Sensitive))
        assertFalse(CapabilityFlags.fromEditorInfo(info).has(CapabilityFlag.Sensitive))
        // the debug build's test host: typed into as another app's field; a release build has none
        info.privateImeOptions = CapabilityFlags.TEST_HOST_FIELD
        assertEquals(!BuildConfig.DEBUG, CapabilityFlags.forField(info, "own").has(CapabilityFlag.Sensitive))
    }

    @Test
    fun aPinIsAPassword() {
        fun flags(type: Int) = CapabilityFlags.fromEditorInfo(EditorInfo().apply { inputType = type })
        val pin = flags(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertTrue(pin.has(CapabilityFlag.Password))
        // a signed number shares no bit of the variation
        assertFalse(flags(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED).has(CapabilityFlag.Password))
    }

    @Test
    fun aShownPasswordIsAPasswordToTheKeyboardOnly() {
        fun info(type: Int, options: Int = 0) = EditorInfo().apply { inputType = type; imeOptions = options }
        val shown = info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        assertTrue(CapabilityFlags.isPassword(shown))
        // fcitx is told what it was told before: a sensitive field
        assertFalse(CapabilityFlags.fromEditorInfo(shown).has(CapabilityFlag.Password))
        assertTrue(CapabilityFlags.isPassword(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)))
        assertTrue(CapabilityFlags.isPassword(info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)))
        assertFalse(CapabilityFlags.isPassword(info(InputType.TYPE_CLASS_TEXT)))
        // sensitive too, but no password
        assertFalse(CapabilityFlags.isPassword(info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)))
        // the variation's bits mean something else in another class
        assertFalse(CapabilityFlags.isPassword(info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
    }

    @Test
    fun eachPasswordAndAnIncognitoFieldIsPasswordOrSensitive() {
        // what the privacy gate asks: nothing around the cursor read, nothing typed kept or learned
        fun gated(type: Int, options: Int = 0) =
            CapabilityFlags.fromEditorInfo(EditorInfo().apply { inputType = type; imeOptions = options })
                .hasAny(CapabilityFlag.PasswordOrSensitive)
        val text = InputType.TYPE_CLASS_TEXT
        mapOf(
            "password" to gated(text or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            "web password" to gated(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            "shown password" to gated(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            "PIN" to gated(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            "incognito" to gated(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        ).forEach { (name, isGated) -> assertTrue(name, isGated) }
        mapOf(
            "text" to gated(text),
            "email" to gated(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            "number" to gated(InputType.TYPE_CLASS_NUMBER),
            // an option that does not turn learning off
            "no extract UI" to gated(text, EditorInfo.IME_FLAG_NO_EXTRACT_UI),
        ).forEach { (name, isGated) -> assertFalse(name, isGated) }
    }
}
