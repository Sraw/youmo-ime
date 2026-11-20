/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.setup

import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Setup returns till the keyboard is set up unless skipped; once shown used to be enough, stranding a user who left for
 * the system settings. Skip and back are in SetupActivityDeviceTest (the activity needs resources).
 */
@RunWith(RobolectricTestRunner::class)
class SetupActivityTest {

    @Before
    fun reset() {
        SetupActivity.skipped = false
    }

    // the flag is the process's: a later test must not find it skipped
    @After
    fun forget() {
        SetupActivity.skipped = false
    }

    @Test
    fun skippedItIsNot() {
        SetupActivity.skipped = true
        assertFalse(SetupActivity.shouldShowUp())
    }

    /** Robolectric's phone has no keyboard enabled. */
    @Test
    @Config(application = FcitxApplication::class)
    fun notSetUpItComesBackEachTimeTillSkipped() {
        assertTrue(SetupActivity.shouldShowUp())
        assertTrue(SetupActivity.shouldShowUp())
        SetupActivity.skipped = true
        assertFalse(SetupActivity.shouldShowUp())
    }
}
