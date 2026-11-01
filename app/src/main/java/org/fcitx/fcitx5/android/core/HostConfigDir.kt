/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.Environment
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.host.ConfigHome
import timber.log.Timber
import java.io.File

/**
 * One of the user's directories this run ([ConfigHome.Home]): read from [dir] (none: nothing there,
 * every setting at its default), written to only if [writable]; [problem], what keeps it from the
 * app's own files.
 */
data class HostConfig(val dir: File?, val writable: Boolean, val problem: ConfigHome.Problem? = null)

/**
 * [HostConfig] for an editor, a backup, a restore: [dir] is always one, under the cache with none
 * settled ([HostConfigDir.unsettled]); nothing may be written to it unless [writable].
 */
data class InUse(val dir: File, val writable: Boolean)

/**
 * Where the user's directories fcitx kept on the external storage are on the phone ([ConfigHome]):
 * the config, settled by the host's start; fcitx's data, by the host's start or whichever of its
 * editors comes first; the themes, by the theme list at the app's start. One decision a process
 * ([ConfigHome.Settled]): the engine, the editors and a backup all read and write the same directory.
 */
internal object HostConfigDir {

    // the application's: one a process, a JVM test's app each its own
    @Volatile
    private var settled: Pair<FcitxApplication, ConfigHome.Settled>? = null

    // a JVM test's: its app settled the themes as it was made (ThemeManager), before the test could set the storage up
    @VisibleForTesting
    internal fun forget() {
        settled = null
    }

    // made once the user has unlocked: before (direct boot) nothing is settled, and the process restarts at unlock
    private fun settled(): ConfigHome.Settled {
        val context = FcitxApplication.getInstance()
        settled?.let { (app, it) -> if (app === context) return it }
        return synchronized(this) {
            settled?.takeIf { it.first === context }?.second ?: made(context).also { settled = context to it }
        }
    }

    private fun made(context: FcitxApplication): ConfigHome.Settled {
        val files = context.filesDir
        return ConfigHome.Settled(
            JvmFiles(files),
            // one not mounted (a failed stat reads as nothing there) is away, not empty
            external = {
                context.getExternalFilesDir(null)?.takeIf { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                    ?.let(::JvmFiles)
            },
            // a new install's first start: the engine's directory not made yet, and the first run not done
            // (an upgraded phone that lost files/engine is not taken for new: its older build's may be away)
            fresh = { !File(files, "engine").exists() && AppPrefs.getInstance().internal.firstRun.getValue() },
            room = { files.usableSpace },
        ) { which, place ->
            place.movedFrom?.let { Timber.i("%s moved in from %s", which.name, it) }
            when (place.problem) {
                ConfigHome.Problem.EXTERNAL_AWAY -> Timber.e("%s not moved yet and the external storage is away: nothing written this run", which.name)
                ConfigHome.Problem.NOT_MOVED -> Timber.e(place.error, "%s not moved: used where it is, the move tried at the next start", which.name)
                null -> {}
            }
        }
    }

    /**
     * [which] this process, an older build's moved in first by whichever asks first, the others
     * waiting for it ([ConfigHome.Settled]). Before the user unlocks (direct boot) the user's files
     * are not there: none, nothing written, the process restarted at unlock.
     */
    fun settle(which: ConfigHome.Home = ConfigHome.CONFIG): HostConfig {
        if (FcitxApplication.getInstance().isDirectBootMode) return HostConfig(null, writable = false)
        val place = settled().of(which)
        // the external storage away since it was settled: nowhere to write, not a write that silently goes nowhere
        val dir = dir(which, place.use)
        return HostConfig(dir, place.writable && dir != null, place.problem)
    }

    /**
     * Where an editor, a backup and a restore find [which], as the engine does ([settle]); with none
     * to read, [unsettled]. Nothing is written to it unless [InUse.writable]: an editor's save, an
     * import, a backup and a restore refuse then (`user_data_not_settled`), as what they wrote would
     * be where the next start does not read.
     */
    fun inUse(which: ConfigHome.Home): InUse = settle(which).let { InUse(it.dir ?: unsettled(which), it.writable) }

    /**
     * What stands for [which] with none settled: under the cache of the storage there is (the device's
     * before the unlock), never `files/data` or the like, which [ConfigHome] would take for an older
     * build's fallback and read at the next start.
     */
    fun unsettled(which: ConfigHome.Home): File =
        File(FcitxApplication.getInstance().directBootAwareContext.cacheDir, "${which.name}.unsettled")

    private fun dir(which: ConfigHome.Home, use: ConfigHome.Use): File? {
        val context = FcitxApplication.getInstance()
        return when (use) {
            ConfigHome.Use.HOME -> File(context.filesDir, which.name)
            ConfigHome.Use.EXTERNAL -> context.getExternalFilesDir(null)
                ?.takeIf { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }?.let { File(it, which.old) }
            ConfigHome.Use.FALLBACK -> File(context.filesDir, which.old)
            ConfigHome.Use.NONE -> null
        }
    }
}
