/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.RasterIcon
import org.fcitx.fcitx5.android.scene.theme.SoundFile

/**
 * Theme size limits at import: a zip refused at the first entry past one (a zip bomb with it), an image by its
 * size read before decoding. A font has none of its own (a CJK one is 10–30 MB), only a file's.
 */
object ThemeAssetLimits {

    const val MAX_ENTRIES = 300
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_PICTURE_SIDE = 4096
    const val MAX_KEY_IMAGE_SIDE = NineSlice.MAX_SIDE
    const val MAX_KEY_IMAGE_BYTES = 2L * 1024 * 1024
    const val MAX_OVERRIDES = Keys.MAX_OVERRIDES

    /** The engine reads no more than a million characters of one (Xml.MAX_CHARS). */
    const val MAX_THEME_XML_BYTES = 1024L * 1024
    const val MAX_LAYOUTS = 16
    const val MAX_LAYOUT_BYTES = PackLayouts.MAX_BYTES
    const val MAX_PREVIEW_BYTES = 512L * 1024
    const val MAX_PREVIEW_SIDE = 2048

    /** A press effect's image: drawn many times a press, PNG or WebP by its name ([EFFECT_IMAGE_TYPES]). */
    const val MAX_EFFECT_IMAGE_SIDE = 256
    const val MAX_EFFECT_IMAGE_BYTES = 128L * 1024
    val EFFECT_IMAGE_TYPES = listOf(".png", ".webp")

    /** More than a few KB is a drawing, not an icon. */
    const val MAX_ICON_BYTES = 64L * 1024

    /** A PNG or WebP icon, decoded whole at most a few times its box. */
    const val MAX_RASTER_ICON_BYTES = RasterIcon.MAX_BYTES * 1L
    const val MAX_RASTER_ICON_SIDE = RasterIcon.MAX_SIDE

    /** A sticker over a key's face, decoded at the size it is drawn. */
    const val MAX_STICKER_BYTES = RasterIcon.MAX_STICKER_BYTES * 1L
    const val MAX_STICKER_SIDE = RasterIcon.MAX_STICKER_SIDE
    const val MAX_STICKERS = RasterIcon.MAX_STICKERS

    /** Each pack is read whole as themes are listed (the keyboard's start); past this a new one is refused. */
    const val MAX_PACKS = 100

    /** Past these an animated picture plays as a still (its first frame), with a warning. */
    const val MAX_ANIMATED_BYTES = 4L * 1024 * 1024
    // decoding is most of the cost and goes with the pixels; the decoder's target size saves nothing
    const val MAX_ANIMATED_SIDE = 720
    const val MAX_ANIMATED_FRAMES = 300

    /** A full frame is decoded in software each; a 0 ms frame is held this long too. */
    const val MIN_ANIMATED_FRAME_MS = 50

    enum class Breach {
        Entries, FileSize, TotalSize, PictureSide, KeyImageSide, KeyImageSize, Overrides,
        ThemeXml, Layouts, LayoutSize, PreviewSize, PreviewSide, IconSize,
        EffectImageSide, EffectImageSize, EffectImageType, SoundSize,
        RasterIconSide, RasterIconSize, StickerSide, StickerSize, RasterType,
    }

    /** Not refused (ThemeFilesManager's [Breach] refuses). */
    enum class Still { Size, Unread, Side, Frames, FrameRate }

    /** [total]: the bytes before it. */
    fun entry(count: Int, size: Long, total: Long): Breach? = when {
        count > MAX_ENTRIES -> Breach.Entries
        size > MAX_FILE_BYTES -> Breach.FileSize
        total + size > MAX_TOTAL_BYTES -> Breach.TotalSize
        else -> null
    }

    fun picture(width: Int, height: Int): Breach? =
        if (maxOf(width, height) > MAX_PICTURE_SIDE) Breach.PictureSide else null

    fun keyImage(width: Int, height: Int, bytes: Long): Breach? = when {
        maxOf(width, height) > MAX_KEY_IMAGE_SIDE -> Breach.KeyImageSide
        bytes > MAX_KEY_IMAGE_BYTES -> Breach.KeyImageSize
        else -> null
    }

    fun themeXml(bytes: Long): Breach? = if (bytes > MAX_THEME_XML_BYTES) Breach.ThemeXml else null

    fun layouts(sizes: List<Long>): Breach? = when {
        sizes.size > MAX_LAYOUTS -> Breach.Layouts
        sizes.any { it > MAX_LAYOUT_BYTES } -> Breach.LayoutSize
        else -> null
    }

    fun preview(width: Int, height: Int, bytes: Long): Breach? = when {
        bytes > MAX_PREVIEW_BYTES -> Breach.PreviewSize
        maxOf(width, height) > MAX_PREVIEW_SIDE -> Breach.PreviewSide
        else -> null
    }

    /** Size first, then the header walked ([AnimatedWebp.header]). Off the main thread: a read of up to 4 MB. */
    fun animated(file: java.io.File): Still? {
        val bytes = file.length()
        if (bytes > MAX_ANIMATED_BYTES) return Still.Size
        val header = runCatching { AnimatedWebp.header(file, MAX_ANIMATED_FRAMES) }.getOrElse { return Still.Unread }
        return animated(bytes, header)
    }

    /** [header] null: it reads as none. */
    fun animated(bytes: Long, header: AnimatedWebp.Header?): Still? = when {
        bytes > MAX_ANIMATED_BYTES -> Still.Size
        header == null -> Still.Unread
        maxOf(header.width, header.height) > MAX_ANIMATED_SIDE -> Still.Side
        header.frames > MAX_ANIMATED_FRAMES -> Still.Frames
        header.shortestFrameMs < MIN_ANIMATED_FRAME_MS -> Still.FrameRate
        else -> null
    }

    fun effectImage(name: String, width: Int, height: Int, bytes: Long): Breach? = when {
        EFFECT_IMAGE_TYPES.none { name.endsWith(it, ignoreCase = true) } -> Breach.EffectImageType
        maxOf(width, height) > MAX_EFFECT_IMAGE_SIDE -> Breach.EffectImageSide
        bytes > MAX_EFFECT_IMAGE_BYTES -> Breach.EffectImageSize
        else -> null
    }

    fun icon(bytes: Long): Breach? = if (bytes > MAX_ICON_BYTES) Breach.IconSize else null

    // a side not positive is no image to decode either
    fun rasterIcon(bytes: Long, width: Int, height: Int): Breach? = when {
        minOf(width, height) <= 0 || maxOf(width, height) > MAX_RASTER_ICON_SIDE -> Breach.RasterIconSide
        bytes > MAX_RASTER_ICON_BYTES -> Breach.RasterIconSize
        else -> null
    }

    fun sticker(bytes: Long, width: Int, height: Int): Breach? = when {
        minOf(width, height) <= 0 || maxOf(width, height) > MAX_STICKER_SIDE -> Breach.StickerSide
        bytes > MAX_STICKER_BYTES -> Breach.StickerSize
        else -> null
    }

    /** By its header ([RasterIcon.header]): none read or an animated one is [Breach.RasterType]. */
    fun rasterIcon(bytes: Long, header: RasterIcon.Header?): Breach? =
        if (header == null || header.animated) Breach.RasterType else rasterIcon(bytes, header.width, header.height)

    fun sticker(bytes: Long, header: RasterIcon.Header?): Breach? =
        if (header == null || header.animated) Breach.RasterType else sticker(bytes, header.width, header.height)

    /** Before it is read (a 32 MB ".ogg" never read in); the rest is [SoundFile.check]'s. */
    fun soundSize(bytes: Long): Breach? = if (bytes > SoundFile.MAX_BYTES) Breach.SoundSize else null

    fun look(look: ThemeLook): Breach? = if (look.keys.overrides.size > MAX_OVERRIDES) Breach.Overrides else null
}
