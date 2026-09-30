/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ContentResolverTest {

    private val resolver = RuntimeEnvironment.getApplication().contentResolver

    @Test
    fun aFileWithNoProviderToAskIsNamedByItsPath() {
        assertEquals("sogou.scel", resolver.queryFileName(Uri.parse("file:///sdcard/Download/sogou.scel")))
        assertEquals("1234", resolver.queryFileName(Uri.parse("content://nobody.here/files/1234")))
        assertNull(resolver.queryFileName(Uri.parse("file:///")))
        assertEquals("y.scel", resolver.queryFileName(Uri.parse("file:///x/..%2F..%2Fy.scel")))
        assertNull(resolver.queryFileName(Uri.parse("file:///x/..")))
    }
}
