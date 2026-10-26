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
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.HostDispatcher
import org.fcitx.fcitx5.android.engine.host.BackgroundWork

/**
 * Whether the input method is idle, for the device UAT (uat.py's Device.idle, through `content
 * query --uri content://<package>.uat.idle`): no job waiting for the fcitx thread, no refine due,
 * no background work ([BackgroundWork]), nothing due on the main thread. The UAT read the
 * candidates the moment some showed, and a refine or a late table coding changed them after (the
 * first candidate of T9-02 read too early under load). Debug builds only, and only to the shell
 * (the manifest asks for DUMP): it tells whoever reads it whether you are typing.
 */
class UatIdleProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val fcitx = HostDispatcher.pendingJobs
        val refine = EngineBridge.refining
        val background = BackgroundWork.busy
        val main = Looper.getMainLooper().queue.isIdle
        val idle = fcitx == 0 && !refine && background == 0 && main
        return MatrixCursor(arrayOf("idle", "fcitx", "refine", "background", "main")).apply {
            addRow(arrayOf(if (idle) 1 else 0, fcitx, if (refine) 1 else 0, background, if (main) 1 else 0))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
