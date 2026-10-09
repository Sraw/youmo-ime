/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.content.Context
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object UserDataManager {

    private val json = Json { prettyPrint = true }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    private fun writeFileTree(srcDir: File, destPrefix: String, dest: ZipOutputStream) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.getExternalFilesDir(null)!!
    private val recentlyUsedDir = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    // the engine's user store (EngineBridge): what it learnt of the user's words, user tables
    private val engineDir = appContext.filesDir.resolve("engine")

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs
            writeFileTree(sharedPrefsDir, "shared_prefs", zipStream)
            // databases
            writeFileTree(dataBasesDir, "databases", zipStream)
            // external
            writeFileTree(externalDir, "external", zipStream)
            // engine
            if (engineDir.isDirectory) writeFileTree(engineDir, "engine", zipStream)
            // recently_used moved to SharedPreference and shoud not be exported
            // metadata
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val metadata = Metadata(
                pkgInfo.packageName,
                pkgInfo.versionCodeCompat,
                Const.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun copyDir(source: File, target: File) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            source.copyRecursively(target, overwrite = true)
        } else {
            Timber.w("Cannot import user data: path='${source.path}', exists=$exists, isDir=$isDir")
        }
    }

    fun import(src: InputStream) = runCatching {
        withTempDir { tempDir ->
            // closed before anything is copied: nothing may fail the import once the engine's is in
            val extracted = ZipInputStream(src).use { it.extract(tempDir) }
            val metadataFile = extracted.find { it.name == "metadata.json" }
                ?: errorRuntime(R.string.exception_user_data_metadata)
            val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
            val origin = UserDataOrigin.of(
                metadata.packageName, BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID_ROOT
            ) ?: errorRuntime(R.string.exception_user_data_package_name_mismatch)
            if (origin is UserDataOrigin.Legacy) {
                val prefs = File(tempDir, "shared_prefs")
                File(prefs, origin.preferences).takeIf { it.exists() }?.renameTo(File(prefs, origin.renamed))
            }
            // the settings it brings that have no UI go back to their defaults at the next start:
            // not here, where AppPrefs would write its own copy over the imported one
            importedUserDataMarker(appContext).createNewFile()
            copyDir(File(tempDir, "shared_prefs"), sharedPrefsDir)
            copyDir(File(tempDir, "databases"), dataBasesDir)
            copyDir(File(tempDir, "external"), externalDir)
            // keep importing recently_used for backwords compatibility
            copyDir(File(tempDir, "recently_used"), recentlyUsedDir)
            // last, for the same reason
            if (File(tempDir, "engine").exists()) importEngine(File(tempDir, "engine"))
            metadata
        }
    }

    /**
     * [source] copied over [engineDir], as [copyDir] would, but put in place in one rename: fcitx
     * stopped leaves the engine's logs open, and starts again if the import fails, so a log
     * replaced on its own would then be written where nothing reads it, and what it learns lost.
     */
    private fun importEngine(source: File) {
        // no directory: copyDir says so, and copies nothing
        if (!source.isDirectory) return copyDir(source, engineDir)
        replaceDirectory(engineDir, File(engineDir.path + ".import")) { staged ->
            if (engineDir.isDirectory) engineDir.copyRecursively(staged)
            source.copyRecursively(staged, overwrite = true)
        }
    }
}

/**
 * Left by [UserDataManager.import] for the next start, which puts the settings without a UI back
 * to their defaults ([org.fcitx.fcitx5.android.data.prefs.AppPrefs.resetHiddenSettings]); outside
 * the object, which the app's start must not make (it needs the external storage).
 */
fun importedUserDataMarker(context: Context) = File(context.noBackupFilesDir, "user_data_imported")