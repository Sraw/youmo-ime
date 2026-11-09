/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.Palette
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

sealed class Theme : Parcelable {

    abstract val name: String
    abstract val isDark: Boolean

    abstract val backgroundColor: Int
    abstract val barColor: Int
    abstract val keyboardColor: Int

    abstract val keyBackgroundColor: Int
    abstract val keyTextColor: Int

    //  Color of candidate text
    abstract val candidateTextColor: Int
    abstract val candidateLabelColor: Int
    abstract val candidateCommentColor: Int

    abstract val altKeyBackgroundColor: Int
    abstract val altKeyTextColor: Int

    abstract val accentKeyBackgroundColor: Int
    abstract val accentKeyTextColor: Int

    abstract val keyPressHighlightColor: Int
    abstract val keyShadowColor: Int

    abstract val popupBackgroundColor: Int
    abstract val popupTextColor: Int

    abstract val spaceBarColor: Int
    abstract val dividerColor: Int
    abstract val clipboardEntryColor: Int

    abstract val genericActiveBackgroundColor: Int
    abstract val genericActiveForegroundColor: Int

    /** All the theme draws with: a custom theme's own, a built-in one's its colours with [LookDefaults]. */
    abstract val look: ThemeLook

    /**
     * The user's own, saved as a file: all of it is its [look] (theme file 3.0), the colours too;
     * its files by absolute path in memory, by name in the file ([ThemeFilesManager]).
     */
    @Parcelize
    @TypeParceler<ThemeLook, ThemeLookParceler>
    data class Custom(
        override val name: String,
        override val isDark: Boolean,
        override val look: ThemeLook,
    ) : Theme() {
        override val backgroundColor get() = look.palette.backgroundColor
        override val barColor get() = look.palette.barColor
        override val keyboardColor get() = look.palette.keyboardColor
        override val keyBackgroundColor get() = look.palette.keyBackgroundColor
        override val keyTextColor get() = look.palette.keyTextColor
        override val candidateTextColor get() = look.palette.candidateTextColor
        override val candidateLabelColor get() = look.palette.candidateLabelColor
        override val candidateCommentColor get() = look.palette.candidateCommentColor
        override val altKeyBackgroundColor get() = look.palette.altKeyBackgroundColor
        override val altKeyTextColor get() = look.palette.altKeyTextColor
        override val accentKeyBackgroundColor get() = look.palette.accentKeyBackgroundColor
        override val accentKeyTextColor get() = look.palette.accentKeyTextColor
        override val keyPressHighlightColor get() = look.palette.keyPressHighlightColor
        override val keyShadowColor get() = look.palette.keyShadowColor
        override val popupBackgroundColor get() = look.palette.popupBackgroundColor
        override val popupTextColor get() = look.palette.popupTextColor
        override val spaceBarColor get() = look.palette.spaceBarColor
        override val dividerColor get() = look.palette.dividerColor
        override val clipboardEntryColor get() = look.palette.clipboardEntryColor
        override val genericActiveBackgroundColor get() = look.palette.genericActiveBackgroundColor
        override val genericActiveForegroundColor get() = look.palette.genericActiveForegroundColor
    }

    /**
     * A built-in theme's version: one of the files of keyboard-scene's src/commonMain/themes ([BuiltinThemeFiles]),
     * its colours that version's colour table (the 24 of ThemeReader.REQUIRED_COLORS), drawn as LookDefaults with
     * them, which is what the file's styles say (BuiltinThemeEquivalenceTest). [name] is how the settings refer to
     * it: the file's name, and its version where it has two (`pixel#dark`). Monet's ([followsSystem]) colours are
     * the system's, read again when they change; its keys may keep the key style of before 3.0 (ThemeLookMigration).
     */
    @Parcelize
    data class Builtin(
        override val name: String,
        override val isDark: Boolean,
        /** Its file's theme: the versions of one file are one card in the list. */
        val file: String,
        val followsSystem: Boolean,
        override val backgroundColor: Int,
        override val barColor: Int,
        override val keyboardColor: Int,
        override val keyBackgroundColor: Int,
        override val keyTextColor: Int,
        override val candidateTextColor: Int,
        override val candidateLabelColor: Int,
        override val candidateCommentColor: Int,
        override val altKeyBackgroundColor: Int,
        override val altKeyTextColor: Int,
        override val accentKeyBackgroundColor: Int,
        override val accentKeyTextColor: Int,
        override val keyPressHighlightColor: Int,
        override val keyShadowColor: Int,
        override val popupBackgroundColor: Int,
        override val popupTextColor: Int,
        override val spaceBarColor: Int,
        override val dividerColor: Int,
        override val clipboardEntryColor: Int,
        override val genericActiveBackgroundColor: Int,
        override val genericActiveForegroundColor: Int,
        val keyBorderColor: Int,
        val keyHintColor: Int,
        val toolbarIconColor: Int,
    ) : Theme() {

        /** Its colours as [ThemeLook] holds them. */
        fun palette() = Palette(
            backgroundColor, barColor, keyboardColor, keyBackgroundColor, keyTextColor, candidateTextColor,
            candidateLabelColor, candidateCommentColor, altKeyBackgroundColor, altKeyTextColor,
            accentKeyBackgroundColor, accentKeyTextColor, keyPressHighlightColor, keyShadowColor,
            popupBackgroundColor, popupTextColor, spaceBarColor, dividerColor, clipboardEntryColor,
            genericActiveBackgroundColor, genericActiveForegroundColor, keyBorderColor, keyHintColor, toolbarIconColor,
        )

        @IgnoredOnParcel
        override val look: ThemeLook by lazy {
            LookDefaults.look(palette()).let { if (followsSystem) ThemeLookMigration.monetLook?.applyTo(it) ?: it else it }
        }

        /** Monet's look as it is now, fixed, as a theme of the user's: exported, it no longer follows the wallpaper. */
        @OptIn(ExperimentalStdlibApi::class)
        fun toCustom() = Custom(
            // its primary colour tells two apart; named as 0.5 named it (MonetLight#ff415f91)
            name = (BuiltinThemeFiles.legacyName(name) ?: name) + "#" + accentKeyBackgroundColor.toHexString(),
            isDark = isDark,
            look = look,
        )

        companion object {
            /** [theme], a built-in file read and picked as one of its versions, referred to as [name]. */
            fun of(theme: SceneTheme, name: String, followsSystem: Boolean): Builtin {
                fun c(n: String) = theme.colors.getValue(n).value
                return Builtin(
                    name, theme.dark, theme.name, followsSystem,
                    c("background"), c("bar"), c("keyboard"), c("key"), c("keyText"), c("candidateText"), c("candidateLabel"),
                    c("candidateComment"), c("altKey"), c("altKeyText"), c("accentKey"), c("accentKeyText"), c("keyPressHighlight"),
                    c("keyShadow"), c("popupBackground"), c("popupText"), c("spaceBar"), c("divider"), c("clipboardEntry"),
                    c("genericActiveBackground"), c("genericActiveForeground"), c("keyBorder"), c("keyHint"), c("toolbarIcon"),
                )
            }
        }
    }
}
