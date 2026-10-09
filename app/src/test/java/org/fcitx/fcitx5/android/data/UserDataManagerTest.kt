/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A backup's settings that have no UI are put back to their defaults at the start after its import. */
@RunWith(RobolectricTestRunner::class)
// the real application: the manager's directories come from appContext
@Config(application = FcitxApplication::class)
class UserDataManagerTest {

    @Test
    fun anImportLeavesTheNextStartToResetTheSettingsWithoutAUi() {
        val marker = importedUserDataMarker(appContext)
        marker.delete()
        val metadata = """{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":1,"versionName":"1","exportTime":0}"""
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry("metadata.json"))
                it.write(metadata.encodeToByteArray())
            }
        }.toByteArray().inputStream()
        UserDataManager.import(zip).getOrThrow()
        assertTrue(marker.exists())
    }
}
