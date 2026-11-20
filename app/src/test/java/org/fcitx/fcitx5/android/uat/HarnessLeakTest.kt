/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.app.Activity
import android.util.ArrayMap
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A closed harness leaves no activity alive: a full run's OOMs were a harness's Android objects kept past its close
 * (ShadowDialog's list, GapWorker's list holding a RecyclerView's window, ActivityThread's activities with their
 * themes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class HarnessLeakTest {

    // what holds an activity, its window and themes till the test ends
    private fun liveActivities(): List<Activity> {
        val thread = RuntimeEnvironment.getActivityThread()
        val f = thread.javaClass.getDeclaredField("mActivities").apply { isAccessible = true }
        val records = (f.get(thread) as ArrayMap<*, *>).values
        return records.mapNotNull { r ->
            r!!.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(r) as Activity?
        }.filterNot { it.isDestroyed }
    }

    @Test
    fun aClosedKeyboardHarnessLeavesNoActivity() {
        val before = liveActivities().size
        repeat(3) { KeyboardHarness().use { it.start() } }
        assertEquals(before, liveActivities().size)
    }

    @Test
    fun aClosedSettingsHarnessDestroysItsActivity() {
        val st = SettingsHarness()
        val activity = st.activity
        val decor = activity.window.decorView
        assertTrue(decor.isAttachedToWindow)
        st.close()
        assertTrue(activity.isDestroyed)
        // a RecyclerView detached leaves GapWorker's list
        assertFalse(decor.isAttachedToWindow)
        assertEquals(emptyList<Activity>(), liveActivities())
    }
}
