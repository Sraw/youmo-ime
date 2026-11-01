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
 * The app's external storage there, as on a phone, and the homes settled again with it. Robolectric's
 * has no state, so it reads as away; whether the home is then made anyway (`fresh`) hangs on the first-run
 * flag other tests leave in the prefs; and an app made with FcitxApplication settled the themes before the
 * test began. A test that writes what the user added calls it first, or it is refused by the order it ran in.
 */
fun phoneStorage() {
    ShadowEnvironment.setExternalStorageState(appContext.getExternalFilesDir(null)!!, Environment.MEDIA_MOUNTED)
    HostConfigDir.forget()
}

/**
 * An install's directories under [root], named as on a phone, made as they are needed: by name, so a
 * field Dirs gains is added here once and two of them are never swapped unseen.
 */
internal fun dirsUnder(
    root: File,
    external: File = File(root, "external"),
    config: File = File(root, "files/host-config"),
    data: File = File(external, ConfigHome.DATA.old),
    theme: File = File(external, ConfigHome.THEME.old),
    settled: Boolean = true,
) = UserDataManager.Dirs(
    sharedPrefs = File(root, "shared_prefs"),
    dataBases = File(root, "databases"),
    external = external,
    recentlyUsed = File(root, "recently_used"),
    engine = File(root, "engine"),
    config = config,
    data = data,
    theme = theme,
    settled = settled,
)

// a backup as the app writes one: its metadata, then entries (a path in the zip to its text)
internal fun backupZip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry("metadata.json"))
        zip.write("""{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":${BuildConfig.VERSION_CODE},"versionName":"1","exportTime":0}""".encodeToByteArray())
        for ((name, text) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.encodeToByteArray())
        }
    }
}.toByteArray().inputStream()

/** The app as before the first unlock ([on]) or after it: FcitxApplication's flag, which has no setter. */
internal fun directBoot(on: Boolean) {
    FcitxApplication::class.java.getDeclaredField("isDirectBootMode").apply { isAccessible = true }.setBoolean(FcitxApplication.getInstance(), on)
}

/** AppPrefs' own store: the instance outlives each test's application, so not this one's default prefs. */
internal fun rawPrefs(): SharedPreferences =
    AppPrefs::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }.get(AppPrefs.getInstance()) as SharedPreferences
