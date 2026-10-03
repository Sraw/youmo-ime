/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserDataOriginTest {

    private val root = "io.github.sraw.youmo"

    private fun of(exporter: String, applicationId: String) = UserDataOrigin.of(exporter, applicationId, root)

    @Test
    fun theAppsOwnDataIsImportedAsItIs() {
        assertEquals(UserDataOrigin.Own, of("io.github.sraw.youmo", "io.github.sraw.youmo"))
        assertEquals(UserDataOrigin.Own, of("io.github.sraw.youmo.debug", "io.github.sraw.youmo.debug"))
    }

    @Test
    fun dataFromBeforeTheRenamingIsImportedByTheSameBuildTypeWithItsPreferencesRenamed() {
        assertEquals(
            UserDataOrigin.Legacy("org.fcitx.fcitx5.android_preferences.xml", "io.github.sraw.youmo_preferences.xml"),
            of("org.fcitx.fcitx5.android", "io.github.sraw.youmo")
        )
        assertEquals(
            UserDataOrigin.Legacy(
                "org.fcitx.fcitx5.android.debug_preferences.xml",
                "io.github.sraw.youmo.debug_preferences.xml"
            ),
            of("org.fcitx.fcitx5.android.debug", "io.github.sraw.youmo.debug")
        )
    }

    @Test
    fun otherAppsAndOtherBuildTypesAreNot() {
        assertNull(of("org.fcitx.fcitx5.android.debug", "io.github.sraw.youmo"))
        assertNull(of("org.fcitx.fcitx5.android", "io.github.sraw.youmo.debug"))
        assertNull(of("io.github.sraw.youmo.debug", "io.github.sraw.youmo"))
        assertNull(of("com.example.ime", "io.github.sraw.youmo"))
    }

    @Test
    fun anIdOutsideTheRootHasNoLegacy() {
        assertNull(UserDataOrigin.of("org.fcitx.fcitx5.android", "com.example.other", root))
    }
}
