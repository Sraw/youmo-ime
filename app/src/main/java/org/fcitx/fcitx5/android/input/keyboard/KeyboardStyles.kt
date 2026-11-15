/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs

/**
 * 竖屏键盘样式 and 横屏键盘样式 (061-wide-keyboard-style), the one way in: each shape's KeyboardStyleState as
 * stored, its default by the screen's width where none is ([KeyboardStyleState.default]). The keyboard's
 * toggles and the settings page write the shape's style and what is under it in one edit; the keyboard writes
 * the shape it was made for, not the one there is when it writes (a view taken out after a turn).
 */
object KeyboardStyles {

    /** The settings page's labels, in [KeyboardStyle]'s order. */
    val LABELS = listOf(R.string.keyboard_style_normal, R.string.keyboard_style_split, R.string.keyboard_style_floating, R.string.keyboard_style_one_handed)

    private val prefs: ThemePrefs get() = ThemeManager.prefs

    fun styleKey(landscape: Boolean): ThemePrefs.StyleKey = if (landscape) prefs.keyboardStyleLandscape else prefs.keyboardStylePortrait

    private fun underKey(landscape: Boolean): ThemePrefs.StyleKey = if (landscape) prefs.keyboardStyleUnderLandscape else prefs.keyboardStyleUnderPortrait

    /** The shape [landscape]'s, its default by [widthDp] where none is stored. */
    fun of(landscape: Boolean, widthDp: Int): KeyboardStyleState = KeyboardStyleState.of(
        KeyboardStyle.entries.firstOrNull { it.name == styleKey(landscape).getValue() },
        KeyboardStyleUnder.entries.firstOrNull { it.name == underKey(landscape).getValue() },
        KeyboardStyleState.default(landscape, widthDp),
    )

    fun of(s: KeyboardArea.Screen): KeyboardStyleState = of(s.landscape, s.widthDp)

    /** The style of the shape [ctx]'s window is now. */
    fun of(ctx: Context): KeyboardStyleState = of(KeyboardArea.screen(ctx))

    fun floating(ctx: Context): Boolean = of(ctx).floating

    /** Where 单手 puts the whole keyboard on [s]: at the side remembered, or the whole width where it is not 单手. */
    fun oneHandedDock(s: KeyboardArea.Screen): SplitKeyboard.Dock =
        if (of(s).oneHanded) prefs.oneHandedSide.getValue().dock else SplitKeyboard.Dock.FULL

    // the screen of this display the shape [landscape] is laid out on, as the input method has it (KeyboardArea.screens):
    // a settings page's own window may be half the display. A tablet is 横屏 both ways: 竖屏's then by the upright one
    private fun screenOf(ctx: Context, landscape: Boolean): KeyboardArea.Screen = screenOf(KeyboardArea.screens(ctx), landscape)

    private fun screenOf(screens: Pair<KeyboardArea.Screen, KeyboardArea.Screen>, landscape: Boolean): KeyboardArea.Screen =
        screens.let { (upright, turned) -> listOf(upright, turned).firstOrNull { it.landscape == landscape } ?: upright }

    /** What the settings page shows for the shape [landscape]: as stored, or its default on this display. */
    fun shown(ctx: Context, landscape: Boolean): KeyboardStyle = of(landscape, screenOf(ctx, landscape).widthDp).style

    /** Picked on the settings page. */
    fun set(ctx: Context, landscape: Boolean, to: KeyboardStyle) =
        write(landscape, of(landscape, screenOf(ctx, landscape).widthDp).set(to))

    /** The keyboard made on [built] floating or docked again (the handle, the toggles, its view taken out). */
    fun floatingOn(built: KeyboardArea.Screen) = write(built.landscape, of(built).floatingOn())

    fun floatingOff(built: KeyboardArea.Screen) = write(built.landscape, of(built).floatingOff())

    fun toggleFloating(built: KeyboardArea.Screen) = if (of(built).floating) floatingOff(built) else floatingOn(built)

    /** One-handed off: 普通 or split, as it was before (the arrow beside it). */
    fun oneHandedOff(built: KeyboardArea.Screen) = write(built.landscape, of(built).oneHandedOff())

    fun toggleOneHanded(built: KeyboardArea.Screen) = of(built).let { write(built.landscape, if (it.oneHanded) it.oneHandedOff() else it.oneHandedOn()) }

    /**
     * 分体时其他面板 is of use (§2.3): where a shape splits, or goes back to split as one-handed or floating is turned
     * off, on this display.
     */
    fun splitDockOffered(ctx: Context): Boolean {
        val screens = KeyboardArea.screens(ctx)
        return listOf(false, true).any { landscape -> offersSplitDock(of(landscape, screenOf(screens, landscape).widthDp)) }
    }

    private fun offersSplitDock(s: KeyboardStyleState): Boolean =
        s.splits || s.under == KeyboardStyleUnder.SPLIT || s.under == KeyboardStyleUnder.ONE_HANDED_OVER_SPLIT

    // the shape's two in one edit: the service and the input view read both as either changes
    private fun write(landscape: Boolean, state: KeyboardStyleState) {
        val style = styleKey(landscape)
        style.sharedPreferences.edit {
            putString(style.key, state.style.name)
            putString(underKey(landscape).key, state.under.name)
        }
    }
}
