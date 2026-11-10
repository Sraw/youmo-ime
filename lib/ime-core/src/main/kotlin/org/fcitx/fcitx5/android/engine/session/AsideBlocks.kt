/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.Misreadings
import org.fcitx.fcitx5.android.engine.reading.CandidateReadings
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.WordLists

/**
 * What pinyin lists beside its readings (AsideCandidates), blocked by their text in a reading
 * (U-82): a character by its parts in its own ([hint], toned), an emoji in that of the word it
 * stands for ([standsFor]'s words).
 */
internal object AsideBlocks {

    /** The entry blocking [text] blocks; null where no reading is known. */
    fun entry(text: String, hint: String, standsFor: () -> List<Entry>?): Entry? =
        if (hint.isNotEmpty()) WordLists.entry(text, Misreadings.toneless(hint)) else standsFor()?.let { Entry.of(text, it) }

    /**
     * The reading [text], a character by its parts, is blocked in by its [hint]: a polyphone's several
     * (`xing/hang`) the one shown above it ([shown], CandidateReadings.oneOf after what was picked before
     * it; else the first), as a one-reading character is blocked in its own (A5). Not each: that would
     * take it from the pinyin of its other readings too.
     */
    fun shown(text: String, hint: String, shown: (String) -> String?): String =
        if (CandidateReadings.SEPARATOR !in hint) hint else shown(text) ?: hint.substringBefore(CandidateReadings.SEPARATOR)

    /** [emoji] but those [user] blocked in the reading of the word they stand for: that of [read] whose [text] it is, in its [words]. */
    fun <T> emoji(emoji: (String) -> List<String>, user: UserModel?, read: List<T>, text: (T) -> String, words: (T) -> List<Entry>?): (String) -> List<String> =
        if (user == null) emoji else { word -> emoji(word).filterNot { e -> user.blocksSentence(e) { read.firstOrNull { text(it) == word }?.let(words) } } }
}
