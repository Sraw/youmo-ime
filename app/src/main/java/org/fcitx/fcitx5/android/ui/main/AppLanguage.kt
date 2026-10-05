/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.fcitx.fcitx5.android.R

/**
 * The language the app shows itself in, the phone's or one the user picks, as Android 13's
 * per-app language has it (appcompat stores it on older versions). Only the languages the app is
 * translated into in full; each named in itself, as a user who cannot read the current one looks
 * for their own. Below Android 13 appcompat applies it to the activities only: the keyboard there
 * stays in the phone's language.
 */
object AppLanguage {

    /** A language tag; empty for the phone's. */
    val tags = listOf("", "zh-CN", "zh-TW", "en")

    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return ""
        val locale = locales[0] ?: return ""
        return when {
            locale.language != "zh" -> if (locale.language == "en") "en" else ""
            // Hant, or a region that writes it
            locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") -> "zh-TW"
            else -> "zh-CN"
        }
    }

    fun label(tag: String) = when (tag) {
        "zh-CN" -> R.string.language_zh_cn
        "zh-TW" -> R.string.language_zh_tw
        "en" -> R.string.language_en
        else -> R.string.language_follow_system
    }

    /** Asks for a language and switches to it: the activities are made again in it. */
    fun choose(context: Context) {
        val current = tags.indexOf(current())
        AlertDialog.Builder(context)
            .setTitle(R.string.app_language)
            .setSingleChoiceItems(tags.map { context.getString(label(it)) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) set(tags[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }
}
