/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class RecyclerViewTest {

    private class Probe(context: Context) : RecyclerView(context) {
        var asked = 0

        override fun requestApplyInsets() {
            asked++
            super.requestApplyInsets()
        }
    }

    // a list attached after insets went out asks again, and pads for the navigation bar
    @Test
    fun aListAddedLateAsksForTheInsetsAndPadsTheNavigationBar() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity).also(activity::setContentView)
        val list = Probe(activity)
        list.applyNavBarInsetsBottomPadding()
        assertEquals(0, list.asked)
        root.addView(list)
        assertTrue("asked once attached", list.asked > 0)
        // below API 30 compat reads the nav bar off system insets cut to stable ones, so both are set
        val nav = Insets.of(0, 0, 0, 48)
        @Suppress("DEPRECATION")
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), nav)
            .setSystemWindowInsets(nav)
            .setStableInsets(nav)
            .build()
        val platform = insets.toWindowInsets()!!
        // below API 30 compat also cuts them to the root's stable insets, which a test window never got
        val viewRoot = activity.window.decorView.parent
        // a hidden ViewRootImpl field: if renamed in a later SDK, this fails clearly
        val lastInsets = runCatching { viewRoot.javaClass.getDeclaredField("mLastWindowInsets") }.getOrElse {
            throw AssertionError("ViewRootImpl.mLastWindowInsets not found in ${viewRoot.javaClass}: the test sets the root's insets there", it)
        }
        lastInsets.apply { isAccessible = true }.set(viewRoot, platform)
        // to the content view: the decor's action bar layout would take them first
        root.dispatchApplyWindowInsets(platform)
        assertEquals(48, list.paddingBottom)
        assertEquals(false, list.clipToPadding)
    }
}
