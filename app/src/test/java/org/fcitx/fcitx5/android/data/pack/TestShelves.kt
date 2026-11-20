/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pack

import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackRequirement
import org.fcitx.fcitx5.android.pack.PackShelf
import org.fcitx.fcitx5.android.pack.TestPacks
import java.nio.file.Files

/** Keyboard tests type with both packs there (KeyboardHarness); packs hold a dummy file, engine data comes from the build. */
object TestShelves {

    val packs = TestPacks()

    val requirements = PackKind.entries.associateWith { PackRequirement(format = 1, minVersion = 1) }

    fun pack(kind: PackKind, version: Int = 1, format: Int = 1): ByteArray =
        packs.pack(kind, mapOf(FILES.getValue(kind) to "$kind v$version".toByteArray()), version, format)

    fun shelf(vararg kinds: PackKind): PackShelf {
        // deleteOnExit takes an empty directory only: the packs imported are gone with the JVM by a hook of their own
        val dir = Files.createTempDirectory("packs").toFile()
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        val shelf = PackShelf(dir, listOf(packs.publicKey), requirements)
        shelf.load()
        for (kind in kinds) check(shelf.import(pack(kind).inputStream()) is ImportResult.Imported) { "$kind not imported" }
        return shelf
    }

    // made once a JVM, shared
    private val both by lazy { shelf(PackKind.TEXT, PackKind.VOICE) }

    /** Both by default (shared); the others missing. */
    fun use(text: Boolean = true, voice: Boolean = true): PackShelf {
        val shelf = if (text && voice) both else shelf(*listOfNotNull(PackKind.TEXT.takeIf { text }, PackKind.VOICE.takeIf { voice }).toTypedArray())
        PackStore.useShelf(shelf)
        return shelf
    }

    private val FILES = mapOf(PackKind.TEXT to "handwriting/test.txt", PackKind.VOICE to "voice/test.txt")
}
