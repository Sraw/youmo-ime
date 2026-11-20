/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.UserManagerCompat

/**
 * An input method cannot ask from its own window. Once refused for good the system shows no dialog and
 * refuses at once: then, and only then, the app's settings page.
 */
class VoicePermissionActivity : ComponentActivity() {

    // kept over a rebuild (a rotation, the dark theme) while the dialog shows
    private var refusedForGood = false
    // a refusal that comes back at once is the system's, no dialog shown
    private var askedAt = 0L

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // a dialog dismissed (Android 11+: a tap outside) is no refusal for good, though the system gives no rationale
        // after it either: only a refusal with no dialog opens the settings
        val noDialog = SystemClock.uptimeMillis() - askedAt < NO_DIALOG_MS
        val forGood = refusedForGood && noDialog
        if (!granted && forGood && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            refusedForGood = savedInstanceState.getBoolean(REFUSED_FOR_GOOD)
            // rebuilt with the dialog up: its answer is the user's, not the system's at once
            askedAt = 0L
            return
        }
        // not AppPrefs: a mark of what this device's system was asked, not a setting to back up or restore elsewhere
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        refusedForGood = prefs.getBoolean(ASKED, false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        prefs.edit().putBoolean(ASKED, true).apply()
        askedAt = SystemClock.uptimeMillis()
        ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(REFUSED_FOR_GOOD, refusedForGood)
    }

    companion object {
        private const val PREFS = "voice"
        private const val ASKED = "microphone_asked"
        private const val REFUSED_FOR_GOOD = "refused_for_good"
        // a dialog the user answers takes longer than this; the system's own refusal comes back within it
        private const val NO_DIALOG_MS = 500L

        fun start(context: Context) {
            // before the first unlock the keyboard runs, this activity cannot
            if (!UserManagerCompat.isUserUnlocked(context)) return
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
