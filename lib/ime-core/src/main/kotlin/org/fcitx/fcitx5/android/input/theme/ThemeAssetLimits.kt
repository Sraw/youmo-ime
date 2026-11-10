/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.ThemeLook

/**
 * How big a theme may be as it is imported: a zip is counted as it is unpacked and refused at the
 * first entry past a limit (a zip bomb with it), an image by its size read before it is decoded.
 * Enough for every key's image, pressed and not, on every board. A font has no limit of its own: a
 * CJK one is often 10–30 MB, so it is held to a file's (design §3.6), checked as the zip is unpacked.
 * A theme 4.0 pack (060-render-p3 T8a) is held to these and to its own: its `theme.xml`, its preview,
 * its icons and the layouts in its `layouts/` (the engine holds it to 1000 styles, 4 fonts, 200 icons).
 */
object ThemeAssetLimits {

    const val MAX_ENTRIES = 300
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_PICTURE_SIDE = 4096
    const val MAX_KEY_IMAGE_SIDE = NineSlice.MAX_SIDE
    const val MAX_KEY_IMAGE_BYTES = 2L * 1024 * 1024
    const val MAX_OVERRIDES = Keys.MAX_OVERRIDES

    /** A pack's `theme.xml`: the engine reads no more than a million characters of one (Xml.MAX_CHARS). */
    const val MAX_THEME_XML_BYTES = 1024L * 1024
    const val MAX_LAYOUTS = 16
    const val MAX_LAYOUT_BYTES = 64L * 1024
    const val MAX_PREVIEW_BYTES = 512L * 1024
    const val MAX_PREVIEW_SIDE = 2048

    /** An SVG icon: a path and its fill take a few KB; more is a drawing, not an icon (scene.theme.SvgSubset). */
    const val MAX_ICON_BYTES = 64L * 1024

    /**
     * The packs a phone keeps: each is read whole as the themes are listed (the keyboard's start), up to
     * [MAX_THEME_XML_BYTES] a pack; past it a new one is refused, and a listing reads the newest this many.
     */
    const val MAX_PACKS = 100

    /**
     * An animated `<picture>` (060-render-p3 A4): one past these is not refused but drawn as a still
     * picture (its first frame), with a warning: memory and battery, a decoder held while it plays.
     */
    const val MAX_ANIMATED_BYTES = 4L * 1024 * 1024
    const val MAX_ANIMATED_SIDE = 1440
    const val MAX_ANIMATED_FRAMES = 300

    /** 20 fps at most (CPU: a full frame decoded in software each, 060-render-p3 A4): a frame shown no shorter than this (a frame of 0 ms, played as fast as it can, too). */
    const val MIN_ANIMATED_FRAME_MS = 50

    /** A limit passed, and by what. */
    enum class Breach {
        Entries, FileSize, TotalSize, PictureSide, KeyImageSide, KeyImageSize, Overrides,
        ThemeXml, Layouts, LayoutSize, PreviewSize, PreviewSide, IconSize,
    }

    /** Why an animated picture is drawn still: not refused (ThemeFilesManager's [Breach] refuses). */
    enum class Still { Size, Unread, Side, Frames, FrameRate }

    /** The [count]th entry, [size] bytes, after [total] bytes before it. */
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

    /** A pack's layouts, each by its size in bytes. */
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

    /**
     * An animated WebP of [bytes] whose header ([AnimatedWebp.header]) is [header] (null: it reads as none):
     * null where it may play.
     */
    /**
     * Why [file] is drawn still, null where it may play: its size first, then the file read once and its
     * header walked ([AnimatedWebp.header], bounded). Off the main thread: a read of up to 4 MB.
     */
    fun animated(file: java.io.File): Still? {
        val bytes = file.length()
        if (bytes > MAX_ANIMATED_BYTES) return Still.Size
        val header = runCatching { AnimatedWebp.header(file, MAX_ANIMATED_FRAMES) }.getOrElse { return Still.Unread }
        return animated(bytes, header)
    }

    fun animated(bytes: Long, header: AnimatedWebp.Header?): Still? = when {
        bytes > MAX_ANIMATED_BYTES -> Still.Size
        header == null -> Still.Unread
        maxOf(header.width, header.height) > MAX_ANIMATED_SIDE -> Still.Side
        header.frames > MAX_ANIMATED_FRAMES -> Still.Frames
        header.shortestFrameMs < MIN_ANIMATED_FRAME_MS -> Still.FrameRate
        else -> null
    }

    fun icon(bytes: Long): Breach? = if (bytes > MAX_ICON_BYTES) Breach.IconSize else null

    fun look(look: ThemeLook): Breach? = if (look.keys.overrides.size > MAX_OVERRIDES) Breach.Overrides else null
}
