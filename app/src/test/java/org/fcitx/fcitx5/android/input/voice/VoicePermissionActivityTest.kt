/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration

/** When the microphone's refusal opens the app's settings page: only when the system refused with no dialog. */
@RunWith(RobolectricTestRunner::class)
class VoicePermissionActivityTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun askedBefore() {
        // asked once before and no rationale (Robolectric's default): a refusal for good, if it comes back at once
        app.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().putBoolean("microphone_asked", true).commit()
    }

    private fun started(): ActivityController<VoicePermissionActivity> {
        val c = Robolectric.buildActivity(VoicePermissionActivity::class.java).setup()
        drainStarted()
        return c
    }

    private fun drainStarted() {
        while (shadowOf(app).nextStartedActivity != null) Unit
    }

    @Suppress("DEPRECATION")
    private fun refuse(activity: VoicePermissionActivity, asked: VoicePermissionActivity = activity) {
        val request = shadowOf(asked).lastRequestedPermission
        assertNotNull("the microphone was asked for", request)
        activity.onRequestPermissionsResult(
            request.requestCode,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun refusedForGood(activity: VoicePermissionActivity) =
        VoicePermissionActivity::class.java.getDeclaredField("refusedForGood").apply { isAccessible = true }.getBoolean(activity)

    @Test
    fun aSecondAskRefusedAtOnceOpensTheSettings() {
        val c = started()
        refuse(c.get())
        val settings = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings?.action)
        assertTrue(c.get().isFinishing)
    }

    @Test
    fun aDialogDismissedAfterAWhileOpensNothing() {
        val c = started()
        // a dialog shown: the answer comes back after NO_DIALOG_MS
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        refuse(c.get())
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(c.get().isFinishing)
    }

    @Test
    fun rebuiltWithTheDialogUpKeepsWhatItKnewAndOpensNothing() {
        // the clock past NO_DIALOG_MS since boot, as a device's always is (a rebuilt one counts from 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val c = started()
        // the request the system holds across the rebuild: asked by the first, answered to the rebuilt (which asks no more)
        val first = c.get()
        c.recreate()
        drainStarted()
        // what was known before the rebuild kept, though the prefs already say asked (a rebuild does not re-read them)
        assertTrue(refusedForGood(c.get()))
        // the answer came at once after the rebuild, but the dialog was up before it: the user's, not the system's
        refuse(c.get(), asked = first)
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
