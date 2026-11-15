/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard.Spot
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 竖屏 and 横屏 each their place and width (061-floating-resize §2.6)
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class FloatingStoreTest {

    private val prefs = AppPrefs.getInstance().internal

    @get:Rule
    val prefsBack = PrefsRule()

    @Test
    @Uat("TB-61")
    fun neverKeptEitherWayItIsWhere060LeftIt() {
        assertEquals(FloatingKeyboard.DEFAULT, FloatingStore.spot(false))
        prefs.floatingKeyboardX.setValue(0.1f)
        prefs.floatingKeyboardY.setValue(0.2f)
        assertEquals(Spot(0.1f, 0.2f), FloatingStore.spot(false))
        assertEquals(Spot(0.1f, 0.2f), FloatingStore.spot(true))
        assertEquals(-1f, FloatingStore.widthDp(false), 0f)
        assertEquals(-1f, FloatingStore.widthDp(true), 0f)
    }

    @Test
    fun eachWayKeepsItsOwnAndTheOldKeysAreLeft() {
        prefs.floatingKeyboardX.setValue(0.1f)
        prefs.floatingKeyboardY.setValue(0.2f)
        FloatingStore.keepSpot(landscape = true, Spot(1f, 0f))
        assertEquals(Spot(1f, 0f), FloatingStore.spot(true))
        assertEquals(Spot(0.1f, 0.2f), FloatingStore.spot(false))
        assertEquals(0.1f, prefs.floatingKeyboardX.getValue(), 0f)
        FloatingStore.keepWidth(landscape = false, 280)
        assertEquals(280f, FloatingStore.widthDp(false), 0f)
        assertEquals(-1f, FloatingStore.widthDp(true), 0f)
    }

    // while a finger resizes it its width is read as it is now, kept only as the finger lifts
    @Test
    fun aResizeUnderWayIsReadNotKept() {
        FloatingStore.keepWidth(landscape = false, 280)
        FloatingStore.resizing(landscape = false, 320)
        assertEquals(320f, FloatingStore.widthDp(false), 0f)
        assertEquals(-1f, FloatingStore.widthDp(true), 0f)
        assertEquals(280f, prefs.floatingPortraitWidthDp.getValue(), 0f)
        FloatingStore.resizing(landscape = false, null)
        assertEquals(280f, FloatingStore.widthDp(false), 0f)
    }
}
