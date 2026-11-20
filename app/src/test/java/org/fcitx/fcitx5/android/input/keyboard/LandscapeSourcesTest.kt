/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 横屏 has one source, [KeyboardArea.landscape]: an orientation read elsewhere lays a foldable's inner screen out two ways. */
class LandscapeSourcesTest {

    @Test
    fun onlyTheseReadTheOrientation() {
        val root = File("src/main/java/org/fcitx/fcitx5/android")
        // the constants, or an orientation compared however reached; not the swipe slots' SwipeOrientation
        val read = Regex("ORIENTATION_(LANDSCAPE|PORTRAIT)|[cC]onfig(uration)?\\)?\\.orientation\\b|\\.orientation\\s*[=!]=(?!\\s*SwipeOrientation\\b)")
        val readers = root.walk().filter { it.extension == "kt" }
            .filter { f -> read.containsMatchIn(f.readText()) }
            .map { it.relativeTo(root).path }
            .sorted().toList()
        assertEquals(
            listOf(
                "input/keyboard/KeyboardArea.kt",
                // the theme page's own two columns, not the keyboard's shape
                "ui/main/settings/theme/ThemeFragment.kt",
                // the device report says the orientation as it is
                "utils/DeviceInfo.kt",
            ),
            readers,
        )
    }
}
