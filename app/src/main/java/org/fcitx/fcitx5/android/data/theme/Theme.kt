/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.Palette
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import java.io.File
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

sealed class Theme : Parcelable {

    abstract val name: String
    abstract val isDark: Boolean

    abstract val palette: Palette

    val backgroundColor: Int get() = palette.backgroundColor
    val barColor: Int get() = palette.barColor
    val keyboardColor: Int get() = palette.keyboardColor
    val keyBackgroundColor: Int get() = palette.keyBackgroundColor
    val keyTextColor: Int get() = palette.keyTextColor
    val candidateTextColor: Int get() = palette.candidateTextColor
    val candidateLabelColor: Int get() = palette.candidateLabelColor
    val candidateCommentColor: Int get() = palette.candidateCommentColor
    val altKeyBackgroundColor: Int get() = palette.altKeyBackgroundColor
    val altKeyTextColor: Int get() = palette.altKeyTextColor
    val accentKeyBackgroundColor: Int get() = palette.accentKeyBackgroundColor
    val accentKeyTextColor: Int get() = palette.accentKeyTextColor
    val keyPressHighlightColor: Int get() = palette.keyPressHighlightColor
    val keyShadowColor: Int get() = palette.keyShadowColor
    val popupBackgroundColor: Int get() = palette.popupBackgroundColor
    val popupTextColor: Int get() = palette.popupTextColor
    val spaceBarColor: Int get() = palette.spaceBarColor
    val dividerColor: Int get() = palette.dividerColor
    val clipboardEntryColor: Int get() = palette.clipboardEntryColor
    val genericActiveBackgroundColor: Int get() = palette.genericActiveBackgroundColor
    val genericActiveForegroundColor: Int get() = palette.genericActiveForegroundColor

    /** A pack's covers only what the views round the keys take; its keys are drawn from its file ([Pack.scene]). */
    abstract val look: ThemeLook

    /** The versions of one [file] ([BuiltinThemeFiles.ref]: `pixel#dark`) are one card in the list. */
    interface Versioned {
        val name: String
        val isDark: Boolean
        val file: String
    }

    /** All of it is its [look] (theme file 3.0); its files by absolute path in memory, by name on disk ([ThemeFilesManager]). */
    @Parcelize
    @TypeParceler<ThemeLook, ThemeLookParceler>
    data class Custom(
        override val name: String,
        override val isDark: Boolean,
        override val look: ThemeLook,
    ) : Theme() {
        override val palette: Palette get() = look.palette
    }

    /**
     * A version of a keyboard-scene built-in theme file, drawn as LookDefaults with its 24 colours, which is what the file's
     * styles say (BuiltinThemeEquivalenceTest). [name] is the settings' reference (`pixel#dark`). Monet's colours
     * ([followsSystem]) are the system's; its keys may keep the pre-3.0 key style (ThemeLookMigration).
     */
    @Parcelize
    @TypeParceler<Palette, PaletteParceler>
    data class Builtin(
        override val name: String,
        override val isDark: Boolean,
        override val file: String,
        val followsSystem: Boolean,
        override val palette: Palette,
    ) : Theme(), Versioned {

        @IgnoredOnParcel
        override val look: ThemeLook by lazy {
            LookDefaults.look(palette).let { if (followsSystem) ThemeLookMigration.monetLook?.applyTo(it) ?: it else it }
        }

        /** Monet's look fixed as a user theme: exported, it no longer follows the wallpaper. */
        @OptIn(ExperimentalStdlibApi::class)
        fun toCustom() = Custom(
            // its primary colour tells two apart; named as 0.5 did (MonetLight#ff415f91)
            name = (BuiltinThemeFiles.legacyName(name) ?: name) + "#" + accentKeyBackgroundColor.toHexString(),
            isDark = isDark,
            look = look,
        )

        companion object {
            fun of(theme: SceneTheme, name: String, followsSystem: Boolean): Builtin {
                fun c(n: String) = theme.colors.getValue(n).value
                return Builtin(
                    name, theme.dark, theme.name, followsSystem,
                    Palette(
                        c("background"), c("bar"), c("keyboard"), c("key"), c("keyText"), c("candidateText"), c("candidateLabel"),
                        c("candidateComment"), c("altKey"), c("altKeyText"), c("accentKey"), c("accentKeyText"), c("keyPressHighlight"),
                        c("keyShadow"), c("popupBackground"), c("popupText"), c("spaceBar"), c("divider"), c("clipboardEntry"),
                        c("genericActiveBackground"), c("genericActiveForeground"), c("keyBorder"), c("keyHint"), c("toolbarIcon"),
                    ),
                )
            }
        }
    }

    /**
     * A theme 4.0 pack version in `v4/<file>/` ([dir]); [stamp] its `theme.xml` time (a reimport is another theme). Keys,
     * bar and popups are drawn from the file ([scene], read when first drawn; no text kept); views round them take [look].
     */
    @Parcelize
    data class Pack(
        override val name: String,
        override val isDark: Boolean,
        override val file: String,
        val dir: String,
        val stamp: Long,
        /** One of the app's ([BuiltinPacks]): listed with the built-ins, never deleted. */
        val builtin: Boolean = false,
    ) : Theme(), Versioned {

        /** This version as the engine reads it, with current system colours; the built-in theme's where the file no longer reads. */
        @IgnoredOnParcel
        val scene: SceneTheme by lazy { ThemePackFiles.scene(dir, isDark) }

        /** Files by their paths ([ThemePackFiles.drawn]). */
        @IgnoredOnParcel
        val drawn: SceneTheme by lazy { ThemePackFiles.drawn(scene, dir) }

        @IgnoredOnParcel
        override val palette: Palette by lazy { ThemePackFiles.palette(scene) }

        @IgnoredOnParcel
        override val look: ThemeLook by lazy { ThemePackFiles.look(scene, palette, dir) }

        fun path(name: String): String = File(dir, name).path
    }
}

/** By name as a theme file holds it ([ThemeLookParceler]). */
internal object PaletteParceler : Parceler<Palette> {
    override fun create(parcel: Parcel): Palette = with(ThemeLookParceler) { create(parcel).palette }

    override fun Palette.write(parcel: Parcel, flags: Int) = with(ThemeLookParceler) { LookDefaults.look(this@write).write(parcel, flags) }
}
