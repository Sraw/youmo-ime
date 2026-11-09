/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.annotation.ColorInt
import androidx.core.view.WindowCompat
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.NavbarBackground
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneWindow
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.utils.DeviceUtil

class NavigationBarManager {

    private val navbarBackground by ThemeManager.prefs.navbarBackground

    private var shouldUpdateNavbarForeground = false
    private var shouldUpdateNavbarBackground = false

    // the touch keyboard is up (not the hardware keyboard's candidates alone), and where its board is
    // ([SceneWindow.placement]): the theme's `@one-handed`, `@split` navigation bar
    private var virtual = true
    private var placement: Set<String> = emptySet()

    private fun Window.useSystemNavbarBackground(enabled: Boolean) {
        // 35+ enforces edge to edge and we must draw behind navbar
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            WindowCompat.setDecorFitsSystemWindows(this, enabled)
        }
    }

    private fun Window.setNavbarBackgroundColor(@ColorInt color: Int) {
        /**
         * Why on earth does it deprecated? It says
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r3/core/java/android/view/Window.java#2720
         * "If the app targets VANILLA_ICE_CREAM or above, the color will be transparent and cannot be changed"
         * but it's only true on 35+. Older versions still need this.
         */
        @Suppress("DEPRECATION")
        navigationBarColor = color
    }

    private fun Window.enforceNavbarContrast(enforced: Boolean) {
        isNavigationBarContrastEnforced = enforced
    }

    private fun evaluateWithVirtualKeyboard(window: Window) {
        // floating, the app is under the bar, not the keyboard: the system's, as with None
        val background = if (ThemeManager.prefs.floatingKeyboard.getValue()) NavbarBackground.None else navbarBackground
        when (background) {
            NavbarBackground.None -> {
                shouldUpdateNavbarForeground = false
                shouldUpdateNavbarBackground = false
                window.useSystemNavbarBackground(true)
                window.enforceNavbarContrast(true)
            }
            NavbarBackground.ColorOnly -> {
                shouldUpdateNavbarForeground = true
                shouldUpdateNavbarBackground = true
                window.useSystemNavbarBackground(true)
                window.enforceNavbarContrast(false)
            }
            NavbarBackground.Full -> {
                shouldUpdateNavbarForeground = true
                shouldUpdateNavbarBackground = false
                window.useSystemNavbarBackground(false)
                window.setNavbarBackgroundColor(Color.TRANSPARENT)
                window.enforceNavbarContrast(false)
                // it seems One UI 7.0 (Android 15) does not allow drawing behind navbar
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && DeviceUtil.isSamsungOneUI) {
                    shouldUpdateNavbarBackground = true
                }
            }
        }
    }

    fun evaluate(window: Window, isVirtualKeyboard: Boolean) {
        virtual = isVirtualKeyboard
        if (isVirtualKeyboard) {
            evaluateWithVirtualKeyboard(window)
        } else {
            // always color navbar to avoid CandidatesView being drawn behind it
            shouldUpdateNavbarForeground = true
            shouldUpdateNavbarBackground = true
            window.useSystemNavbarBackground(true)
            window.enforceNavbarContrast(false)
        }
        update(window)
    }

    /** The touch keyboard's board placed anew: the bar's colour of its [placement] ([SceneWindow.placement]). */
    fun place(window: Window, placement: Set<String>) {
        if (placement == this.placement) return
        this.placement = placement
        update(window)
    }

    private fun update(window: Window) {
        val theme = ThemeManager.activeTheme
        // the theme's (`navbar { color }`, 060-render-p3 T7a); null, follow: the colour under the keys. The
        // floating keyboard's is the app's under it; the hardware keyboard's (its candidates alone) the theme's
        val floating = virtual && ThemeManager.prefs.floatingKeyboard.getValue()
        val set = if (floating) null else SceneWindow.navbar(theme, SceneWindow.scenes(window.context, theme, placement = if (virtual) placement else emptySet()))
        if (shouldUpdateNavbarForeground) {
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightNavigationBars = set?.let(SceneWindow::darkButtonsOn) ?: !theme.isDark
        }
        if (shouldUpdateNavbarBackground) {
            window.setNavbarBackgroundColor(
                // the colour under the keys, as the theme has it; under a picture the theme's dark of it
                set ?: if (ThemeManager.shownLook(theme).base == BoardBase.Picture) theme.backgroundColor else ThemeColors.board(ThemeManager.shownLook(theme).palette, ThemeManager.shownLook(theme).keys)
            )
        }
    }
}
