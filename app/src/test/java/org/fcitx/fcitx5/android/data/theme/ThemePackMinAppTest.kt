/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePackMinAppTest {
    // a pack's min-app refused by the app named so: what the import does with the two
    private fun refuses(minApp: String, versionName: String) =
        ThemePackFiles.minAppRefused(minApp, ThemePackFiles.minAppVersion(versionName))

    @Test
    fun aReleaseAndItsPreReleasesTakeThePacksOfTheirVersion() {
        assertEquals("0.6.0", ThemePackFiles.minAppVersion("0.6.0"))
        assertFalse(refuses("0.6.0", "0.6.0"))
        assertFalse(refuses("0.6.0", "0.6.0-beta1"))
        assertFalse(refuses("0.6.0", "0.6.0-rc.2"))
        assertTrue(refuses("0.6.1", "0.6.0-beta1"))
        assertTrue(refuses("0.6.0", "0.5.2"))
    }

    @Test
    fun aBuildPastATagOrUntaggedChecksNothing() {
        assertNull(ThemePackFiles.minAppVersion("0.5.2-791-g3c7458e44"))
        assertNull(ThemePackFiles.minAppVersion("0.6.0-beta1-3-gabc1234"))
        assertNull(ThemePackFiles.minAppVersion("0.5.2-untagged"))
        assertFalse(refuses("0.6.0", "0.5.2-791-g3c7458e44"))
    }
}
