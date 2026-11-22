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
        BaiduBdaSkin::accepts to ::BaiduBdaSkin,
        FlorisSkin::accepts to ::FlorisSkin,
        TrimeSkin::accepts to ::TrimeSkin,
        XunfeiSkin::accepts to ::XunfeiSkin,
    )

    /**
     * [entries] (a skin's files by name, `/`-separated, none leading out of it) as a theme named as the skin is, or
     * as [fileName] (the file's own name, its extension too) where it has no name: [convertOpened] of [open].
     *
     * @throws SkinError where it is no skin read here, or one without a keyboard
     */
    fun convert(entries: Map<String, ByteArray>, platform: SkinPlatform, fileName: String): SkinConversion =
        convertOpened(open(entries, platform), platform, fileName)

    /**
     * [entries] as a reader takes them: without macOS's files, a Sogou container (the one file, or the one `.ssf`)
     * opened in its place, and the one folder they are all in taken off where no reader takes them as they are.
     */
    fun open(entries: Map<String, ByteArray>, platform: SkinPlatform): Map<String, ByteArray> {
        val own = withoutMacFiles(entries)
        val opened = container(own)?.let { (_, b) ->
            // the files, the container too, are held while it is open: what they take is not left for it (as SkinZip counts a `.flex`)
            withoutMacFiles(SogouContainer.open(b, platform, SkinLimits.MAX_TOTAL_BYTES - own.values.sumOf { it.size.toLong() }))
        } ?: own
        return if (READERS.any { it.first(opened.keys) }) opened else unwrapped(opened)
    }

    /** [entries] as [open] gave them (not opened again: a folder is taken off once), converted. */
    fun convertOpened(entries: Map<String, ByteArray>, platform: SkinPlatform, fileName: String): SkinConversion {
        unsupported(fileName, entries.keys)?.let { throw SkinError(SkinError.Reason.UNSUPPORTED, it) }
        val files = SkinFiles(entries, platform)
        val reader = READERS.firstOrNull { it.first(files.names) }?.second
            // a computer skin said so only where no reader takes it (a skin of another format may hold a skin.ini)
            ?: throw SkinError(if (SogouSkin.isComputerSkin(files.names)) SkinError.Reason.SOGOU_PC else SkinError.Reason.UNKNOWN)
        return guarded { SkinTheme(reader(files), files::decode, fileName, platform.layoutSources()).write() }
    }

    // a Sogou own-made skin's settings, one for each screen width
    private val SOGOU_DIY = Regex("\\d+/skin\\.ini")

    private fun sogouDiy(names: Set<String>): Boolean =
        "phoneTheme.ini" in names && names.any { SOGOU_DIY.matches(it) } && names.none { it.endsWith("phoneSkin.ini") }

    /**
     * The [SkinFormat] of a format told apart and not converted, by [fileName], the one file's name in it and [names]
     * only; checked before the readers, as some of these hold files a reader would take (a 仓 skin's YAML).
     */
    fun unsupported(fileName: String, names: Set<String>): String? {
        val ext = listOfNotNull(fileName, names.singleOrNull()).map { it.substringAfterLast('/').substringAfterLast('.', "").lowercase() }
        val leaves = names.mapTo(HashSet()) { it.substringAfterLast('/') }
        fun has(test: (String) -> Boolean) = names.any(test)
        return when {
            "sxi" in ext || ("skin.json" in leaves && has { it.endsWith("/res/skin.proto") }) -> SkinFormat.SHOUXIN
            "metadata.json" in leaves && has { it.substringAfterLast('/').let { n -> n.startsWith("style_sheet") && n.endsWith(".css") } } -> SkinFormat.GBOARD
            // Rboard's packs: each theme a `<name>.zip` beside its `<name>` picture
            "pack" in ext || has { it.endsWith(".zip") && it.removeSuffix(".zip") in names } -> SkinFormat.GBOARD
            "cskin" in ext || has { it.startsWith("jsonnet/") || "/jsonnet/" in it } -> SkinFormat.CANG
            "apk" in ext || ("AndroidManifest.xml" in names && "resources.arsc" in names) -> SkinFormat.APK
            sogouDiy(names) -> SkinFormat.SOGOU_DIY
            else -> null
        }
    }

    // the one file, or the one `.ssf` among them, where it is a Sogou container
    private fun container(entries: Map<String, ByteArray>): Pair<String, ByteArray>? {
        val one = entries.entries.singleOrNull() ?: entries.entries.filter { it.key.endsWith(".ssf", ignoreCase = true) }.singleOrNull()
        return one?.takeIf { SogouContainer.isContainer(it.value) }?.let { it.key to it.value }
    }

    /** [entries] without what macOS's Finder adds to a ZIP it makes (`__MACOSX/`, `._name`): no file of the skin's. */
    fun withoutMacFiles(entries: Map<String, ByteArray>): Map<String, ByteArray> =
        entries.filterKeys { n -> !n.startsWith("__MACOSX/") && !n.substringAfterLast('/').startsWith("._") }

    /** [entries] with the one folder they are all in taken off their names (a download of a theme's repository); else as they are. */
    fun unwrapped(entries: Map<String, ByteArray>): Map<String, ByteArray> {
        val top = entries.keys.map { it.substringBefore('/', "") }.toSet()
        val folder = top.singleOrNull()?.takeIf { it.isNotEmpty() } ?: return entries
        return entries.mapKeys { it.key.removePrefix("$folder/") }.filterKeys { it.isNotEmpty() }
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
