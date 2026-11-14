/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 横屏 has one source, [KeyboardArea.landscape] (ScreenShape's rule: turned, or 600 dp wide): a read of the
 * orientation itself elsewhere lays a foldable's inner screen out as portrait in one part and landscape in
 * another (061-wide-as-landscape §2.1).
 */
class LandscapeSourcesTest {

    @Test
    fun onlyTheseReadTheOrientation() {
        val root = File("src/main/java/org/fcitx/fcitx5/android")
        // the constants, or an orientation compared however it is reached (`config.orientation == 2`,
        // `newConfig?.orientation != o`, `it.orientation == …`); not the swipe slots' own SwipeOrientation
        val read = Regex("ORIENTATION_(LANDSCAPE|PORTRAIT)|[cC]onfig(uration)?\\)?\\.orientation\\b|\\.orientation\\s*[=!]=(?!\\s*SwipeOrientation\\b)")
        val readers = root.walk().filter { it.extension == "kt" }
            .filter { f -> read.containsMatchIn(f.readText()) }
            .map { it.relativeTo(root).path }
            .sorted().toList()
        assertEquals(
            listOf(
                // the rule's own source
                "input/keyboard/KeyboardArea.kt",
                // the theme page's own two columns, the page's window and not the keyboard's shape
                "ui/main/settings/theme/ThemeFragment.kt",
                // the device's report says the orientation as it is, beside the keyboard's 横屏
                "utils/DeviceInfo.kt",
            ),
            readers,
        )
    }
}
