/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.annotation.RequiresApi
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber

inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? {
    // https://issuetracker.google.com/issues/240585930#comment6
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key) as? T
    }
}

inline fun <reified T : Parcelable> Intent.parcelableArray(key: String): Array<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        getParcelableArrayExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        getParcelableArrayExtra(key) as? Array<T>
    }
}

/** Opens [url] in a browser; with none to open it (some work profiles, TVs) shows the address instead. */
fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "no browser for %s", url)
        toast(url, Toast.LENGTH_LONG)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
fun buildPrimaryStorageIntent(path: String = ""): Intent {
    val initialUri = appContext.storageManager.primaryStorageVolume.createOpenDocumentTreeIntent()
        .parcelable<Uri>(DocumentsContract.EXTRA_INITIAL_URI)!!
    val uri = Uri.Builder()
        .scheme(initialUri.scheme)
        .authority(initialUri.authority)
        .encodedPath(
            initialUri.path!!.replaceFirst("/root/", "/document/") +
                    Uri.encode(":Android/data/${BuildConfig.APPLICATION_ID}/files/$path")
        ).build()
    return Intent(Intent.ACTION_VIEW, uri)
}

fun buildDocumentsProviderIntent(): Intent {
    val uri = DocumentsContract.buildRootUri("${BuildConfig.APPLICATION_ID}.provider", "files")
    return Intent(Intent.ACTION_VIEW, uri)
}
