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

/** MainActivity checks on every resume, so a back that did not keep it away would bring it straight back. Needs the app's resources. */
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

    @Test
    fun shownItIsStillDue() {
        // a set-up device has none due
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
