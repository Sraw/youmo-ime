/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.inputmethodservice.InputMethodService
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/** The real requestHideSelf waits on the system's answer, which never comes under Robolectric, and hangs the test. */
@Implements(InputMethodService::class)
class HideRecordingShadow : ShadowService() {

    @Implementation
    fun requestHideSelf(flags: Int) {
        hides += flags
    }

    companion object {
        /**
         * Cleared by a test before what it counts. Not by a @Resetter: Robolectric calls those only through its annotation
         * processor's ShadowProvider, which a shadow given in @Config has none of here.
         */
        val hides = mutableListOf<Int>()
    }
}
