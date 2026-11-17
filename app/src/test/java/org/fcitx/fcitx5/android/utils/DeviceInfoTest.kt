/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.view.Display
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The device's report (LogActivity's export): what it says of the keyboard is the keyboard's. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h600dp-land")
class DeviceInfoTest {

    // R26 F8329: a tablet held turned, the log page half of a split screen (its window 400 dp, upright): the keyboard is
    // 横屏 all the same, its window the display's width, and the report says so, not the page's window's shape
    @Test
    fun theKeyboardsLandscapeIsTheDisplaysNotThePagesWindow() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val half = Configuration(activity.resources.configuration).apply {
                screenWidthDp = 400
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
            val halfRes = activity.createConfigurationContext(half).resources
            val page = object : ContextWrapper(activity) {
                override fun getResources(): Resources = halfRes

                // Robolectric's activity context is no visual one to Context.getDisplay (it throws); a real activity's is
                @Suppress("DEPRECATION")
                override fun getDisplay(): Display = activity.windowManager.defaultDisplay
            }
            val report = DeviceInfo.get(page)
            assertTrue(report, "Window width: 400 dp, keyboard landscape: true" in report)
        }
    }
}
