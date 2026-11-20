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

/** An IOException instead of null for no stream, and for what [MainActivity.importable] refuses (app-private `file:` paths). */
internal fun ContentResolver.openInputStreamOrThrow(uri: Uri): InputStream =
    openInputStream(checked(uri)) ?: throw IOException("cannot read $uri")

/** As above, to write. */
internal fun ContentResolver.openOutputStreamOrThrow(uri: Uri): OutputStream =
    openOutputStream(checked(uri)) ?: throw IOException("cannot write $uri")

// pickers give content:; a file: comes from another app's ACTION_VIEW (old file managers)
private fun checked(uri: Uri): Uri = uri.takeIf { MainActivity.importable(appContext, it) } ?: throw IOException("not opened: ${uri.scheme}")

/** Logged and shown, not thrown: an IOException, or the SecurityException of a provider refusing a picked file. */
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
