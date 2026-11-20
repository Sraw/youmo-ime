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

/** Blocks for what pinyin lists beside its readings (AsideCandidates), by text in a reading: a parts character in its own ([hint], toned), an emoji in its word's ([standsFor]). */
internal object AsideBlocks {

    /** Null where no reading is known. */
    fun entry(text: String, hint: String, standsFor: () -> List<Entry>?): Entry? =
        if (hint.isNotEmpty()) WordLists.entry(text, Misreadings.toneless(hint)) else standsFor()?.let { Entry.of(text, it) }

    /** The reading a parts character is blocked in: for a polyphone hint (`xing/hang`), the one shown above it ([shown]), else the first; not each, which would remove it from its other readings' pinyin. */
    fun shown(text: String, hint: String, shown: (String) -> String?): String =
        if (CandidateReadings.SEPARATOR !in hint) hint else shown(text) ?: hint.substringBefore(CandidateReadings.SEPARATOR)

    /** [emoji] minus those [user] blocked in their word's reading (of the [read] item whose [text] it is). */
    fun <T> emoji(emoji: (String) -> List<String>, user: UserModel?, read: List<T>, text: (T) -> String, words: (T) -> List<Entry>?): (String) -> List<String> =
        if (user == null) emoji else { word -> emoji(word).filterNot { e -> user.blocksSentence(e) { read.firstOrNull { text(it) == word }?.let(words) } } }
}
