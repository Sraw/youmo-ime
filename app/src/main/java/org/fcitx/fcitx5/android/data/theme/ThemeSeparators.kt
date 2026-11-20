/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import java.io.FileOutputStream
import java.io.IOException

/** Pre-0.6.1 packs, and those an older backup restores, get the app's 分词 ([addSeparatorsOnce]). */
object ThemeSeparators {

    /** This phone's, not backed up nor in the prefs: a restore would overwrite them and redo every pack. */
    @VisibleForTesting
    internal var separatorsFile: () -> File = { File(appContext.noBackupFilesDir, "theme-separator-added") }

    /** Given 分词 at the next start ([restoredBeforeSeparator]). */
    @VisibleForTesting
    internal var restoredFile: () -> File = { File(appContext.noBackupFilesDir, "theme-separator-restored") }

    /** Present while a start works: found, the last ended midway (killed, or an Error), so it runs once more with marker and list cleared first. */
    @VisibleForTesting
    internal var tryingFile: () -> File = { File(appContext.noBackupFilesDir, "theme-separator-trying") }

    /**
     * Puts 分词 in qwerty Shift's place while pinyin is typed ([ThemePackFiles.addSeparator]), once per install at the
     * first start themes can be written, and for a pre-0.6.1 backup's packs at the start after its restore. Runs on
     * Application.onCreate's main thread and never throws (a failing pack is logged and left). Under the restore's lock.
     */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun addSeparatorsOnce() {
        try {
            if (!ThemeFilesManager.writable) return
            val marker = separatorsFile()
            val restored = restoredFile()
            val trying = tryingFile()
            val all = !marker.exists()
            val names = if (all) null else if (restored.exists()) restored.readLines().filter { it.isNotEmpty() }.toSet() else null
            if (!all && names == null) {
                trying.delete()
                return
            }
            if (trying.exists()) {
                // the last start ended midway: once more, not again
                written(marker)
                restored.delete()
            } else {
                written(trying)
            }
            // an import killed midway put right first
            ThemeFilesManager.sweepPacksOnce()
            val dirs = ThemePackFiles.folder(ThemeFilesManager.dir).listFiles(FileFilter { it.isDirectory && !it.name.startsWith(".") && (names == null || it.name in names) })
            // once an install on the main thread: logged how long
            val start = System.nanoTime()
            if (!dirs.isNullOrEmpty()) addSeparators(dirs.toList())
            Timber.i("Theme packs given 分词: ${dirs?.size ?: 0} looked at in ${(System.nanoTime() - start) / NANOS_A_MS} ms")
            if (all) written(marker)
            restored.delete()
            trying.delete()
        } catch (e: Exception) {
            Timber.w(e, "Theme packs not given 分词")
            // caught, the start went on: not one that died, the next tries in full
            runCatching { tryingFile().delete() }
        }
    }

    private const val NANOS_A_MS = 1_000_000

    // there, empty, on the disk
    private fun written(file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.fd.sync() }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun addSeparators(dirs: List<File>) {
        val app = SceneLayouts.parseBuiltIn(appContext, "qwerty") ?: run {
            Timber.w("Theme packs not given 分词: the app's qwerty does not read")
            return
        }
        val appText = BuiltInLayouts(appContext)::text
        for (pack in dirs) {
            try {
                val why = ThemePackFiles.addSeparator(ThemeFilesManager.dir, pack, app, appText)
                if (why == null) Timber.i("Theme pack ${pack.name} given 分词") else Timber.i("Theme pack ${pack.name} not given 分词: $why")
            } catch (e: Exception) {
                Timber.w(e, "Theme pack ${pack.name} not given 分词")
            }
        }
    }

    /** Each replacing the phone's of its name; a 0.6.1+ backup brings its packs as they were. */
    @Synchronized
    fun restoredBeforeSeparator(names: Collection<String>) {
        // added to: two old backups restored without a start between
        if (names.isNotEmpty()) writeRestored(readRestored() + names)
    }

    /** Imported (placed): no longer an old backup's, as written. */
    @Synchronized
    internal fun importedOverRestored(name: String) {
        val names = readRestored()
        if (name in names) writeRestored(names - name)
    }

    private fun readRestored() = restoredFile().takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() }.orEmpty()

    private fun writeRestored(names: List<String>) {
        val file = restoredFile()
        if (names.isEmpty()) {
            file.delete()
            return
        }
        val temp = File(file.parentFile, file.name + ".new")
        file.parentFile?.mkdirs()
        FileOutputStream(temp).use { it.write(names.distinct().joinToString("\n", postfix = "\n").encodeToByteArray()); it.fd.sync() }
        if (!temp.renameTo(file)) throw IOException("cannot record the packs restored")
    }
}
