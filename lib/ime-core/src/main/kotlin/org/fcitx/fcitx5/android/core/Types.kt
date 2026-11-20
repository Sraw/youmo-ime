/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

data class InputMethodEntry(
    val uniqueName: String,
    val name: String,
    val icon: String,
    val label: String,
    val languageCode: String,
) {
    constructor(name: String) : this("", name, "", "×", "")

    val displayName: String
        get() = name.ifEmpty { uniqueName }
}

data class CandidateAction(
    val id: Int,
    val text: String,
    val isSeparator: Boolean,
    val icon: String,
    val isCheckable: Boolean,
    val isChecked: Boolean
)
