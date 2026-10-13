/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The start after an import of the user's data puts the settings without a UI back, once; the
 * first start deletes the notification channels an older version made, once.
 */
@RunWith(RobolectricTestRunner::class)
class FcitxApplicationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("fcitx-application-test", Context.MODE_PRIVATE)

    // reset once already, as every start after the first: only the import's marker resets again
    private fun prefs() = AppPrefs(sp).also {
        it.resetHiddenSettings()
        sp.edit { putBoolean("ignore_system_cursor", true) }
    }

    @Before
    fun empty() {
        sp.edit { clear() }
    }

    @Test
    fun anImportsMarkerResetsTheSettingsAgainAndGoes() {
        val prefs = prefs()
        val marker = folder.newFile("user_data_imported")
        FcitxApplication.resetHiddenSettings(prefs, directBoot = false) { marker }
        assertFalse(prefs.advanced.ignoreSystemCursor.getValue())
        assertFalse(marker.exists())
    }

    @Test
    fun withoutAMarkerNothingIsResetAgain() {
        val prefs = prefs()
        FcitxApplication.resetHiddenSettings(prefs, directBoot = false) { folder.root.resolve("user_data_imported") }
        assertTrue(prefs.advanced.ignoreSystemCursor.getValue())
    }

    @Test
    fun inDirectBootTheMarkerIsLeftForTheNextStart() {
        val prefs = prefs()
        val marker = folder.newFile("user_data_imported")
        FcitxApplication.resetHiddenSettings(prefs, directBoot = true) { marker }
        assertTrue(prefs.advanced.ignoreSystemCursor.getValue())
        assertTrue(marker.exists())
    }

    // the starts after need not ask the system for the channels: the first left none
    @Test
    fun theNotificationChannelsAnOlderVersionMadeGoAtTheFirstStartAlone() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("setup", "setup", NotificationManager.IMPORTANCE_LOW))
        FcitxApplication.deleteNotificationChannels(manager, sp)
        assertTrue(manager.notificationChannels.isEmpty())
        manager.createNotificationChannel(NotificationChannel("later", "later", NotificationManager.IMPORTANCE_LOW))
        FcitxApplication.deleteNotificationChannels(manager, sp)
        assertEquals(listOf("later"), manager.notificationChannels.map { it.id })
    }
}
