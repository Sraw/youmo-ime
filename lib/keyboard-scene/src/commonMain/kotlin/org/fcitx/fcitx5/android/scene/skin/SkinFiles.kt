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

    /** [name] decoded, or null (not there, not an image, past the limits). */
    fun image(name: String?): SkinImage? {
        if (name == null || name !in entries) return null
        return images.getOrPut(name) {
            platform.image(entries.getValue(name)).also { if (it == null) imageFailed = true }
        }
    }

    /** All of [name]'s pixels, or null. */
    fun pixels(name: String?): Pixels? = image(name)?.let { it.pixels(0, 0, it.width, it.height) }

    /** [bytes] decoded as an image (a preview not named in the skin), or null. */
    fun decode(bytes: ByteArray?): Pixels? = bytes?.let(platform::image)?.let { it.pixels(0, 0, it.width, it.height) }
}
