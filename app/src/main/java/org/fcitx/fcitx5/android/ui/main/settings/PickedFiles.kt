/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A file the user picked, to read: an IOException, not null, from a provider that gives no stream, and for one
 * [MainActivity.importable] refuses (a scheme but `content:` and `file:`, a `file:` path into the app's own
 * directories, read with the app's rights: L2 security review N5).
 */
internal fun ContentResolver.openInputStreamOrThrow(uri: Uri): InputStream =
    openInputStream(checked(uri)) ?: throw IOException("cannot read $uri")

/** A file the user picked, to write: an IOException, not null, from a provider that gives no stream, and as above. */
internal fun ContentResolver.openOutputStreamOrThrow(uri: Uri): OutputStream =
    openOutputStream(checked(uri)) ?: throw IOException("cannot write $uri")

// the pickers give content: ones; a file: one comes of another app's ACTION_VIEW (old file managers), MainActivity's rule
private fun checked(uri: Uri): Uri = uri.takeIf { MainActivity.importable(appContext, it) } ?: throw IOException("not opened: ${uri.scheme}")

/**
 * [block], which reads or writes a file the user picked; what goes wrong there is logged as [what]
 * and shown, not thrown: an IOException, or the SecurityException of a file the picker gave that
 * its provider will not open after all.
 */
internal inline fun Context.showingFileErrors(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        Timber.w(e, what)
        toast(e)
    } catch (e: SecurityException) {
        Timber.w(e, what)
        toast(e)
    }
}
