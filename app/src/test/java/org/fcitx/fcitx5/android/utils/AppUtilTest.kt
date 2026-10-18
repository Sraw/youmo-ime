/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MainActivity, exported, opens from an intent only the pages the keyboard itself sends the user to. */
class AppUtilTest {

    @Test
    fun keyboardsOwnRoutesAreLaunchRoutes() {
        listOf(
            SettingsRoute.Keys,
            SettingsRoute.InputMethodList,
            SettingsRoute.Theme,
            SettingsRoute.InputMethodConfig("Pinyin", "pinyin"),
        ).forEach { assertTrue("$it", AppUtil.isLaunchRoute(it)) }
    }

    @Test
    fun otherPagesAreNot() {
        listOf(
            SettingsRoute.Clipboard,
            SettingsRoute.Developer,
            SettingsRoute.PinyinDict("content://elsewhere/words.dict"),
        ).forEach { assertFalse("$it", AppUtil.isLaunchRoute(it)) }
    }
}
