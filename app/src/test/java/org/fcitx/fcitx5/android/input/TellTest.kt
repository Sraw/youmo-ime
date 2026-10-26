/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class TellTest {

    // F3198: each flag defaults to false at engineContext; one dropped on the way is silently off (HW-23's said)
    @Test
    fun everyFlagOfTheContextReachesTheEngine() = runTest {
        val fcitx = FakeFcitxAPI()
        fcitx.tell(Action.Context("你好", cleared = true, learnFlags = 2, started = true, after = "吗", selected = true, said = true), learning = true)
        assertEquals(listOf("engineContext(你好, true, cleared, flags 2, started, after 吗, selected, said)"), fcitx.calls)
    }
}
