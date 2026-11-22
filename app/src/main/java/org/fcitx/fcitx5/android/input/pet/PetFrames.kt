/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.scene.pet.Pet
import org.fcitx.fcitx5.android.scene.pet.PetLimits
import org.fcitx.fcitx5.android.scene.pet.PetState
import timber.log.Timber
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A pet's sheets decoded once, about as large as drawn, within a memory budget; the states left out are as if the theme had none. */
class PetFrames private constructor(
    /** The states kept, idle a single frame where it is drawn too small to see move. */
    val pet: Pet,
    private val sheets: Array<Sheet?>,
) {
    class Sheet(val bitmap: Bitmap, val frameWidth: Int, val frameHeight: Int, val columns: Int)

    fun sheet(name: PetState.Name): Sheet? = sheets[name.ordinal]

    fun release() {
        sheets.forEach { it?.bitmap?.recycle() }
        sheets.fill(null)
    }

    /** Each state's sheet as decoded: [width] × [height] for all its frames. */
    data class Target(val width: Int, val height: Int) {
        val bytes: Long get() = width.toLong() * height * BYTES_PER_PIXEL
    }

    data class Plan(val targets: Map<PetState.Name, Target>, val still: Boolean, val dropped: List<PetState.Name>)

    companion object {
        const val BUDGET_BYTES = 6L * 1024 * 1024
        const val LOW_MEMORY_BUDGET_BYTES = 3L * 1024 * 1024
        const val MIN_MOVING_PX = 24
        private const val BYTES_PER_PIXEL = 4

        // the most a phone short of memory loses first, idle never
        private val DROPPED = listOf(
            PetState.Name.WAKE, PetState.Name.SLEEP, PetState.Name.SPACE, PetState.Name.ENTER, PetState.Name.DELETE, PetState.Name.TYPING,
        )

        @VisibleForTesting
        @Volatile
        var decodes = 0
            private set

        /**
         * What to decode of sheets of [sizes] (width, height) for a box of [boxPx]: each frame shrunk to fit it, never
         * enlarged; over [budget] states dropped, then idle shrunk. Null where idle cannot be read.
         */
        fun plan(pet: Pet, sizes: Map<PetState.Name, IntArray>, boxPx: Int, budget: Long): Plan? {
            val targets = LinkedHashMap<PetState.Name, Target>()
            for ((name, s) in pet.states) {
                val size = sizes[name] ?: continue
                val fw = size[0] / s.columns
                val fh = size[1] / s.rows
                val scale = minOf(1.0, boxPx.toDouble() / maxOf(fw, fh))
                val tw = maxOf(1, (fw * scale).roundToInt())
                val th = maxOf(1, (fh * scale).roundToInt())
                targets[name] = Target(tw * s.columns, th * s.rows)
            }
            val idle = pet.idle
            var idleTarget = targets[PetState.Name.IDLE] ?: return null
            val dropped = ArrayList<PetState.Name>()
            for (name in DROPPED) {
                if (targets.values.sumOf { it.bytes } <= budget) break
                if (targets.remove(name) != null) dropped += name
            }
            val shrunk = idleTarget.bytes > budget
            if (shrunk) {
                val f = sqrt(budget.toDouble() / idleTarget.bytes)
                val tw = maxOf(1, floor(idleTarget.width / idle.columns * f).toInt())
                val th = maxOf(1, floor(idleTarget.height / idle.rows * f).toInt())
                idleTarget = Target(tw * idle.columns, th * idle.rows)
                targets[PetState.Name.IDLE] = idleTarget
            }
            // a small frame is drawn at the box's size: only a small box, or idle shrunk for memory, is too small to move
            val still = boxPx < MIN_MOVING_PX || shrunk && maxOf(idleTarget.width / idle.columns, idleTarget.height / idle.rows) < MIN_MOVING_PX
            if (still) {
                dropped += targets.keys.filter { it != PetState.Name.IDLE }
                targets.keys.retainAll(setOf(PetState.Name.IDLE))
            }
            return Plan(targets, still, dropped)
        }

        /** On ThemeAssets' thread, then [done] on the main one with the frames, or null where idle cannot be drawn. */
        fun load(pet: Pet, boxPx: Int, budget: Long, done: (PetFrames?) -> Unit) {
            ThemeAssets.runner {
                val frames = runCatching { decode(pet, boxPx, budget) }.onFailure { Timber.w(it, "theme pet not decoded") }.getOrNull()
                ThemeAssets.poster { done(frames) }
            }
        }

        private fun decode(pet: Pet, boxPx: Int, budget: Long): PetFrames? {
            // checked as the pack was imported: a file changed since is left out, its bounds read before any decode
            val sizes = pet.states.mapNotNull { (name, s) ->
                val f = File(s.sheet)
                val size = if (f.isFile && f.length() <= PetLimits.MAX_SHEET_BYTES) ThemeAssets.decoder.size(s.sheet) else null
                val wrong = if (size == null) "unreadable" else PetLimits.check(s, size[0], size[1], f.length())
                if (wrong != null) Timber.w("theme pet %s: %s", name.id, wrong)
                size?.takeIf { wrong == null }?.let { name to it }
            }.toMap()
            val plan = plan(pet, sizes, boxPx, budget) ?: return null
            if (plan.dropped.isNotEmpty()) Timber.i("theme pet: %s left out for memory or size", plan.dropped.joinToString { it.id })
            val sheets = arrayOfNulls<Sheet>(PetState.Name.entries.size)
            for ((name, t) in plan.targets) {
                val s = pet.states.getValue(name)
                val size = sizes.getValue(name)
                var sample = 1
                while (size[0] / (sample * 2) >= t.width && size[1] / (sample * 2) >= t.height) sample *= 2
                decodes++
                val decoded = ThemeAssets.decoder.decode(s.sheet, sample) ?: continue
                val b = if (decoded.width == t.width && decoded.height == t.height) decoded
                else Bitmap.createScaledBitmap(decoded, t.width, t.height, true).also { if (it !== decoded) decoded.recycle() }
                sheets[name.ordinal] = Sheet(b, t.width / s.columns, t.height / s.rows, s.columns)
            }
            if (sheets[PetState.Name.IDLE.ordinal] == null) return null
            val kept = pet.states.filterKeys { sheets[it.ordinal] != null }
                .mapValues { (name, s) -> if (plan.still && name == PetState.Name.IDLE) s.copy(frames = 1) else s }
            return PetFrames(pet.copy(states = kept), sheets)
        }
    }
}
