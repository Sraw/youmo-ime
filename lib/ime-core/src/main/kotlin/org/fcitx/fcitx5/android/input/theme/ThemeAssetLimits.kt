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
 */
object ThemeAssetLimits {

    const val MAX_ENTRIES = 300
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_PICTURE_SIDE = 4096
    const val MAX_KEY_IMAGE_SIDE = NineSlice.MAX_SIDE
    const val MAX_KEY_IMAGE_BYTES = 2L * 1024 * 1024
    const val MAX_OVERRIDES = Keys.MAX_OVERRIDES

    /** A limit passed, and by what. */
    enum class Breach { Entries, FileSize, TotalSize, PictureSide, KeyImageSide, KeyImageSize, Overrides }

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

    fun look(look: ThemeLook): Breach? = if (look.keys.overrides.size > MAX_OVERRIDES) Breach.Overrides else null
}
