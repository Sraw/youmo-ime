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
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.appContext
import java.util.Locale

/**
 * The language the app shows itself in, the phone's or one the user picks, as Android 13's
 * per-app language has it (appcompat stores it on older versions). Only the languages the app is
 * translated into in full; each named in itself, as a user who cannot read the current one looks
 * for their own. The keyboard, which Android leaves in the phone's language, takes it on itself
 * (FcitxInputMethodService.getResources).
 */
object AppLanguage {

    /** A language tag; empty for the phone's. */
    val tags = listOf("", "zh-CN", "zh-TW", "en")

    /** One of [tags]; or, for a language picked in the phone's settings that they lack, its own tag. */
    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "" else tagOf(locales[0])
    }

    internal fun tagOf(locale: Locale?): String = when {
        locale == null || locale.language.isEmpty() -> ""
        locale.language == "en" -> "en"
        // one the phone's settings picked: read as "", it showed as the phone's, and choosing that did nothing
        locale.language != "zh" -> locale.toLanguageTag()
        // Hant, or a region that writes it
        locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") -> "zh-TW"
        else -> "zh-CN"
    }

    fun label(tag: String) = when (tag) {
        "zh-CN" -> R.string.language_zh_cn
        "zh-TW" -> R.string.language_zh_tw
        "en" -> R.string.language_en
        else -> R.string.language_follow_system
    }

    /** [tag]'s name, in itself; one not in [tags] as the platform names it. */
    fun name(context: Context, tag: String): String {
        if (tag in tags) return context.getString(label(tag))
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** Asks for a language and switches to it: the activities are made again in it. */
    fun choose(context: Context) {
        // -1, none ticked, for a language not in the list: then any choice switches
        val current = tags.indexOf(current())
        AlertDialog.Builder(context)
            .setTitle(R.string.app_language)
            .setSingleChoiceItems(tags.map { name(context, it) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) set(tags[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun set(tag: String) {
        AppPrefs.getInstance().internal.appLanguage.setValue(tag)
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
        // fcitx names its input methods and config pages in the language it starts in;
        // one not running starts in this one anyway
        Locales.onLocalesPicked(appContext)
        if (FcitxDaemon.getFirstConnectionOrNull() != null) FcitxDaemon.restartFcitx()
    }
}
