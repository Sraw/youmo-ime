/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreLogTest {

    private val logged = mutableListOf<String>()

    @After
    fun detach() {
        CoreLog.sink = null
        CoreLog.verbose = false
    }

    @Test
    fun withoutASinkNothingIsFormatted() {
        CoreLog.verbose = true
        CoreLog.d { error("formatted a debug message nobody receives") }
        CoreLog.v { error("formatted a verbose message nobody receives") }
    }

    @Test
    fun debugMessagesNeedOnlyASink() {
        CoreLog.sink = { logged += it }
        CoreLog.d { "d" }
        assertEquals(listOf("d"), logged)
    }

    @Test
    fun verboseMessagesAlsoNeedTheVerboseSetting() {
        CoreLog.sink = { logged += it }
        CoreLog.v { error("formatted a verbose message with verbose logging off") }
        CoreLog.verbose = true
        CoreLog.v { "v" }
        assertEquals(listOf("v"), logged)
    }
}
