/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/** What a key is drawn with at last: its face, pressed face, and whether its text shows. */
data class KeyStyle(val fill: KeyFill, val pressed: KeyPressed, val label: KeyLabel)

/**
 * A key's look, looked for in order: the key on its own board (`qwerty/q`), a function key on every
 * board (`*`/backspace), its kind. A single key's look covers only what it sets: Q given an image
 * alone is pressed as the letters are. Its [KeyLabel] is the first of the two found that is there.
 * A name no board here has does nothing, nor one of a board not drawn: no error, no notice.
 */
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

    /**
     * The images a board with keys [names] draws, each file once: the kinds', its own keys', the
     * every-board ones of its roles. What a board decodes as it is made. A keyboard of no board name
     * (the number row, a search's keys) has no keys of its own: the kinds' and the roles' alone.
     */
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

/**
 * How much the key images of one board take decoded, and how much smaller they are decoded when
 * that is over [LIMIT_BYTES]: the board still shows, a little blurred. One factor, a power of two
 * (BitmapFactory's inSampleSize), for all of the board's images; a file used twice counts once.
 */
object KeyImageBudget {

    const val LIMIT_BYTES = 12L * 1024 * 1024

    /** A key image's file and its size, pixels. */
    data class Image(val file: String, val width: Int, val height: Int)

    fun bytes(images: Collection<Image>, sample: Int = 1): Long =
        images.distinctBy { it.file }.sumOf { sampled(it.width, sample).toLong() * sampled(it.height, sample) * BYTES_PER_PIXEL }

    /** The inSampleSize for [images]: 1 when they fit. */
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
