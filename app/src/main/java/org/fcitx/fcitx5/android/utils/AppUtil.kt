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
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
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

    /** MainActivity is exported, and some routes name any file: only routes [launchMainToDest] sends are opened from an intent. */
    fun isLaunchRoute(route: SettingsRoute) = when (route) {
        SettingsRoute.Keys, SettingsRoute.InputMethodList, SettingsRoute.Theme, SettingsRoute.Packs,
        is SettingsRoute.InputMethodConfig, is SettingsRoute.QuickPhrase -> true
        is SettingsRoute.ScrollList -> ScrollDefaults.of(route.id) != null
        else -> false
    }

    fun launchMainToPacks(context: Context) =
        launchMainToDest(context, SettingsRoute.Packs)

    fun launchMainToKeyboard(context: Context) =
        launchMainToDest(context, SettingsRoute.Keys)

    fun launchMainToInputMethodList(context: Context) =
        launchMainToDest(context, SettingsRoute.InputMethodList)

    /** A scroll list's editor, from its long press menu. */
    fun launchMainToScrollList(context: Context, id: String) =
        launchMainToDest(context, SettingsRoute.ScrollList(id))

    fun launchMainToThemeList(context: Context) =
        launchMainToDest(context, SettingsRoute.Theme)

    fun launchMainToInputMethodConfig(context: Context, uniqueName: String, displayName: String) =
        launchMainToDest(context, SettingsRoute.InputMethodConfig(displayName, uniqueName))

    /** Too-long content is refused here (false): megabyte clips would not fit the Intent (TransactionTooLargeException). */
    fun launchQuickPhraseEditor(context: Context, entry: QuickPhraseEntry? = null, content: String = "", key: String = ""): Boolean {
        val text = entry?.content ?: content
        if (text.length > QuickPhraseBook.MAX_CONTENT) {
            context.toast(context.getString(R.string.quick_phrase_too_long_to_save, QuickPhraseBook.MAX_CONTENT))
            return false
        }
        launchMainToDest(
            context,
            SettingsRoute.QuickPhrase(
                edit = true,
                key = entry?.key ?: key,
                content = text,
                existing = entry != null,
                note = entry?.note.orEmpty(),
                folder = entry?.folder.orEmpty(),
            ),
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

    /** Kills the process after asking for the settings again; in the background it only ends, as Android 10+ blocks background starts. */
    fun restart(context: Context) {
        context.startActivity<MainActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        exit()
    }
}
