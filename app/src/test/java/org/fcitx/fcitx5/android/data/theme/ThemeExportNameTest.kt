/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A 3.0 theme exported as a pack is named as a pack's import takes it: a 3.0 name a pack may not have (a version's,
 * `.`-led, too long) made one, so the export always imports back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeExportNameTest {

    @Test
    fun aThreeZeroNameIsMadeAPacksName() {
        // each made name also one a pack's import takes (the cut-to-length one its edge)
        fun row(name: String, want: String) = {
            val made = ThemeFilesManager.packName(name)
            assertEquals(name, want, made)
            assertFalse(name, ThemePackFiles.badName(made))
        }
        assertRows(
            "a plain name: as it is" to row("mine", "mine"),
            "a version's name: the pack's would be read as one of its versions" to row("mine#dark", "mine"),
            "a name of two version endings: both gone" to row("mine#light#dark", "mine"),
            "`.`-led: the pack folder's own names are" to row(".hidden", "hidden"),
            "nothing left of dots: the default" to row("...", "theme"),
            "nothing left of a version's ending: the default" to row("#dark", "theme"),
            "a `/`: a pack's folder can have none" to row("a/b", "a_b"),
            "too long: cut to a pack's length" to row("x".repeat(ThemeReader.MAX_NAME + 10), "x".repeat(ThemeReader.MAX_NAME)),
            "`.`-led and cut to end in a version's name: that ending gone too" to
                row("." + "x".repeat(ThemeReader.MAX_NAME - 5) + "#dark" + "yyyy", "x".repeat(ThemeReader.MAX_NAME - 5)),
        )
    }
}
