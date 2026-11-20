/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val DAY_MS = 86_400_000L

// one for a list of thousands, made again only if the language changed; main thread alone
private var dayFormat: Pair<Locale, DateFormat>? = null

/** Formatted in UTC: the day is already local, and formatted in the phone's zone it read as the day before west of UTC. */
internal fun formatLocalDay(day: Int): String {
    val locale = Locale.getDefault()
    val format = dayFormat?.takeIf { it.first == locale }?.second
        ?: DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .also { dayFormat = locale to it }
    return format.format(Date(day * DAY_MS))
}
