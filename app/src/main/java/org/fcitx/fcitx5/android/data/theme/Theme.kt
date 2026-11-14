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
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.Palette
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import java.io.File
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

sealed class Theme : Parcelable {

    abstract val name: String
    abstract val isDark: Boolean

    /** Its colours: a custom theme's [look]'s, a built-in one's version's colour table, a pack's what its file says. */
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

    /**
     * All the theme draws with: a custom theme's own, a built-in one's its colours with [LookDefaults]; a pack's
     * what of it the views round the keys take (its keys are drawn as its file says: [Pack.scene]).
     */
    abstract val look: ThemeLook

    /**
     * A theme of a theme 4.0 file, one of whose versions it is ([BuiltinThemeFiles.ref]: `pixel#dark`, or the
     * file's name alone where it has one): the versions of one [file] are one card in the list.
     */
    interface Versioned {
        val name: String
        val isDark: Boolean
        val file: String
    }

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
        override val palette: Palette get() = look.palette
    }

    /**
     * A built-in theme's version: one of the files of keyboard-scene's src/commonMain/themes ([BuiltinThemeFiles]),
     * its colours that version's colour table (the 24 of ThemeReader.REQUIRED_COLORS), drawn as LookDefaults with
     * them, which is what the file's styles say (BuiltinThemeEquivalenceTest). [name] is how the settings refer to
     * it: the file's name, and its version where it has two (`pixel#dark`). Monet's ([followsSystem]) colours are
     * the system's, read again when they change; its keys may keep the key style of before 3.0 (ThemeLookMigration).
     */
    @Parcelize
    @TypeParceler<Palette, PaletteParceler>
    data class Builtin(
        override val name: String,
        override val isDark: Boolean,
        /** Its file's theme: the versions of one file are one card in the list. */
        override val file: String,
        val followsSystem: Boolean,
        override val palette: Palette,
    ) : Theme(), Versioned {

        @IgnoredOnParcel
        override val look: ThemeLook by lazy {
            LookDefaults.look(palette).let { if (followsSystem) ThemeLookMigration.monetLook?.applyTo(it) ?: it else it }
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
     * A theme 4.0 pack of the user's (060-render-p3 T8a), as one of its versions: [file] its name and its folder's
     * (`v4/<file>/` in the themes', [dir]), [stamp] its `theme.xml`'s time (a pack imported again another theme).
     * Its keys, bar and popups are drawn as the file says ([scene], read from it when first drawn: no pack's text is
     * kept); the views round them take its colours and what [look] makes of its picture and fonts.
     */
    @Parcelize
    data class Pack(
        override val name: String,
        override val isDark: Boolean,
        override val file: String,
        val dir: String,
        val stamp: Long,
        /** One of the app's ([BuiltinPacks]): listed with the built-in themes, never deleted. */
        val builtin: Boolean = false,
    ) : Theme(), Versioned {

        /**
         * Its theme as the engine reads it, this version ([ThemePackFiles.scene]: with the system's colours as they
         * are; the built-in theme's where its file no longer reads).
         */
        @IgnoredOnParcel
        val scene: SceneTheme by lazy { ThemePackFiles.scene(dir, isDark) }

        /** [scene] as drawn: its files by their paths ([ThemePackFiles.drawn]). */
        @IgnoredOnParcel
        val drawn: SceneTheme by lazy { ThemePackFiles.drawn(scene, dir) }

        @IgnoredOnParcel
        override val palette: Palette by lazy { ThemePackFiles.palette(scene) }

        @IgnoredOnParcel
        override val look: ThemeLook by lazy { ThemePackFiles.look(scene, palette, dir) }

        /** The file of it a theme 4 names [name] (by its name in the pack's folder). */
        fun path(name: String): String = File(dir, name).path
    }
}

/** A built-in theme's [Palette] in a parcel, by name ([PaletteJson]) as a theme file holds it. */
internal object PaletteParceler : Parceler<Palette> {
    override fun create(parcel: Parcel): Palette = Json.decodeFromString(PaletteJson.serializer(), parcel.readString()!!).toPalette()

    override fun Palette.write(parcel: Parcel, flags: Int) = parcel.writeString(Json.encodeToString(PaletteJson.serializer(), PaletteJson.of(this)))
}
