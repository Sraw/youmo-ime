/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pack

import android.content.res.AssetManager
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackStatus
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The app's data by its asset path (`engine/pinyin.data`, `voice/encoder.ort`), wherever it is: a
 * path a resource pack owns ([PackKind.of]) from that pack's files, any other from the APK's assets.
 * The one way the engine, handwriting and voice read their data: no caller asks where a file is.
 *
 * A pack's path with that pack not usable (not imported, or another version than this app needs)
 * throws [PackUnavailableException], an IOException: what cannot load is told as data that cannot
 * load always was (EngineBridge.Unloadable), only the feature reading it stopped.
 */
class DataSource(private val packDir: (PackKind) -> PackDir, private val assets: () -> AssetManager) {

    /** A pack's directory if it is ready, else its status. */
    sealed class PackDir {
        class Ready(val dir: File) : PackDir()
        class Unavailable(val status: PackStatus) : PackDir()
    }

    /** [path], mapped read-only: a pack's file mapped, an asset mapped where it lies in the APK (stored uncompressed). */
    fun map(path: String): ByteBuffer {
        val kind = PackKind.of(path) ?: return assets().openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }
        }
        return FileInputStream(packFile(kind, path)).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
    }

    /** [path], to read through. */
    fun open(path: String): InputStream = when (val kind = PackKind.of(path)) {
        null -> assets().open(path)
        else -> FileInputStream(packFile(kind, path))
    }

    /** A pack's file by its path, for what reads a file by its name (onnxruntime, sherpa-onnx); an asset has none. */
    fun file(path: String): File {
        val kind = requireNotNull(PackKind.of(path)) { "$path is the APK's: open or map it" }
        return packFile(kind, path)
    }

    private fun packFile(kind: PackKind, path: String): File = when (val dir = packDir(kind)) {
        is PackDir.Ready -> File(dir.dir, path).also { if (!it.isFile) throw FileNotFoundException("$path not in the ${kind.id} pack") }
        is PackDir.Unavailable -> throw PackUnavailableException(kind, dir.status)
    }
}

/** [kind]'s pack is not there, or not one this app reads ([status]): what it has cannot be read. */
class PackUnavailableException(val kind: PackKind, val status: PackStatus) :
    FileNotFoundException("the ${kind.id} pack is ${status.javaClass.simpleName}")

/** The app's: the packs [PackStore] has, the APK's assets. */
val AppData = DataSource(
    packDir = { kind ->
        PackStore.dir(kind)?.let { DataSource.PackDir.Ready(it) } ?: DataSource.PackDir.Unavailable(PackStore.status(kind))
    },
    assets = { appContext.assets },
)
