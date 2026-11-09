/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.app.Application
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.io.File

// the guards of the shell's pack import (an exported provider): who may call, what a name may be, what is left
// in the cache. The import itself is PackStore.import's, tested there
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PackImportProviderTest {

    private lateinit var provider: PackImportProvider
    private val dir get() = File(RuntimeEnvironment.getApplication().cacheDir, "uat-packs")

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(PackImportProvider::class.java).create().get()
        dir.deleteRecursively()
        ShadowBinder.setCallingUid(2000)
    }

    @After
    fun tearDown() {
        ShadowBinder.reset()
        dir.deleteRecursively()
    }

    private fun write(name: String) = provider.openFile(Uri.parse("content://x.uat.packs/$name"), "w").close()

    @Test
    fun anAppIsNotLetIn() {
        ShadowBinder.setCallingUid(10123)
        assertThrows(SecurityException::class.java) { provider.call("state", null, null) }
        assertThrows(SecurityException::class.java) { write("text.youmopack") }
    }

    @Test
    fun aNameNotAPath() {
        for (bad in listOf("..", ".", "%2F..%2Fx")) {
            assertThrows(IllegalArgumentException::class.java) { write(bad) }
        }
    }

    // a run stopped between its write and its import: the pack left is gone when the next is written
    @Test
    fun aNewPackWrittenTakesTheOneLeftAway() {
        write("text.youmopack")
        write("voice.youmopack")
        assertEquals(listOf("voice.youmopack"), dir.list()?.toList())
    }
}
