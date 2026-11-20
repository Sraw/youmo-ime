/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.SharedPreferences
import android.os.Environment
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.utils.appContext
import org.robolectric.shadows.ShadowEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Makes the external storage present and settles the homes again. Robolectric's has no state (reads as away),
 * `fresh` hangs on other tests' first-run flag, and FcitxApplication settled the themes first: a test that writes
 * user data calls this, or it is refused depending on run order.
 */
fun phoneStorage() {
    ShadowEnvironment.setExternalStorageState(appContext.getExternalFilesDir(null)!!, Environment.MEDIA_MOUNTED)
    HostConfigDir.forget()
}

/** By name, so a field Dirs gains is added once and two are never swapped unseen. */
internal fun dirsUnder(
    root: File,
    external: File = File(root, "external"),
    config: File = File(root, "files/host-config"),
    data: File = File(external, ConfigHome.DATA.old),
    theme: File = File(external, ConfigHome.THEME.old),
    settled: Boolean = true,
    sharedPrefs: File = File(root, "shared_prefs"),
) = UserDataManager.Dirs(
    sharedPrefs = sharedPrefs,
    dataBases = File(root, "databases"),
    external = external,
    recentlyUsed = File(root, "recently_used"),
    engine = File(root, "engine"),
    config = config,
    data = data,
    theme = theme,
    settled = settled,
    // as CustomLayouts.dir is on a phone
    layouts = File(root, "files/layouts"),
)

// an entry ending in `/` is a directory
internal fun zipOf(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip ->
        for ((name, text) in entries) {
            zip.putNextEntry(ZipEntry(name))
            if (!name.endsWith("/")) zip.write(text.encodeToByteArray())
        }
    }
}.toByteArray()

internal fun backupZip(
    vararg entries: Pair<String, String>,
    versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    packageName: String = BuildConfig.APPLICATION_ID,
) = zipOf(
    "metadata.json" to """{"packageName":"$packageName","versionCode":$versionCode,"versionName":"1","exportTime":0}""",
    *entries,
).inputStream()

/** FcitxApplication's flag has no setter. */
internal fun directBoot(on: Boolean) {
    FcitxApplication::class.java.getDeclaredField("isDirectBootMode").apply { isAccessible = true }.setBoolean(FcitxApplication.getInstance(), on)
}

/** AppPrefs outlives each test's application, so not this one's default prefs. */
internal fun rawPrefs(): SharedPreferences =
    AppPrefs::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }.get(AppPrefs.getInstance()) as SharedPreferences
