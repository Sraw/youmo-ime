/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.fcitx.fcitx5.android.scene.pet.Pet
import org.fcitx.fcitx5.android.scene.pet.PetLimits
import org.fcitx.fcitx5.android.scene.pet.PetState
import java.io.ByteArrayOutputStream
import java.io.File

/** Self-made sprite sheets: each frame its own shade, so a test can tell which one is drawn. */
internal object PetSheets {

    fun png(frames: Int, columns: Int, framePx: Int, color: Int = Color.RED): ByteArray {
        val rows = (frames + columns - 1) / columns
        val b = Bitmap.createBitmap(columns * framePx, rows * framePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paint = Paint()
        for (i in 0 until frames) {
            paint.color = Color.argb(255, Color.red(color), (i * 255 / frames), Color.blue(color))
            val x = (i % columns) * framePx.toFloat()
            val y = (i / columns) * framePx.toFloat()
            c.drawRect(x, y, x + framePx, y + framePx, paint)
        }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** [states] as name to (frames, fps), each sheet [framePx] a frame, written to [dir] by their paths. */
    fun pet(dir: File, vararg states: Pair<PetState.Name, Pair<Int, Int>>, framePx: Int = 64, place: Pet.Place = Pet.Place.KEYBOARD): Pet =
        Pet(place, Pet.Align.TOP_END, 40.0, 0.0, 0.0, states.associate { (name, f) ->
            val file = File(dir, "pet-${name.id}.png").apply { writeBytes(png(f.first, f.first, framePx)) }
            name to PetState(file.path, f.first, f.first, f.second, PetLimits.resting(name))
        })
}
