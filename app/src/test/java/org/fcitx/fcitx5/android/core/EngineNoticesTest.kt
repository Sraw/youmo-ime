/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.Looper
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** What the engine says from its own threads: on the keyboard when it runs, a toast with none. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class EngineNoticesTest {

    private val said = mutableListOf<String>()

    @After
    fun noKeyboard() {
        EngineNotices.sayer = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // F3464: fcitx's own tips (punctuation, full width, a group switched) went the failure's way,
    // 6 s and not typed away, and lapsed a pending clipboard delete as they replaced it
    @Test
    fun aTipIsAPlainLineAndOnlyAFailureIsSteady() {
        EngineNotices.sayer = EngineNotices.Sayer { message, steady -> said += "$message steady=$steady" }
        EngineNotices.tip("全角")
        // the framework's string: the app's are not in unit tests' resources
        EngineNotices.failed(android.R.string.ok)
        idle()
        assertEquals(listOf("全角 steady=false", "${appContext.getString(android.R.string.ok)} steady=true"), said)
    }

    // F3521: no keyboard running, said all the same
    @Test
    fun withNoKeyboardItIsAToast() {
        EngineNotices.tip("全角")
        idle()
        assertEquals("全角", ShadowToast.getTextOfLatestToast())
    }
}
