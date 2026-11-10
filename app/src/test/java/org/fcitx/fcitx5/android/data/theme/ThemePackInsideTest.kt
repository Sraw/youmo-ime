/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The import's own "a file in its folder" check, behind the engine reader's (which since R20 refuses
 * `..` and any `/` first, so no import reaches this one): a name out of the folder, or a link out of it, is none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemePackInsideTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aFileOutOfItsFolderIsNotInside() {
        val folder = tmp.newFolder("pack")
        File(folder, "a.ttf").writeText("x")
        val out = tmp.newFile("b.ttf")
        Files.createSymbolicLink(File(folder, "link.ttf").toPath(), out.toPath())
        assertTrue(ThemePackFiles.inside(folder, "a.ttf"))
        assertFalse(ThemePackFiles.inside(folder, "../b.ttf"))
        assertFalse(ThemePackFiles.inside(folder, "link.ttf"))
        assertFalse(ThemePackFiles.inside(folder, "none.ttf"))
    }
}
