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
 * The app's data by asset path, from the owning pack ([PackKind.of]) or the APK's assets. An unusable pack throws
 * [PackUnavailableException], an IOException: only the feature reading it stops (EngineBridge.Unloadable).
 */
class DataSource(private val packDir: (PackKind) -> PackDir, private val assets: () -> AssetManager) {

    sealed class PackDir {
        class Ready(val dir: File) : PackDir()
        class Unavailable(val status: PackStatus) : PackDir()
    }

    /** An asset is mapped where it lies in the APK (stored uncompressed). */
    fun map(path: String): ByteBuffer {
        val kind = PackKind.of(path) ?: return assets().openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }
        }
        return FileInputStream(packFile(kind, path)).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
    }

    fun open(path: String): InputStream = when (val kind = PackKind.of(path)) {
        null -> assets().open(path)
        else -> FileInputStream(packFile(kind, path))
    }

    /** For readers that need a file name (onnxruntime, sherpa-onnx); an asset has none. */
    fun file(path: String): File {
        val kind = requireNotNull(PackKind.of(path)) { "$path is the APK's: open or map it" }
        return packFile(kind, path)
    }

    private fun packFile(kind: PackKind, path: String): File = when (val dir = packDir(kind)) {
        is PackDir.Ready -> File(dir.dir, path).also { if (!it.isFile) throw FileNotFoundException("$path not in the ${kind.id} pack") }
        is PackDir.Unavailable -> throw PackUnavailableException(kind, dir.status)
    }
}

class PackUnavailableException(val kind: PackKind, val status: PackStatus) :
    FileNotFoundException("the ${kind.id} pack is ${status.javaClass.simpleName}")

val AppData = DataSource(
    packDir = { kind ->
        PackStore.dir(kind)?.let { DataSource.PackDir.Ready(it) } ?: DataSource.PackDir.Unavailable(PackStore.status(kind))
    },
    assets = { appContext.assets },
)
