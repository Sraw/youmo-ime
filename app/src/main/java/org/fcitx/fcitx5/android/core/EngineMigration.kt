/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.host.LibimeMigration
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Carries over the config of those who used libime's pinyin and tables, before fcitx reads it:
 * the profile names the engine's input methods instead, and pinyin's settings become the
 * androidengine addon's. What the user learned is read in later, by the engine (EngineBridge).
 */
object EngineMigration {

    /** [configHome] is fcitx's (FCITX_CONFIG_HOME). */
    fun run(configHome: File) {
        try {
            val profile = File(configHome, "profile")
            if (profile.exists()) LibimeMigration.profile(profile.readText())?.let { replace(profile, it) }
            val settings = File(configHome, "conf/androidengine.conf")
            val pinyin = File(configHome, "conf/pinyin.conf")
            if (!settings.exists() && pinyin.exists()) replace(settings, LibimeMigration.settings(pinyin.readText()))
            val chttrans = File(configHome, "conf/chttrans.conf")
            if (chttrans.exists()) LibimeMigration.inputMethodList(chttrans.readText())?.let { replace(chttrans, it) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // run before every start: whatever it trips on must not keep fcitx from starting
            // fcitx starts with its defaults then: the old input methods are just not there
            Timber.w(e, "cannot migrate libime's config")
        }
    }

    // a profile cut short by a kill would lose every group
    private fun replace(file: File, text: String) {
        val next = File(file.path + ".new")
        // on disk before the rename: else a power cut may leave the new name on an empty file
        FileOutputStream(next).use {
            it.write(text.toByteArray())
            it.fd.sync()
        }
        if (!next.renameTo(file)) throw IOException("cannot replace $file")
    }
}
