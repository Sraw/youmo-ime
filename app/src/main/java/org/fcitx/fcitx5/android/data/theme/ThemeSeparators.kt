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

/** FB12: the packs installed before 0.6.1, and those an older backup restores, given the app's 分词 ([addSeparatorsOnce]). */
object ThemeSeparators {

    /**
     * Where it is kept that this install's packs were given 分词 ([addSeparatorsOnce]): this phone's, not backed up, nor in
     * the prefs, which a restore writes over (a backup of 0.6.0 restored would have every pack given it again, one of
     * 0.6.1 its packs too, which were imported after).
     */
    @VisibleForTesting
    internal var separatorsFile: () -> File = { File(appContext.noBackupFilesDir, "theme-separator-added") }

    /** The packs a backup of before 0.6.1 restored, to be given 分词 at the next start ([restoredBeforeSeparator]). */
    @VisibleForTesting
    internal var restoredFile: () -> File = { File(appContext.noBackupFilesDir, "theme-separator-restored") }

    /**
     * There while a start gives packs 分词: one that finds it there ended in the middle (killed, or an Error such as
     * StackOverflowError out of Application.onCreate), and does it once more with the marker written and the list
     * dropped first, so a pack that kills the app costs one start more, never every start.
     */
    @VisibleForTesting
    internal var tryingFile: () -> File = { File(appContext.noBackupFilesDir, "theme-separator-trying") }

    /**
     * FB12 (dev/design/061-theme-separator-migration.md): the packs installed before 0.6.1 given the app's 分词 in their
     * qwerty's Shift's place while pinyin is typed, as the app's own qwerty has it since ([ThemePackFiles.addSeparator]),
     * once an install, at the first start the themes can be written (A4: none before the phone is unlocked); and the
     * packs a backup of before 0.6.1 brought ([restoredBeforeSeparator]) at the start after its restore. The packs imported
     * after are as they are written. Run by ThemeManager before the packs are listed, on the main thread of
     * Application.onCreate: it never throws (a pack that fails is left as it was, logged, and not tried again).
     * Under the lock [restoredBeforeSeparator] takes: a restore's list is not added to between its read and its deletion.
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
                // the last start ended in the middle: once more, not again
                written(marker)
                restored.delete()
            } else {
                written(trying)
            }
            // a kill in the middle of an import put right first: the pack it replaced back, what it left cleared
            ThemeFilesManager.sweepPacksOnce()
            val dirs = ThemePackFiles.folder(ThemeFilesManager.dir).listFiles(FileFilter { it.isDirectory && !it.name.startsWith(".") && (names == null || it.name in names) })
            // once an install, on the start's main thread: how long, for the packs a phone may have (impl report)
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

    // [file] there, empty, on the disk
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

    /**
     * The packs [names] a backup made before 0.6.1 restored (UserDataManager), each replacing the phone's of its name: to
     * be given 分词 at the next start, as the ones installed then were. One of 0.6.1 or later brings its packs as they
     * were (given it already, or imported after).
     */
    @Synchronized
    fun restoredBeforeSeparator(names: Collection<String>) {
        // added to: two old backups restored without a start between
        if (names.isNotEmpty()) writeRestored(readRestored() + names)
    }

    /** The pack [name] imported (placed): not one an old backup restored any more, as it is written. */
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
