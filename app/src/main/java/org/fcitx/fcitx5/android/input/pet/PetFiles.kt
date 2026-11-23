/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.scene.pet.PetLimits
import org.fcitx.fcitx5.android.scene.theme.RasterIcon
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

/** A pack's pet sheets held to [PetLimits] as it is imported: one wrong refuses the pack, as any of its files. */
object PetFiles {

    /** A sheet the pack lacks is left to the pack's file check, which names it. */
    fun check(from: File, theme: Theme) {
        val pet = theme.pet ?: return
        for ((name, s) in pet.states) {
            val f = File(from, s.sheet)
            if (!f.isFile) continue
            val length = f.length()
            val wrong = if (length > PetLimits.MAX_SHEET_BYTES) {
                PetLimits.check(s, 1, 1, length)
            } else {
                // the bytes alone say its size and whether it moves
                val h = RasterIcon.header(head(f))
                if (h == null) "not a PNG or WebP" else PetLimits.check(s, h.width, h.height, length, h.animated)
            }
            wrong?.let {
                val what = "${name.id} · ${s.sheet}: $it"
                // the limits said in the user's words take three numbers beside what was wrong: errorRuntime passes one
                throw IllegalArgumentException(
                    appContext.getString(R.string.exception_theme_pet, what, PetLimits.MAX_SHEET_BYTES / 1024, PetLimits.MAX_SHEET_SIDE, PetLimits.MAX_FRAME_SIDE)
                )
            }
        }
    }

    // RasterIcon.header reads no further than a WebP's VP8X chunk, 30 bytes in: not up to 512 KB a state for it
    private const val HEAD_BYTES = 64

    private fun head(f: File): ByteArray = f.inputStream().use { s ->
        val b = ByteArray(HEAD_BYTES)
        var n = 0
        while (n < b.size) {
            val got = s.read(b, n, b.size - n)
            if (got < 0) break
            n += got
        }
        b.copyOf(n)
    }
}
