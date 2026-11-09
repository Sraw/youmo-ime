/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
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

    // a list added after the window's insets went out asks for them again once attached (a ViewPager2 page
    // made when its tab is first picked), and its bottom padding is the navigation bar's
    @Test
    fun aListAddedLateAsksForTheInsetsAndPadsTheNavigationBar() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity).also(activity::setContentView)
        val list = Probe(activity)
        list.applyNavBarInsetsBottomPadding()
        assertEquals(0, list.asked)
        root.addView(list)
        assertTrue("asked once attached", list.asked > 0)
        // from the window's root, as the system gives them: below API 30 compat reads the navigation bar off the
        // system window insets cut to the stable ones, so both are set (a navigation bar alone is 0 at SDK 29)
        val nav = Insets.of(0, 0, 0, 48)
        @Suppress("DEPRECATION")
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), nav)
            .setSystemWindowInsets(nav)
            .setStableInsets(nav)
            .build()
        val platform = insets.toWindowInsets()!!
        // below API 30 compat also cuts them to the root's stable insets (View.getRootWindowInsets, the view root's
        // last insets), which a test window never got: set them there as the system would
        val viewRoot = activity.window.decorView.parent
        viewRoot.javaClass.getDeclaredField("mLastWindowInsets").apply { isAccessible = true }.set(viewRoot, platform)
        // to the content view: the decor's action bar layout would take them first
        root.dispatchApplyWindowInsets(platform)
        assertEquals(48, list.paddingBottom)
        assertEquals(false, list.clipToPadding)
    }
}
