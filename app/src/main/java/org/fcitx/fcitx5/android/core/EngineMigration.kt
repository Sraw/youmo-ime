/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.data.restoreDirectory
import org.fcitx.fcitx5.android.engine.host.AddonDefaults
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.LibimeMigration
import org.fcitx.fcitx5.android.engine.store.replaceFile
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Carries over the config of those who used libime's pinyin and tables, before fcitx reads it:
 * the profile names the engine's input methods instead, and the settings of pinyin and of the
 * tables become the androidengine addon's (androidkeyboard's word hints turned off too, as
 * English's suggestions). What the user learned is read in later, by the engine (EngineBridge).
 * Each file carried over is kept as it was before it is first rewritten ([keep]).
 * Before that, what a table imported in an older build left is deleted ([importedTablesGone]);
 * after it, punctuation.conf gets the app's default ([punctuationDefault]), and the tables typed
 * with before 调频 came off by default keep it on ([orderByUseKept]). None is kept to undo.
 */
object EngineMigration {

    /**
     * [configHome] is fcitx's (FCITX_CONFIG_HOME), beside its data home; [engineDir] the engine's
     * user data, null while the phone is locked.
     */
    fun run(configHome: File, engineDir: File?) {
        // run before every start: whatever a step trips on must not keep fcitx from starting, nor
        // the steps after it from running
        guarded("delete the imported tables") { importedTablesGone(configHome, engineDir) }
        // failing, fcitx starts with its defaults: the old input methods are just not there
        guarded("migrate libime's config") { libimeConfig(configHome) }
        guarded("set the punctuation default") { punctuationDefault(configHome) }
        guarded("keep the tables ordering by use") { orderByUseKept(configHome, engineDir) }
    }

    private inline fun guarded(what: String, step: () -> Unit) {
        try {
            step()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "cannot %s", what)
        }
    }

    private fun libimeConfig(configHome: File) {
        val profile = File(configHome, "profile")
        if (profile.exists()) LibimeMigration.profile(profile.readText())?.let { replace(configHome, profile, it) }
        val settings = File(configHome, "conf/androidengine.conf")
        val pinyin = File(configHome, "conf/pinyin.conf")
        // libime keeps a table's settings by the table's name, not under conf/ as addons do
        val tables = LibimeMigration.TABLE_CONFIGS.map { it to File(configHome, "table/$it.conf") }
            .filter { it.second.exists() }.associate { (name, file) -> name to file.readText() }
        if (!settings.exists()) {
            if (pinyin.exists() || tables.isNotEmpty()) {
                replace(configHome, settings, LibimeMigration.settings(if (pinyin.exists()) pinyin.readText() else "", tables))
            }
        } else if (tables.isNotEmpty()) {
            // migrated by a build whose engine had no table settings yet
            LibimeMigration.withTables(settings.readText(), tables)?.let { replace(configHome, settings, it) }
        }
        // fcitx's androidkeyboard, English before 0.1.0 and upstream's: its word hints off, English's suggestions too
        val keyboard = File(configHome, "conf/androidkeyboard.conf")
        if (keyboard.exists()) {
            LibimeMigration.englishSuggest(if (settings.exists()) settings.readText() else "", keyboard.readText())
                ?.let { replace(configHome, settings, it) }
        }
        val chttrans = File(configHome, "conf/chttrans.conf")
        if (chttrans.exists()) LibimeMigration.inputMethodList(chttrans.readText())?.let { replace(configHome, chttrans, it) }
    }

    private fun punctuationDefault(configHome: File) {
        val punctuation = File(configHome, "conf/punctuation.conf")
        // the app's choice, not libime's to carry over: nothing kept to undo
        AddonDefaults.punctuation(if (punctuation.exists()) punctuation.readText() else "")?.let { text ->
            punctuation.parentFile?.mkdirs()
            replaceFile(punctuation, File(punctuation.path + ".new")) { it.writeText(text) }
        }
    }

    /**
     * 调频 is off on a new install (U-28 D7); a table typed with in an older build, which has its log
     * in [engineDir], keeps it on where its settings do not say. Once, at the first start of this
     * build ([ORDER_KEPT] says it ran): a table's log made since is a new install's. Decided only
     * once the phone is unlocked, with [engineDir] to read. A start while locked notes which tables'
     * settings did not say yet ([ORDER_UNSET], the first such start's): fcitx saves every value,
     * its defaults too, at the next change of any setting, and a table's `No` written so before
     * the unlocked start is the new default, not the user's.
     */
    private fun orderByUseKept(configHome: File, engineDir: File?) {
        val settings = File(configHome, "conf/androidengine.conf")
        val unset = File(configHome, ORDER_UNSET)
        val config = if (settings.exists()) settings.readText() else ""
        if (engineDir == null) {
            if (!unset.exists()) replaceFile(unset, File(unset.path + ".new")) {
                it.writeText(LibimeMigration.orderUnset(config).joinToString("\n"))
            }
            return
        }
        // a new install has no engine directory yet: made, or the marker could not be written and a
        // table typed with from then on would be taken for an older build's (review C M1); an import
        // killed between its renames left it as engine.old, put back first, as the engine would
        try {
            restoreDirectory(engineDir)
        } catch (e: IOException) {
            Timber.w(e, "engine data not put back: the tables' ordering decided at a later start")
            return
        }
        if (!engineDir.isDirectory && !engineDir.mkdirs()) return
        val done = File(engineDir, ORDER_KEPT)
        if (!done.exists()) {
            val typed = Engines.TABLES.keys.filter { File(engineDir, Engines.userTable(it)).exists() }
            val before = if (unset.exists()) unset.readLines().filterTo(HashSet()) { it.isNotEmpty() } else emptySet()
            LibimeMigration.keepOrderByUse(config, typed, before)?.let {
                settings.parentFile?.mkdirs()
                replace(configHome, settings, it)
            }
            done.createNewFile()
        }
        unset.delete()
    }

    /**
     * What a table imported in an older build left: its input method's conf, which fcitx would
     * still list with nothing to type it, and the copy of it kept when it was migrated ([keep]),
     * its table, settings and what it learned. The app's own tables are kept apart: no conf of
     * theirs in the data home, their logs in [engineDir] itself.
     */
    private fun importedTablesGone(configHome: File, engineDir: File?) {
        val data = File(configHome.parentFile, "data")
        val confs = File(data, "inputmethod").listFiles { f -> f.isFile && f.name.endsWith(".conf") }.orEmpty()
        val gone = confs.filter { conf ->
            val imported = try {
                conf.readLines().any { it.trim() == "Addon=androidengine" }
            } catch (e: IOException) {
                Timber.w(e, "imported table %s", conf.name)
                false
            }
            imported && conf.delete()
        }.toMutableList()
        val settings = File(configHome, "table").listFiles { f -> f.name.removeSuffix(".conf") !in LibimeMigration.TABLE_CONFIGS }
        gone += settings.orEmpty().filter { it.delete() }
        val dirs = listOfNotNull(File(data, "table"), File(File(data, KEPT), "inputmethod"), engineDir?.let { File(it, "tables") })
        gone += dirs.filter { it.exists() && it.deleteRecursively() }
        // the user's own tables and what they learned, gone for good: an exported log says so
        if (gone.isNotEmpty()) Timber.i("imported tables gone: %s", gone)
    }

    // a profile cut short by a kill would lose every group
    private fun replace(home: File, file: File, text: String) {
        if (file.exists()) keep(home, file)
        replaceFile(file, File(file.path + ".new")) {
            it.writeText(text)
        }
    }

    /**
     * [file], under [home], as it was before the migration first rewrote it: kept by the same path
     * under [KEPT], once, for a bug here to be undone and an older build to read it. One that
     * cannot be kept is not rewritten.
     */
    private fun keep(home: File, file: File) {
        val kept = File(File(home, KEPT), file.toRelativeString(home))
        if (kept.exists()) return
        kept.parentFile?.mkdirs()
        replaceFile(kept, File(kept.path + ".new")) {
            file.copyTo(it, overwrite = true)
        }
    }

    // at the top of each home: in no directory fcitx or the settings list
    private const val KEPT = ".libime"

    // in the engine's user data, beside the tables' logs: the tables typed with before were looked at
    internal const val ORDER_KEPT = "tables.order-kept"

    // in the config home, readable while locked: see orderByUseKept
    internal const val ORDER_UNSET = "tables.order-unset"
}
