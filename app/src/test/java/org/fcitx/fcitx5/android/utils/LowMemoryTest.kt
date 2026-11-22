/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.app.ActivityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LowMemoryTest {

    @Test
    fun aLowRamDeviceOrASmallHeapIsLow() {
        assertTrue(LowMemory.of(lowRamDevice = true, memoryClassMb = 512))
        assertTrue(LowMemory.of(lowRamDevice = false, memoryClassMb = 192))
        assertFalse(LowMemory.of(lowRamDevice = false, memoryClassMb = 256))
    }

    @Test
    fun readFromTheActivityManager() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val am = shadowOf(context.getSystemService(ActivityManager::class.java))
        am.setMemoryClass(256)
        am.setIsLowRamDevice(false)
        assertFalse(LowMemory.on(context))
        am.setIsLowRamDevice(true)
        assertTrue(LowMemory.on(context))
        am.setIsLowRamDevice(false)
        am.setMemoryClass(128)
        assertTrue(LowMemory.on(context))
    }
}
