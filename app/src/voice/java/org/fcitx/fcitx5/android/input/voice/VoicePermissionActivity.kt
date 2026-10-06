/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.UserManagerCompat

/**
 * Asks for the microphone, which an input method cannot do from its own window, and closes. Once
 * refused for good the system shows no dialog and refuses at once: then, and only then, the
 * app's settings page, where it can still be granted.
 */
class VoicePermissionActivity : ComponentActivity() {

    // asked before, and the system not to explain why: refused for good, or never asked at all
    private var refusedForGood = false

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && refusedForGood && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        refusedForGood = prefs.getBoolean(ASKED, false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        prefs.edit().putBoolean(ASKED, true).apply()
        ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        private const val PREFS = "voice"
        private const val ASKED = "microphone_asked"

        fun start(context: android.content.Context) {
            // before the first unlock the keyboard runs, this activity cannot
            if (!UserManagerCompat.isUserUnlocked(context)) return
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
