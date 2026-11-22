/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.scene.pet.PetLimits
import org.fcitx.fcitx5.android.scene.theme.RasterIcon
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.utils.errorRuntime
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
                val h = RasterIcon.header(f.readBytes())
                if (h == null) "not a PNG or WebP" else PetLimits.check(s, h.width, h.height, length, h.animated)
            }
            wrong?.let { errorRuntime(R.string.exception_theme_pet, "${name.id} · ${s.sheet}: $it") }
        }
    }
}
