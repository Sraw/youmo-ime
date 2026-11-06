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
import java.io.File

/**
 * The device UAT's clip microphone ([VoiceClipMic]), from the shell only (uid 2000, or root):
 *
 *     adb shell content write --uri content://<package>.voiceclip/a.wav < a.wav   # into files/voiceclips/
 *     adb shell content call --uri content://<package>.voiceclip --method queue --arg a.wav,800,b.wav
 *     adb shell content call --uri content://<package>.voiceclip --method on      # the clips, none queued yet
 *     adb shell content call --uri content://<package>.voiceclip --method state
 *     adb shell content call --uri content://<package>.voiceclip --method clear
 *
 * A queue's names are files in files/voiceclips/ (or absolute paths the app can read), its numbers
 * pauses in ms; from it on every talk hears the clips, not the microphone, till cleared. The voice
 * debug build only.
 */
class VoiceClipProvider : ContentProvider() {

    override fun onCreate() = true

    private fun shellOnly() {
        val uid = Binder.getCallingUid() % PER_USER
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("the shell's only, not uid $uid")
    }

    private fun dir() = File(requireNotNull(context).filesDir, "voiceclips")

    private fun part(name: String): VoiceClipMic.Part = name.toIntOrNull()?.let { VoiceClipMic.Part.Pause(it) } ?: run {
        val file = if (name.startsWith("/")) File(name) else File(dir(), name)
        VoiceClipMic.Part.Clip(name, VoiceClipMic.decode(file.readBytes()))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        shellOnly()
        when (method) {
            "queue" -> VoiceClipMic.queue(arg.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::part))
            "on" -> VoiceClipMic.queue(emptyList())
            "clear" -> VoiceClipMic.clear()
            "state" -> Unit
            else -> throw IllegalArgumentException("method: queue, on, clear or state, not $method")
        }
        return Bundle().apply { putString("state", VoiceClipMic.state()) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        shellOnly()
        val name = requireNotNull(uri.lastPathSegment) { "content://…voiceclip/<name>" }
        require('/' !in name && name != "..") { "a name, not a path: $name" }
        val file = File(dir().apply { mkdirs() }, name)
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
    }
}
