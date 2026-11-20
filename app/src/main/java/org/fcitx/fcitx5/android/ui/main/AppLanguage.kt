/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast
import java.util.Locale

/**
 * Android 13's per-app language (appcompat stores it on older versions), only fully translated ones, each named in
 * itself. The keyboard takes it on itself (FcitxInputMethodService.getResources).
 */
object AppLanguage {

    /** Empty for the phone's. */
    val tags = listOf("", "zh-CN", "zh-TW", "en")

    /** Or a phone-picked language outside [tags], its own tag. */
    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "" else tagOf(locales[0])
    }

    internal fun tagOf(locale: Locale?): String = when {
        locale == null || locale.language.isEmpty() -> ""
        locale.language == "en" -> "en"
        // read as "", it showed as the phone's, and choosing that did nothing
        locale.language != "zh" -> locale.toLanguageTag()
        // a script outweighs the region, as Android matches resources by it: zh-Hans-TW reads as zh-CN
        locale.script.isNotEmpty() -> if (locale.script == "Hant") "zh-TW" else "zh-CN"
        locale.country in setOf("TW", "HK", "MO") -> "zh-TW"
        else -> "zh-CN"
    }

    fun label(tag: String) = when (tag) {
        "zh-CN" -> R.string.language_zh_cn
        "zh-TW" -> R.string.language_zh_tw
        "en" -> R.string.language_en
        else -> R.string.language_follow_system
    }

    fun name(context: Context, tag: String): String {
        if (tag in tags) return context.getString(label(tag))
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** The activities are recreated in it. */
    fun choose(context: Context, onChosen: (String) -> Unit) {
        // -1 for a language not in the list: then any choice switches
        val current = tags.indexOf(current())
        AlertDialog.Builder(context)
            .setTitle(R.string.app_language)
            .setSingleChoiceItems(tags.map { name(context, it) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) {
                    set(tags[which])
                    onChosen(tags[which])
                }
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
        // a host still starting is waited for (else it keeps the old names): off the main thread
        if (HostDaemon.hasClient()) {
            FcitxApplication.getInstance().coroutineScope.launch(Dispatchers.IO) {
                // a restart still starting may or may not take the new language, which the wait cannot tell: said either way
                if (!HostDaemon.restartFcitx()) {
                    withContext(Dispatchers.Main) { appContext.toast(R.string.restart_fcitx_still_starting) }
                }
            }
        }
    }
}
