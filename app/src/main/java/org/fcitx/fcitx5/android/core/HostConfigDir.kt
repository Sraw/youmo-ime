/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.Environment
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMaps
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.host.ConfigHome
import timber.log.Timber
import java.io.File

/** [dir] null: nothing there, every setting at its default; [problem] is what keeps it from the app's own files. */
data class HostConfig(val dir: File?, val writable: Boolean, val problem: ConfigHome.Problem? = null)

/** [dir] is always one (under the cache with none settled); write only if [writable]. */
data class InUse(val dir: File, val writable: Boolean)

/** Where the user's fcitx directories are: decided once a process ([ConfigHome.Settled]), so engine, editors and backups all agree. */
internal object HostConfigDir {

    // one per process; a JVM test's app each its own
    @Volatile
    private var settled: Pair<FcitxApplication, ConfigHome.Settled>? = null

    // a JVM test's app settled the themes as it was made, before the test could set the storage up
    @VisibleForTesting
    internal fun forget() {
        settled = null
    }

    // before the unlock (direct boot) nothing is settled; the process restarts at unlock
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
            // a failed stat reads as nothing there: away, not empty
            external = {
                context.getExternalFilesDir(null)?.takeIf { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                    ?.let(::JvmFiles)
            },
            // an upgraded phone that lost files/engine is not taken for new: its older build's data may be away
            fresh = { !File(files, "engine").exists() && AppPrefs.getInstance().internal.firstRun.getValue() },
            room = { files.usableSpace },
        ) { which, place ->
            place.movedFrom?.let { Timber.i("%s moved in from %s", which.name, it) }
            when (place.problem) {
                ConfigHome.Problem.EXTERNAL_AWAY -> Timber.e("%s not moved yet and the external storage is away: nothing written this run", which.name)
                ConfigHome.Problem.NOT_MOVED -> Timber.e(place.error, "%s not moved: used where it is, the move tried at the next start", which.name)
                null -> {}
            }
            if (which == ConfigHome.DATA && place.writable) {
                // not one that stops the engine's start: tried again at the next
                dir(which, place.use)?.let { runCatching { resetPunctuationOnce(it) }.onFailure { e -> Timber.w(e, "punctuation reset failed") } }
            }
        }
    }

    /** The first caller moves an older build's in, the others wait. Before the unlock: none, nothing written. */
    fun settle(which: ConfigHome.Home = ConfigHome.CONFIG): HostConfig {
        if (FcitxApplication.getInstance().isDirectBootMode) return HostConfig(null, writable = false)
        val place = settled().of(which)
        // the storage gone since: nowhere to write, rather than a write that silently goes nowhere
        val dir = dir(which, place.use)
        return HostConfig(dir, place.writable && dir != null, place.problem)
    }

    /** Unless [InUse.writable], saves, imports, backups and restores refuse (`user_data_not_settled`): the next start would not read them. */
    fun inUse(which: ConfigHome.Home): InUse = settle(which).let { InUse(it.dir ?: unsettled(which), it.writable) }

    /** Under the cache, never `files/data` or the like, which [ConfigHome] would take for an older build's fallback. */
    fun unsettled(which: ConfigHome.Home): File =
        File(FcitxApplication.getInstance().directBootAwareContext.cacheDir, "${which.name}.unsettled")

    /** There is no 中文标点 page: an edited table is deleted once; a restore's mark is cleared (AppPrefs.afterImport) so this retries. */
    @VisibleForTesting
    internal fun resetPunctuationOnce(data: File) {
        val done = AppPrefs.getInstance().internal.punctuationReset061
        if (done.getValue()) return
        if (PunctuationMaps.dropEdited(data)) done.setValue(true) else Timber.w("an edited punctuation table not deleted: tried again at the next start")
    }

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
