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
 * Setup comes back till the keyboard is set up, unless the user skipped it. Shown once was enough to
 * keep it away before: a user who left it for the system's settings, where a tap on the keyboard's
 * name opens the app's own settings over it, came back to the app with nothing to select the keyboard.
 * (The activity needs the app's resources, which these tests go without: whether it comes back is
 * tested here; that skip and back keep it away, in SetupActivityDeviceTest.)
 */
@RunWith(RobolectricTestRunner::class)
class SetupActivityTest {

    @Before
    fun reset() {
        SetupActivity.skipped = false
    }

    // the flag is the process's: a test after this one must not find it skipped
    @After
    fun forget() {
        SetupActivity.skipped = false
    }

    @Test
    fun skippedItIsNot() {
        SetupActivity.skipped = true
        assertFalse(SetupActivity.shouldShowUp())
    }

    /** Robolectric's phone has no keyboard enabled: each time MainActivity asks, the setup is due again. */
    @Test
    @Config(application = FcitxApplication::class)
    fun notSetUpItComesBackEachTimeTillSkipped() {
        assertTrue(SetupActivity.shouldShowUp())
        assertTrue(SetupActivity.shouldShowUp())
        SetupActivity.skipped = true
        assertFalse(SetupActivity.shouldShowUp())
    }
}
