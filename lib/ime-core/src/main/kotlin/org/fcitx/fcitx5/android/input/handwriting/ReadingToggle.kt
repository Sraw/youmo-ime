/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.engine.reading.ReadingStyle

/**
 * The handwriting pad's 注 key, which sets 候选词注音 (the same setting as its row in the settings, for
 * every input method): a tap turns it off where it is on, on in the style last used where it is off
 * (pinyin, none used yet); a long press offers [CHOICES]. The style last used is any but off that the
 * setting had, from the key or the settings.
 */
object ReadingToggle {

    /** The long press's choices, in their order: 关, 拼音, 注音符号. */
    val CHOICES: List<ReadingStyle> = listOf(ReadingStyle.OFF, ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)

    /** What a tap sets where the setting is [now], [last] the style last used (its key; empty, none). */
    fun tapped(now: ReadingStyle, last: String): ReadingStyle =
        if (now != ReadingStyle.OFF) ReadingStyle.OFF else ReadingStyle.of(last).takeIf { it != ReadingStyle.OFF } ?: ReadingStyle.PINYIN

    /** The style last used once the setting is [now]: [now] where it is on, else [last] as it was. */
    fun last(now: ReadingStyle, last: String): String = if (now != ReadingStyle.OFF) now.key else last
}
