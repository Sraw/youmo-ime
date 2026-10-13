/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.setup

import android.content.Intent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Back leaves the setup as skip does, keeping it away: MainActivity looks again each time it resumes
 * (SetupActivityTest), so a back that did not would bring it back at once. Being shown does not keep
 * it away, as it did before. On a device: the activity needs the app's resources.
 */
class SetupActivityDeviceTest {

    @Before
    fun reset() {
        SetupActivity.skipped = false
    }

    @After
    fun forget() {
        SetupActivity.skipped = false
    }

    @Test
    fun shownItIsNotSkippedAndBackSkipsIt() {
        val seen = mutableListOf<Boolean>()
        launch {
            seen += SetupActivity.skipped
            it.onBackPressedDispatcher.onBackPressed()
            seen += SetupActivity.skipped
            seen += it.isFinishing
        }
        assertEquals(listOf(false, true, true), seen)
    }

    @Test
    fun theSkipButtonSkipsIt() {
        val seen = mutableListOf<Boolean>()
        launch {
            it.findViewById<Button>(R.id.skip_button).performClick()
            seen += SetupActivity.skipped
            seen += it.isFinishing
        }
        assertEquals(listOf(true, true), seen)
    }

    /** Shown, it is still due: a flag set as it was shown, as `shown` was in onCreate, kept it away after one look. */
    @Test
    fun shownItIsStillDue() {
        // on a device whose keyboard is set up none is due, shown or not
        assumeTrue(SetupPage.hasUndonePage())
        val due = mutableListOf<Boolean>()
        launch { due += SetupActivity.shouldShowUp() }
        assertEquals(listOf(true), due)
    }

    private fun launch(action: (SetupActivity) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SetupActivity>(Intent(context, SetupActivity::class.java)).use { scenario ->
            scenario.onActivity { action(it) }
        }
    }
}
