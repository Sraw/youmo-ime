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
 * the profile names the engine's input methods instead, and the settings of pinyin and of the
 * tables become the androidengine addon's. What the user learned is read in later, by the engine (EngineBridge).
 */
object EngineMigration {

    /** [configHome] and [dataHome] are fcitx's (FCITX_CONFIG_HOME, FCITX_DATA_HOME). */
    fun run(configHome: File, dataHome: File) {
        try {
            // the tables the user imported: libime's table addon is not in the app any more
            tables(File(dataHome, "inputmethod"))
            val profile = File(configHome, "profile")
            if (profile.exists()) LibimeMigration.profile(profile.readText())?.let { replace(profile, it) }
            val settings = File(configHome, "conf/androidengine.conf")
            val pinyin = File(configHome, "conf/pinyin.conf")
            // libime keeps a table's settings by the table's name, not under conf/ as addons do
            val tables = LibimeMigration.TABLE_CONFIGS.map { it to File(configHome, "table/$it.conf") }
                .filter { it.second.exists() }.associate { (name, file) -> name to file.readText() }
            if (!settings.exists()) {
                if (pinyin.exists() || tables.isNotEmpty()) {
                    replace(settings, LibimeMigration.settings(if (pinyin.exists()) pinyin.readText() else "", tables))
                }
            } else if (tables.isNotEmpty()) {
                // migrated by a build whose engine had no table settings yet
                LibimeMigration.withTables(settings.readText(), tables)?.let { replace(settings, it) }
            }
            val chttrans = File(configHome, "conf/chttrans.conf")
            if (chttrans.exists()) LibimeMigration.inputMethodList(chttrans.readText())?.let { replace(chttrans, it) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // run before every start: whatever it trips on must not keep fcitx from starting
            // fcitx starts with its defaults then: the old input methods are just not there
            Timber.w(e, "cannot migrate libime's config")
        }
    }

    /**
     * The tables the user imported: libime's table addon is not in the app any more. Not one named
     * as libime's own (a wbx.conf of their own, say): the profile names the engine's for those.
     */
    private fun tables(dir: File) {
        val confs = dir.listFiles { f -> f.isFile && f.name.endsWith(".conf") }.orEmpty()
        for (conf in confs.filter { it.name.removeSuffix(".conf") !in LibimeMigration.INPUT_METHODS }) {
            try {
                LibimeMigration.tableInputMethod(conf.readText())?.let { replace(conf, it) }
            } catch (e: IOException) {
                // the others, and the rest of the migration, all the same
                Timber.w(e, "cannot migrate %s", conf)
            }
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
