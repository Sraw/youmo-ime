/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import java.io.File

/** Found through Gradle's `uat.fixtures`, so an IDE run finds it too; without it, relative to app/. */
fun fixture(name: String): File = File(System.getProperty("uat.fixtures") ?: "../uat/fixtures", name)
    .also { check(it.isFile) { "no fixture $it (uat.fixtures not set, and not run from app/)" } }

private const val TAR_BLOCK = 512

/** Plain ustar only (no links, no long names); [under] picks a prefix and drops it, since old builds' tars name their own dirs. */
fun untar(tar: File, into: File, under: String = "") = tar.inputStream().buffered().use { input ->
    val header = ByteArray(TAR_BLOCK)
    while (input.readNBytes(header, 0, TAR_BLOCK) == TAR_BLOCK && header[0] != 0.toByte()) {
        val name = String(header, 0, 100).trimEnd('\u0000').removePrefix("./")
        val size = String(header, 124, 12).trim('\u0000', ' ').toLong(8)
        val data = input.readNBytes(size.toInt())
        input.skipNBytes((TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK)
        val type = header[156].toInt().toChar()
        // directories are made as their files need them; nothing else is in these tars
        if ((type == '0' || type == '\u0000') && name.startsWith(under)) {
            File(into, name.removePrefix(under)).apply { parentFile!!.mkdirs() }.writeBytes(data)
        }
    }
}
