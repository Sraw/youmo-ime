/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

/**
 * A skin's files by name, as the platform unpacked them (in memory, held to [SkinLimits]; names `/`-separated, no
 * `..`): read as text, INI or images here, each image decoded once. Nothing here touches a disk: a name in a skin
 * never becomes a path.
 */
class SkinFiles(private val entries: Map<String, ByteArray>, private val platform: SkinPlatform) {
    val names: Set<String> get() = entries.keys

    private val images = HashMap<String, SkinImage?>()
    private var keptPixels = 0L

    // all of an image's pixels, by name: one copy however many times it is asked for (nothing drawn into them: a
    // reader draws into pixels of its own), within the same bound
    private val whole = HashMap<String, Pixels?>()
    private var wholePixels = 0L

    /** Whether an image failed to decode or was past the limits (said to the user once). */
    var imageFailed = false
        private set

    operator fun contains(name: String) = name in entries

    fun bytes(name: String): ByteArray? = entries[name]

    /** The first of [names] there is. */
    fun first(vararg names: String): String? = names.firstOrNull { it in entries }

    /** [name] as text, or "" where it is not there or past [SkinLimits.MAX_TEXT_BYTES]. */
    fun text(name: String?): String {
        val b = name?.let { entries[it] } ?: return ""
        if (b.size > SkinLimits.MAX_TEXT_BYTES) return ""
        return SkinText.decode(b, platform::legacyText)
    }

    fun ini(name: String?): Ini = SkinText.ini(text(name))

    /**
     * [name] decoded, or null (not there, not an image, past the limits). Kept to be read again (an atlas, a key's
     * tile at a time) within [SkinLimits.MAX_KEPT_PIXELS]: past it, those kept are let go, decoded again if asked for.
     */
    fun image(name: String?): SkinImage? {
        if (name == null || name !in entries) return null
        if (name in images) return images[name]
        val image = platform.image(entries.getValue(name))
        if (image == null) imageFailed = true
        val size = image?.let { it.width.toLong() * it.height } ?: 0L
        if (keptPixels + size > SkinLimits.MAX_KEPT_PIXELS) {
            images.clear()
            keptPixels = 0
        }
        images[name] = image
        keptPixels += size
        return image
    }

    /** All of [name]'s pixels, or null: the same [Pixels] each time (read, never drawn into). */
    fun pixels(name: String?): Pixels? {
        if (name == null || name !in entries) return null
        if (name in whole) return whole[name]
        val px = image(name)?.let { it.pixels(0, 0, it.width, it.height) }
        val size = px?.let { it.width.toLong() * it.height } ?: 0L
        if (wholePixels + size > SkinLimits.MAX_KEPT_PIXELS) {
            whole.clear()
            wholePixels = 0
        }
        whole[name] = px
        wholePixels += size
        return px
    }

    /** [bytes] decoded as an image (a preview not named in the skin), or null. */
    fun decode(bytes: ByteArray?): Pixels? = bytes?.let(platform::image)?.let { it.pixels(0, 0, it.width, it.height) }
}
