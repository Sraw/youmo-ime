/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.core

data class CandidateWord @JvmOverloads constructor(
    val label: String,
    val text: String,
    val comment: String,
    val spaceBetweenComment: Boolean = true,
    /** 候选词注音: one string per code point of [text], empty for one shown none; empty for none at all. */
    val readings: List<String> = emptyList(),
) {
    fun textWithComment(): String {
        return buildString {
            append(text)
            if (comment.isNotBlank()) {
                if (spaceBetweenComment) {
                    append(" ")
                }
                append(comment)
            }
        }
    }

    companion object {
        val Empty = CandidateWord("", "", "", false)
    }
}
