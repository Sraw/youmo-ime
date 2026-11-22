/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.pet

/** What a pet's sheets may be: the app checks them as a pack is imported, the theme maker as it saves, and both before decoding. */
object PetLimits {
    const val MAX_SHEET_BYTES = 512 * 1024
    const val MAX_SHEET_SIDE = 2048
    const val MAX_FRAME_SIDE = 256
    const val MAX_FRAMES = 24
    const val MAX_TOTAL_FRAMES = 96
    const val MAX_FPS = 30
    const val MAX_RESTING_FPS = 8
    const val MIN_SIZE_DP = 16.0
    const val MAX_SIZE_DP = 64.0
    const val DEFAULT_SIZE_DP = 40.0
    const val MAX_OFFSET_DP = 32.0

    /** For the theme maker's checks (youmo-format.json). */
    val FORMAT: Map<String, Int> = mapOf(
        "sheetBytes" to MAX_SHEET_BYTES, "sheetSide" to MAX_SHEET_SIDE, "frameSide" to MAX_FRAME_SIDE, "frames" to MAX_FRAMES,
        "totalFrames" to MAX_TOTAL_FRAMES, "fps" to MAX_FPS, "restingFps" to MAX_RESTING_FPS, "minSizeDp" to MIN_SIZE_DP.toInt(),
        "maxSizeDp" to MAX_SIZE_DP.toInt(), "offsetDp" to MAX_OFFSET_DP.toInt(),
    )

    /** What is wrong with [state]'s sheet of [widthPx] × [heightPx] and [bytes], or null. */
    fun check(state: PetState, widthPx: Int, heightPx: Int, bytes: Long, animated: Boolean = false): String? = when {
        animated -> "an animated WebP: a sheet is a still image"
        bytes > MAX_SHEET_BYTES -> "${bytes / KB} KB, over ${MAX_SHEET_BYTES / KB} KB"
        widthPx <= 0 || heightPx <= 0 -> "not an image"
        maxOf(widthPx, heightPx) > MAX_SHEET_SIDE -> "$widthPx×$heightPx px, over $MAX_SHEET_SIDE px a side"
        widthPx % state.columns != 0 -> "$widthPx px wide, not ${state.columns} columns"
        heightPx % state.rows != 0 -> "$heightPx px high, not ${state.rows} rows"
        maxOf(widthPx / state.columns, heightPx / state.rows) > MAX_FRAME_SIDE ->
            "frames of ${widthPx / state.columns}×${heightPx / state.rows} px, over $MAX_FRAME_SIDE px a side"
        else -> null
    }

    /** Every state's frames together. */
    fun total(pet: Pet): Int = pet.states.values.sumOf { it.frames }

    /** The most frames a second [name] plays. */
    fun fps(name: PetState.Name): Int = if (resting(name)) MAX_RESTING_FPS else MAX_FPS

    fun resting(name: PetState.Name): Boolean = name == PetState.Name.IDLE || name == PetState.Name.SLEEP

    private const val KB = 1024
}
