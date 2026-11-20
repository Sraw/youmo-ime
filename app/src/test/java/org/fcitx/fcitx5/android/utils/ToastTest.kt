/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.os.Looper
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
// minSdk and the pinned SDK
@Config(sdk = [29, 35])
class ToastTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun latestCancelled() = shadowOf(ShadowToast.getLatestToast()).isCancelled

    // still there a millisecond before its time, gone at it
    @Test
    @Uat("MN-26")
    fun aToastGoesAfterItsTime() {
        val ok = context.getString(android.R.string.ok)
        assertRows(
            *listOf(
                Triple("text, short", { context.toast("已复制") } to "已复制", TOAST_SHORT_MS),
                Triple("text, long", { context.toast("没保存", Toast.LENGTH_LONG) } to "没保存", TOAST_LONG_MS),
                Triple("resource, short", { context.toast(android.R.string.ok) } to ok, TOAST_SHORT_MS),
                Triple("resource, long", { context.toast(android.R.string.ok, Toast.LENGTH_LONG) } to ok, TOAST_LONG_MS),
            ).map { (why, shown, ms) ->
                why to {
                    shown.first()
                    assertEquals(shown.second, ShadowToast.getTextOfLatestToast())
                    idle(ms - 1L)
                    assertFalse("before ${ms - 1} ms", latestCancelled())
                    idle(1)
                    assertTrue("at $ms ms", latestCancelled())
                }
            }.toTypedArray(),
        )
    }

    @Test
    @Uat("MN-26")
    fun aLongerTimeoutAskedForIsKept() {
        val a11y = shadowOf(context.getSystemService(AccessibilityManager::class.java))
        // 1.5 s asked for: a short toast stays that long
        a11y.setNonInteractiveUiTimeout(1500)
        context.toast("已复制")
        idle(1499)
        assertFalse(latestCancelled())
        idle(1)
        assertTrue(latestCancelled())
        // past the system's own length: as long as asked
        a11y.setNonInteractiveUiTimeout(10_000)
        context.toast("没保存", Toast.LENGTH_LONG)
        idle(9_999)
        assertFalse(latestCancelled())
        idle(1)
        assertTrue(latestCancelled())
    }

    // the system queues toasts: a queued one would spend its time waiting
    @Test
    @Uat("MN-26")
    fun theNewestTakesThePlaceOfOneShown() {
        context.toast("已复制")
        val first = ShadowToast.getLatestToast()
        idle(500)
        context.toast("已剪切")
        assertTrue("the first put away at once", shadowOf(first).isCancelled)
        assertEquals("已剪切", ShadowToast.getTextOfLatestToast())
        // its full time from its own show
        idle(TOAST_SHORT_MS - 1L)
        assertFalse(latestCancelled())
        idle(1)
        assertTrue(latestCancelled())
    }
}
