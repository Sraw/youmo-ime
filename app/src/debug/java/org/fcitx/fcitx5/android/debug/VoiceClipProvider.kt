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
import org.fcitx.fcitx5.android.input.voice.VoiceClipMic
import timber.log.Timber
import java.io.File

/**
 * The device UAT's clip microphone ([VoiceClipMic]), from the shell only (uid 2000, or root; the
 * manifest asks for DUMP too):
 *
 *     adb shell content write --uri content://<package>.voiceclip/a.wav < a.wav   # into files/voiceclips/
 *     adb shell content call --uri content://<package>.voiceclip --method queue --arg a.wav,800,b.wav
 *     adb shell content call --uri content://<package>.voiceclip --method on      # the clips, none queued yet
 *     adb shell content call --uri content://<package>.voiceclip --method state
 *     adb shell content call --uri content://<package>.voiceclip --method clear
 *
 * A queue's names are files in files/voiceclips/ (16 MB at most), its numbers pauses in ms (to
 * 60 s); from it on every talk hears the clips, not the microphone, till cleared. Debug builds only.
 */
class VoiceClipProvider : ContentProvider() {

    override fun onCreate() = true

    private fun shellOnly() {
        val uid = Binder.getCallingUid() % PER_USER
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("the shell's only, not uid $uid")
    }

    private fun dir() = File(requireNotNull(context).filesDir, "voiceclips")

    // a name in files/voiceclips/, no path out of it; no absolute paths either: the app's own files are enough
    private fun clip(name: String): File {
        require('/' !in name && name != "." && name != "..") { "a name, not a path: $name" }
        return File(dir(), name)
    }

    private fun part(name: String): VoiceClipMic.Part = name.toIntOrNull()?.let { VoiceClipMic.Part.Pause(it) } ?: run {
        val file = clip(name)
        require(file.isFile) { "no clip $name" }
        require(file.length() <= MAX_BYTES) { "$name is over $MAX_BYTES bytes" }
        VoiceClipMic.Part.Clip(name, VoiceClipMic.decode(file.readBytes()))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        shellOnly()
        when (method) {
            "queue" -> {
                // all of it read before any is queued: a clip missing leaves the queue as it was
                val parts = arg.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::part)
                VoiceClipMic.queue(parts)
                Timber.w("voice clip microphone: queued %s", arg)
            }
            "on" -> {
                VoiceClipMic.queue(emptyList())
                Timber.w("voice clip microphone: on")
            }
            "clear" -> VoiceClipMic.clear()
            "state" -> Unit
            else -> throw IllegalArgumentException("method: queue, on, clear or state, not $method")
        }
        return Bundle().apply { putString("state", VoiceClipMic.state()) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        shellOnly()
        val file = clip(requireNotNull(uri.lastPathSegment) { "content://…voiceclip/<name>" })
        dir().mkdirs()
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
        const val MAX_BYTES = 16L shl 20
    }
}
