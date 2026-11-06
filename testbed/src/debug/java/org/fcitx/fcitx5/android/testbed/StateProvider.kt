/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.testbed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Debug builds only: the native fields' text, selection and composing span, for uat.py
 * (`adb shell content query --uri content://org.fcitx.fcitx5.android.testbed.state`), one row
 * with one column `json`. Exported, as `content` runs as the shell, but anyone other than the
 * shell or root is refused: no other app reads what is typed here.
 */
class StateProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("adb shell only")
        val out = JSONArray()
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            Fields.all().forEach { out.put(it.state()) }
            done.countDown()
        }
        done.await(TIMEOUT_S, TimeUnit.SECONDS)
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(out.toString())) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val TIMEOUT_S = 5L
    }
}
