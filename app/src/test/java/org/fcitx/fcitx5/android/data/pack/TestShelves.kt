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

/**
 * Pack shelves for the app's tests, put in [PackStore]'s place ([use]): the keyboard's tests
 * type with both packs there (KeyboardHarness), as a user's phone has them; the gate's and voice
 * input's prompts are tested without. The packs hold a small file each, no engine data: what reads
 * the engine's data in a JVM test reads it from the build's output, as before the packs were.
 */
object TestShelves {

    val packs = TestPacks()

    val requirements = PackKind.entries.associateWith { PackRequirement(format = 1, minVersion = 1) }

    /** A pack of [kind] at [version], its one file not one the app reads. */
    fun pack(kind: PackKind, version: Int = 1, format: Int = 1): ByteArray =
        packs.pack(kind, mapOf(FILES.getValue(kind) to "$kind v$version".toByteArray()), version, format)

    /** A new shelf in a directory of its own, with the packs of [kinds] imported. */
    fun shelf(vararg kinds: PackKind): PackShelf {
        val shelf = PackShelf(Files.createTempDirectory("packs").toFile().apply { deleteOnExit() }, listOf(packs.publicKey), requirements)
        shelf.load()
        for (kind in kinds) check(shelf.import(pack(kind).inputStream()) is ImportResult.Imported) { "$kind not imported" }
        return shelf
    }

    // one of each, made once a JVM: the tests that only need the packs there share them
    private val both by lazy { shelf(PackKind.TEXT, PackKind.VOICE) }

    /** [PackStore] with the packs asked for ready (both by default: shared), the others missing. */
    fun use(text: Boolean = true, voice: Boolean = true): PackShelf {
        val shelf = if (text && voice) both else shelf(*listOfNotNull(PackKind.TEXT.takeIf { text }, PackKind.VOICE.takeIf { voice }).toTypedArray())
        PackStore.useShelf(shelf)
        return shelf
    }

    private val FILES = mapOf(PackKind.TEXT to "handwriting/test.txt", PackKind.VOICE to "voice/test.txt")
}
