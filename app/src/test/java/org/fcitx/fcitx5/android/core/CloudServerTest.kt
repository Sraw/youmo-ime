/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.remote.CloudConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CloudServerTest {

    private val key = "0123456789ABCDEF".repeat(4)
    private val app = RuntimeEnvironment.getApplication()
    private val path = File(app.noBackupFilesDir, "cloud.properties")
    private val cloud = CloudServer(path)

    @Test
    fun aServerOnlyWhileOnForWhereItMaySendReadAtEachQuestion() {
        assertNull(cloud.current())
        cloud.save(CloudConfig().withServer("https://example.com:8765"))
        assertNull(cloud.current())
        cloud.save(cloud.config().on(key))
        val server = cloud.current()
        assertNotNull(server)
        assertSame(server, cloud.current())
        cloud.save(cloud.config().withToken("s3cret"))
        assertNotSame(server, cloud.current())
        // a new address is off again
        cloud.save(cloud.config().withServer("https://example.org"))
        assertNull(cloud.current())
    }

    @Test
    fun wordsAreAskedForOnceADayAndOnlyWhileOn() {
        val stamp = File(app.noBackupFilesDir, "cloud-words.checked")
        var day = 1_700_000_000_000L
        cloud.updateWords(day) { }
        assertFalse(stamp.exists())
        // on, to a port nothing listens on: asked (and the asking fails on its own thread), stamped
        cloud.save(CloudConfig().withServer("https://127.0.0.1:1").on(key))
        cloud.updateWords(day) { }
        assertEquals(day, stamp.lastModified())
        // not again today
        cloud.updateWords(day + 60 * 60 * 1000) { }
        assertEquals(day, stamp.lastModified())
        day += 25 * 60 * 60 * 1000
        cloud.updateWords(day) { }
        assertEquals(day, stamp.lastModified())
    }

    @Test
    fun keptOutOfTheExportedAndBackedUpPreferences() {
        cloud.save(CloudConfig().withServer("https://127.0.0.1:8765").withToken("s3cret").on(key))
        assertTrue(path.readText().contains("s3cret"))
        assertTrue(File(app.applicationInfo.dataDir, "shared_prefs").walk().none { it.isFile && it.readText().contains("s3cret") })
        // read back by another, as after a restart
        assertEquals(CloudConfig(true, "https://127.0.0.1:8765", "s3cret", key), CloudServer(path).config())
    }
}
