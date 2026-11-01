/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.inputmethodservice.InputMethodService
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/**
 * InputMethodService's requestHideSelf, recorded: the real one waits on the system's answer
 * (hideMySoftInput's future), which never comes under Robolectric, and hangs the test.
 */
@Implements(InputMethodService::class)
class HideRecordingShadow : ShadowService() {

    @Implementation
    fun requestHideSelf(flags: Int) {
        hides += flags
    }

    companion object {
        /**
         * The flags of each request, in order, across the tests of a sandbox: cleared by a test before
         * what it counts (ToolbarUatTest.startRecordingHides). Not by a @Resetter: Robolectric calls
         * those only through its annotation processor's ShadowProvider, which a shadow given in @Config
         * has none of here.
         */
        val hides = mutableListOf<Int>()
    }
}
