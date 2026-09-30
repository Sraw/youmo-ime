/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import android.util.Base64
import java.security.MessageDigest

/**
 * Plans how to bring [DataManager.dataDir] from what the last run installed to what the app ships
 * in its assets.
 *
 * This used to merge the app's files with those of plugins; with plugins gone there is one source.
 */
object DataHierarchy {

    /**
     * The descriptor to save once [shipped] has been installed.
     *
     * Its checksum is derived as it was when descriptors were merged, so a data directory an older
     * version installed compares equal as long as the assets haven't changed.
     */
    fun installed(shipped: DataDescriptor) =
        DataDescriptor(checksum(shipped), shipped.files, shipped.symlinks)

    /**
     * [FileAction]s that turn the [old] installation into [shipped]; empty when nothing changed
     */
    fun diff(old: DataDescriptor, shipped: DataDescriptor): List<FileAction> {
        if (old.sha256 == checksum(shipped))
            return emptyList()
        val diffFiles = shipped.files.mapNotNull { (path, sha256) ->
            when {
                // directories (empty sha256) are created along with the files in them
                sha256.isBlank() -> null
                path !in old.files -> FileAction.CreateFile(path)
                old.files[path] != sha256 -> FileAction.UpdateFile(path)
                else -> null
            }
        } + old.files.filterKeys { it !in shipped.files }.map { (path, sha256) ->
            if (sha256.isNotBlank()) FileAction.DeleteFile(path) else FileAction.DeleteDir(path)
        }
        val diffLinks = shipped.symlinks.mapNotNull { (target, source) ->
            // an unchanged link is left alone; a changed one is overwritten
            if (old.symlinks[target] == source) null else FileAction.CreateSymlink(target, source)
        } + old.symlinks.keys.filter { it !in shipped.symlinks }.map { FileAction.DeleteFile(it) }
        return diffFiles + diffLinks
    }

    private fun checksum(descriptor: DataDescriptor): SHA256 =
        MessageDigest.getInstance("SHA-256").digest(descriptor.sha256.encodeToByteArray())
            .let { Base64.encodeToString(it, 0).trim() }
}
