/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.res.AssetManager
import org.fcitx.fcitx5.android.host.PkgDataDir
import java.io.IOException

/** The built-in fcitx data under the APK's assets ([root]; `app/src/main/host-assets.md`), read in place, never copied out. */
class AssetPkgDataDir(private val assets: AssetManager, private val root: String) : PkgDataDir {
    override fun read(path: String): ByteArray? = try {
        assets.open("$root/$path").use { it.readBytes() }
    } catch (_: IOException) {
        null
    }

    override fun list(dir: String): Collection<String>? = try {
        assets.list("$root/$dir")?.toList()
    } catch (_: IOException) {
        null
    }
}
