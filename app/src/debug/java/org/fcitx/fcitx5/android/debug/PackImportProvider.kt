/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackStatus
import java.io.File

/**
 * Pack import for the device UAT, shell only (uid 2000 or root; DUMP in the manifest), through [PackStore.import]
 * with the settings page's checks:
 *
 *     adb shell content write --uri content://<package>.uat.packs/text.youmopack < youmo-text-v1.youmopack
 *     adb shell content call --uri content://<package>.uat.packs --method import --arg text.youmopack
 *     adb shell content call --uri content://<package>.uat.packs --method state
 *
 * The picker a test cannot drive on every Android version; PacksFragment's Robolectric test covers that part.
 */
class PackImportProvider : ContentProvider() {

    override fun onCreate() = true

    private fun shellOnly() {
        val uid = Binder.getCallingUid() % PER_USER
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("the shell's only, not uid $uid")
    }

    private fun dir() = File(requireNotNull(context).cacheDir, "uat-packs")

    private fun file(name: String): File {
        require('/' !in name && name != "." && name != "..") { "a name, not a path: $name" }
        return File(dir(), name)
    }

    // a process started for this call: the binder thread may run before Application.onCreate has finished
    private fun appCreated() {
        val until = SystemClock.uptimeMillis() + APP_WAIT_MS
        while (runCatching { FcitxApplication.getInstance() }.isFailure) {
            check(SystemClock.uptimeMillis() < until) { "the app was not created in $APP_WAIT_MS ms" }
            Thread.sleep(POLL_MS)
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        shellOnly()
        appCreated()
        val out = Bundle()
        when (method) {
            "import" -> {
                val file = file(requireNotNull(arg) { "--arg <name written in>" })
                require(file.isFile) { "no $arg written in" }
                val result = try {
                    runBlocking { file.inputStream().use { PackStore.import(it) } }
                } finally {
                    file.delete()
                }
                out.putString("result", describe(result))
            }
            "state" -> Unit
            else -> throw IllegalArgumentException("method: import or state, not $method")
        }
        for (kind in PackKind.entries) out.putString(kind.id, describe(PackStore.status(kind)))
        return out
    }

    private fun describe(result: ImportResult) = when (result) {
        is ImportResult.Imported -> "imported:${result.manifest.kind.id}:v${result.manifest.version}"
        is ImportResult.AlreadyThere -> "already:${result.manifest.kind.id}:v${result.manifest.version}"
        is ImportResult.Refused -> "refused:${result.why.javaClass.simpleName}"
        is ImportResult.KeptNewer -> "kept:${result.installed.kind.id}:v${result.installed.version}"
    }

    private fun describe(status: PackStatus) = when (status) {
        PackStatus.Missing -> "missing"
        is PackStatus.Ready -> "v${status.manifest.version}"
        is PackStatus.NeedsUpdate -> "needs-update:v${status.manifest.version}"
        is PackStatus.NeedsNewerApp -> "needs-app:v${status.manifest.version}"
        is PackStatus.Damaged -> "damaged:v${status.manifest.version}"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        shellOnly()
        val file = file(requireNotNull(uri.lastPathSegment) { "content://…uat.packs/<name>" })
        dir().mkdirs()
        // a run stopped before its import left a pack (up to ~180 MB) in the cache: one pack at a time
        if ('w' in mode) dir().listFiles()?.forEach { if (it.name != file.name) it.delete() }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val PER_USER = 100_000
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val APP_WAIT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
