/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

/**
 * Another input method's skin made a theme 4.0 pack (dev/design/060-skin-import.md): the skin's files, unpacked in
 * memory by the platform, to `theme.xml` and its files, which the import then checks as any pack's. A skin is hostile
 * input: every length and coordinate in it is held to the files there ([SkinText], [Pixels.crop], [SkinSound]), an
 * image decoded only within [SkinLimits.MAX_IMAGE_PIXELS], and no name in it becomes a file's name.
 */
object SkinConverter {
    /** The formats read, the first that takes a skin's file names reading it. A new format is one more line. */
    val READERS: List<Pair<(Set<String>) -> Boolean, (SkinFiles) -> SkinSource>> = listOf(
        SogouSkin::accepts to ::SogouSkin,
        BaiduSkin::accepts to ::BaiduSkin,
    )

    /**
     * [entries] (a skin's files by name, `/`-separated, none leading out of it) as a theme named as the skin is, or
     * [fallbackName] (the file's) where it has no name.
     *
     * @throws SkinError where it is no skin read here, or one without a keyboard
     */
    fun convert(entries: Map<String, ByteArray>, platform: SkinPlatform, fallbackName: String): SkinConversion {
        val files = SkinFiles(entries, platform)
        val reader = READERS.firstOrNull { it.first(files.names) }?.second ?: throw SkinError(SkinError.Reason.UNKNOWN)
        return guarded { SkinTheme(reader(files), files::decode, fallbackName).write() }
    }

    // a skin's numbers past what the readers hold them to: no skin, not a crash of the import
    @Suppress("TooGenericExceptionCaught")
    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SkinError) {
        throw e
    } catch (e: RuntimeException) {
        throw SkinError(SkinError.Reason.BROKEN, e.message.orEmpty(), e)
    }

}
