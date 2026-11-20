/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyKind
import org.fcitx.fcitx5.android.scene.look.KeyLabel
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.NineSlice

data class KeyStyle(val fill: KeyFill, val pressed: KeyPressed, val label: KeyLabel)

/** Looked up: the key on its board (`qwerty/q`), a function key on every board, its kind. A single key's look covers only what it sets. Unknown names do nothing. */
object KeyStyleResolver {

    fun resolve(keys: Keys, board: String?, key: String?, kind: KeyKind): KeyStyle {
        val own = if (board != null && key != null) keys.overrides["$board/$key"] else null
        val all = if (key != null && KeyNames.isRole(key)) keys.overrides["${KeyNames.ALL}/$key"] else null
        val style = keys.kind(kind)
        return KeyStyle(
            fill = own?.fill ?: all?.fill ?: style.fill,
            pressed = own?.pressed ?: all?.pressed ?: style.pressed,
            label = own?.label ?: all?.label ?: KeyLabel.Show,
        )
    }

    /** Each file once: the kinds', its own keys', its roles' every-board ones; a board-less keyboard gets kinds and roles alone. */
    fun imagesOn(keys: Keys, board: String?, names: Collection<String>): Set<NineSlice> {
        val out = LinkedHashSet<NineSlice>()
        fun add(fill: KeyFill?, pressed: KeyPressed?) {
            (fill as? KeyFill.Image)?.let { out += it.slice }
            (pressed as? KeyPressed.Image)?.let { out += it.slice }
        }
        keys.kinds().forEach { add(it.fill, it.pressed) }
        names.forEach { key ->
            if (KeyNames.isRole(key)) keys.overrides["${KeyNames.ALL}/$key"]?.let { add(it.fill, it.pressed) }
            if (board != null) keys.overrides["$board/$key"]?.let { add(it.fill, it.pressed) }
        }
        return out
    }
}

/** Over [LIMIT_BYTES] decoded, every image of the board is sampled down by one power of two (inSampleSize); a file used twice counts once. */
object KeyImageBudget {

    const val LIMIT_BYTES = 12L * 1024 * 1024

    /** Pixels. */
    data class Image(val file: String, val width: Int, val height: Int)

    fun bytes(images: Collection<Image>, sample: Int = 1): Long =
        images.distinctBy { it.file }.sumOf { sampled(it.width, sample).toLong() * sampled(it.height, sample) * BYTES_PER_PIXEL }

    /** 1 when they fit. */
    fun sampleSize(images: Collection<Image>, limit: Long = LIMIT_BYTES): Int {
        var sample = 1
        while (bytes(images, sample) > limit && sample < MAX_SAMPLE) sample *= 2
        return sample
    }

    // as Skia's sampled decoding sizes it: down, at least one pixel
    private fun sampled(px: Int, sample: Int) = maxOf(1, px / sample)

    private const val BYTES_PER_PIXEL = 4
    private const val MAX_SAMPLE = 64
}
