/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.Box
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.LookDefaults
import org.fcitx.fcitx5.android.input.theme.Palette
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

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

    /** All the theme draws with: a custom theme's own, a built-in or Monet one's its colours with [LookDefaults]. */
    abstract val look: ThemeLook

    /** The colours as [ThemeLook] holds them; the three of 3.0 as they were drawn before. */
    protected fun palette() = Palette(
        backgroundColor, barColor, keyboardColor, keyBackgroundColor, keyTextColor, candidateTextColor,
        candidateLabelColor, candidateCommentColor, altKeyBackgroundColor, altKeyTextColor,
        accentKeyBackgroundColor, accentKeyTextColor, keyPressHighlightColor, keyShadowColor,
        popupBackgroundColor, popupTextColor, spaceBarColor, dividerColor, clipboardEntryColor,
        genericActiveBackgroundColor, genericActiveForegroundColor,
    )

    open fun backgroundDrawable(keyBorder: Boolean = false): Drawable {
        return ColorDrawable(if (keyBorder) backgroundColor else keyboardColor)
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

        /**
         * The picture as the background image page edits it: its portrait cut (absolute paths),
         * the source, the brightness, the crop.
         */
        @Parcelize
        data class CustomBackground(
            val croppedFilePath: String,
            val srcFilePath: String,
            val brightness: Int = 70,
            val cropRect: Rect?,
            val cropRotation: Int = 0
        ) : Parcelable {
            fun toDrawable(): Drawable? {
                val cropped = File(croppedFilePath)
                if (!cropped.exists()) return null
                val bitmap = BitmapFactory.decodeFile(cropped.path) ?: return null
                return BitmapDrawable(appContext.resources, bitmap).apply {
                    colorFilter = DarkenColorFilter(100 - brightness)
                }
            }
        }

        val backgroundImage: CustomBackground?
            get() = look.picture?.let {
                CustomBackground(
                    it.portrait.file, it.src, it.brightness,
                    it.portrait.cropRect?.let { b -> Rect(b.left, b.top, b.right, b.bottom) },
                    it.portrait.rotation,
                )
            }

        /** This theme with [background] as its picture's portrait cut, source and brightness; under the keyboard if it was not. */
        fun withBackground(background: CustomBackground): Custom {
            val crop = Crop(background.croppedFilePath, background.cropRect?.let { Box(it.left, it.top, it.right, it.bottom) }, background.cropRotation)
            val picture = look.picture?.copy(src = background.srcFilePath, portrait = crop, brightness = background.brightness)
                ?: Picture(background.srcFilePath, crop, null, background.brightness, 0)
            return copy(look = look.copy(picture = picture, base = if (look.picture == null) BoardBase.Picture else look.base))
        }

        override fun backgroundDrawable(keyBorder: Boolean): Drawable {
            return backgroundImage?.toDrawable() ?: super.backgroundDrawable(keyBorder)
        }

    }

    @Parcelize
    data class Builtin(
        override val name: String,
        override val isDark: Boolean,
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
        override val genericActiveForegroundColor: Int
    ) : Theme() {

        // an alias to use 0xAARRGGBB color literal in code
        // because kotlin compiler treats `0xff000000` as Long, not Int
        constructor(
            name: String,
            isDark: Boolean,
            backgroundColor: Number,
            barColor: Number,
            keyboardColor: Number,
            keyBackgroundColor: Number,
            keyTextColor: Number,
            candidateTextColor: Number,
            candidateLabelColor: Number,
            candidateCommentColor: Number,
            altKeyBackgroundColor: Number,
            altKeyTextColor: Number,
            accentKeyBackgroundColor: Number,
            accentKeyTextColor: Number,
            keyPressHighlightColor: Number,
            keyShadowColor: Number,
            popupBackgroundColor: Number,
            popupTextColor: Number,
            spaceBarColor: Number,
            dividerColor: Number,
            clipboardEntryColor: Number,
            genericActiveBackgroundColor: Number,
            genericActiveForegroundColor: Number
        ) : this(
            name,
            isDark,
            backgroundColor.toInt(),
            barColor.toInt(),
            keyboardColor.toInt(),
            keyBackgroundColor.toInt(),
            keyTextColor.toInt(),
            candidateTextColor.toInt(),
            candidateLabelColor.toInt(),
            candidateCommentColor.toInt(),
            altKeyBackgroundColor.toInt(),
            altKeyTextColor.toInt(),
            accentKeyBackgroundColor.toInt(),
            accentKeyTextColor.toInt(),
            keyPressHighlightColor.toInt(),
            keyShadowColor.toInt(),
            popupBackgroundColor.toInt(),
            popupTextColor.toInt(),
            spaceBarColor.toInt(),
            dividerColor.toInt(),
            clipboardEntryColor.toInt(),
            genericActiveBackgroundColor.toInt(),
            genericActiveForegroundColor.toInt()
        )

        @IgnoredOnParcel
        override val look: ThemeLook by lazy { LookDefaults.look(palette()) }

        fun deriveCustomNoBackground(name: String) = Custom(name, isDark, look)

        fun deriveCustomBackground(
            name: String,
            croppedBackgroundImage: String,
            originBackgroundImage: String,
            brightness: Int = 70,
            cropBackgroundRect: Rect? = null,
            cropBackgroundRotation: Int = 0
        ) = Custom(
            name,
            isDark,
            LookDefaults.look(
                palette(),
                Picture(
                    src = originBackgroundImage,
                    portrait = Crop(
                        croppedBackgroundImage,
                        cropBackgroundRect?.let { Box(it.left, it.top, it.right, it.bottom) },
                        cropBackgroundRotation
                    ),
                    landscape = null,
                    brightness = brightness,
                    blurDp = 0
                )
            )
        )
    }

    @Parcelize
    data class Monet(
        override val name: String,
        override val isDark: Boolean,
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
        override val genericActiveForegroundColor: Int
    ) : Theme() {
        constructor(
            isDark: Boolean,
            surfaceContainer: Int,
            surfaceContainerHigh: Int,
            surfaceBright: Int,
            onSurface: Int,
            primary: Int,
            onPrimary: Int,
            secondaryContainer: Int,
            onSurfaceVariant: Int,
        ) : this(
            name = "Monet" + if (isDark) "Dark" else "Light",
            isDark = isDark,
            backgroundColor = surfaceContainer,
            barColor = surfaceContainerHigh,
            keyboardColor = surfaceContainer,
            keyBackgroundColor = surfaceBright,
            keyTextColor = onSurface,
            candidateTextColor = onSurface,
            candidateLabelColor = onSurface,
            candidateCommentColor = onSurfaceVariant,
            altKeyBackgroundColor = secondaryContainer,
            altKeyTextColor = onSurfaceVariant,
            accentKeyBackgroundColor = primary,
            accentKeyTextColor = onPrimary,
            keyPressHighlightColor = onSurface.alpha(if (isDark) 0.2f else 0.12f),
            keyShadowColor = 0x000000,
            popupBackgroundColor = surfaceContainer,
            popupTextColor = onSurface,
            spaceBarColor = surfaceBright,
            dividerColor = surfaceBright,
            clipboardEntryColor = surfaceBright,
            genericActiveBackgroundColor = primary,
            genericActiveForegroundColor = onPrimary
        )

        // the key style settings before 3.0, if one was in use when they were settled: not copied, to follow the wallpaper
        @IgnoredOnParcel
        override val look: ThemeLook by lazy {
            LookDefaults.look(palette()).let { ThemeLookMigration.monetLook?.applyTo(it) ?: it }
        }

        @OptIn(ExperimentalStdlibApi::class)
        fun toCustom() = Custom(
            name = name + "#" + this.accentKeyBackgroundColor.toHexString(), // Use primary color as identifier
            isDark = isDark,
            look = look
        )
    }
}
