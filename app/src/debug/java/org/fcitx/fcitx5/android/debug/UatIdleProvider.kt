/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import org.fcitx.fcitx5.android.core.HostDispatcher
import org.fcitx.fcitx5.android.engine.runtime.BackgroundWork

/**
 * Whether the input method is idle, for uat.py's Device.idle: no fcitx job, [BackgroundWork] or main-thread
 * work due, so candidates are read only once settled. Shell only (DUMP): it tells the reader whether you are typing.
 */
class UatIdleProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val fcitx = HostDispatcher.pendingJobs
        val background = BackgroundWork.busy
        val main = Looper.getMainLooper().queue.isIdle
        val idle = fcitx == 0 && background == 0 && main
        return MatrixCursor(arrayOf("idle", "fcitx", "background", "main")).apply {
            addRow(arrayOf(if (idle) 1 else 0, fcitx, background, if (main) 1 else 0))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
