/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.BuiltinThemeFiles
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A pack's theme as drawn is the instance's own: a copy equal to an older one (the system's colours set anew, the
 * packs listed again) draws its own theme, not the older one's kept, so a pack whose colours are `system()` follows
 * the wallpaper's colours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class SceneThemesPackTest {

    private val dir = File(appContext.cacheDir, "scene-themes-pack")

    @After
    fun clean() {
        SceneThemes.forgetAll()
        dir.deleteRecursively()
    }

    @Test
    fun aCopyEqualToAnOlderPackDrawsItsOwnTheme() {
        dir.mkdirs()
        File(dir, ThemePackFiles.THEME_XML).writeText(BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord))
        val old = Theme.Pack("mine#dark", true, "mine", dir.path, 1L)
        val look = ThemeManager.shownLook(old)
        assertSame(old.drawn, SceneThemes.of(old, look, KeyStyleChoice.FOLLOW))
        val again = old.copy()
        // equal, as the colours set anew make it, its theme read anew
        assertNotSame(old.drawn, again.drawn)
        assertSame(again.drawn, SceneThemes.of(again, look, KeyStyleChoice.FOLLOW))
    }

    // kept by the key style settings too: another choice draws anew, the same again is the one kept
    @Test
    fun theKeyStyleSettingsAreThePackDrawingsKey() {
        dir.mkdirs()
        File(dir, ThemePackFiles.THEME_XML).writeText(BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord))
        val pack = Theme.Pack("mine#dark", true, "mine", dir.path, 1L)
        val look = ThemeManager.shownLook(pack)
        val a = KeyStyleChoice(null, KeyStyleChoice.H_LOOSE, null, null)
        val b = KeyStyleChoice(null, null, KeyStyleChoice.V_COMPACT, null)
        val first = SceneThemes.of(pack, look, a)
        assertNotSame(first, SceneThemes.of(pack, look, b))
        assertSame(first, SceneThemes.of(pack, look, a))
        assertSame(pack.drawn, SceneThemes.of(pack, look, KeyStyleChoice.FOLLOW))
    }
}
