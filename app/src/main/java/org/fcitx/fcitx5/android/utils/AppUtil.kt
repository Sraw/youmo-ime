/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.content.Intent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.ui.main.ClipboardEditActivity
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import kotlin.system.exitProcess

object AppUtil {

    fun launchMain(context: Context) {
        context.startActivity<MainActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
    }

    private fun launchMainToDest(context: Context, route: SettingsRoute) {
        context.startActivity<MainActivity> {
            action = Intent.ACTION_RUN
            putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, route)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
    }

    /**
     * Whether [route] is one [launchMainToDest] sends, the only ones MainActivity opens from an
     * intent: it is exported, and a route such as the pinyin dictionaries' names any file it likes.
     * Quick input's editor may so be opened filled in by another app: nothing is saved or deleted till
     * the user taps it, and a content over the limit is refused (MainActivity).
     */
    fun isLaunchRoute(route: SettingsRoute) = when (route) {
        SettingsRoute.Keys, SettingsRoute.InputMethodList, SettingsRoute.Theme,
        is SettingsRoute.InputMethodConfig, is SettingsRoute.QuickPhrase -> true
        else -> false
    }

    fun launchMainToKeyboard(context: Context) =
        launchMainToDest(context, SettingsRoute.Keys)

    fun launchMainToInputMethodList(context: Context) =
        launchMainToDest(context, SettingsRoute.InputMethodList)

    fun launchMainToThemeList(context: Context) =
        launchMainToDest(context, SettingsRoute.Theme)

    fun launchMainToInputMethodConfig(context: Context, uniqueName: String, displayName: String) =
        launchMainToDest(context, SettingsRoute.InputMethodConfig(displayName, uniqueName))

    /**
     * Quick input's page with its editor open: [entry] to change, or a new one, [content] filled in
     * (the clipboard's 存为快速输入) with [key] as the key suggested. A content longer than the page
     * saves is refused here, told, and the page not opened (false): the clipboard keeps clips of
     * megabytes, which would also not fit in the Intent (TransactionTooLargeException, the process gone).
     */
    fun launchQuickPhraseEditor(context: Context, entry: QuickPhraseEntry? = null, content: String = "", key: String = ""): Boolean {
        val text = entry?.content ?: content
        if (text.length > QuickPhraseBook.MAX_CONTENT) {
            context.toast(context.getString(R.string.quick_phrase_too_long_to_save, QuickPhraseBook.MAX_CONTENT))
            return false
        }
        launchMainToDest(
            context,
            SettingsRoute.QuickPhrase(edit = true, key = entry?.key ?: key, content = text, existing = entry != null),
        )
        return true
    }

    fun launchClipboardEdit(context: Context, id: Int, lastEntry: Boolean = false) {
        context.startActivity<ClipboardEditActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ClipboardEditActivity.ENTRY_ID, id)
            putExtra(ClipboardEditActivity.LAST_ENTRY, lastEntry)
        }
    }

    fun exit() {
        exitProcess(0)
    }

    /**
     * Ends the process and opens the settings again, in a new one: the app's files were replaced
     * under it. The system starts the new process for the activity asked for just before. Left for
     * another app meanwhile, it only ends: Android 10 and later block a page an app in the
     * background opens, and a notice to tap would need the notification permission the app does
     * not ask for. The data is restored all the same, the toast says so, and the app opens as usual.
     */
    fun restart(context: Context) {
        context.startActivity<MainActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        exit()
    }
}
