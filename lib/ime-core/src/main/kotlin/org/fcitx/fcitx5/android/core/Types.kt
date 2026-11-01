/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

data class InputMethodSubMode(val name: String, val label: String, val icon: String) {
    constructor() : this("", "", "")
}

data class InputMethodEntry(
    val uniqueName: String,
    val name: String,
    val icon: String,
    val nativeName: String,
    val label: String,
    val languageCode: String,
    val addon: String,
    val isConfigurable: Boolean,
    val subMode: InputMethodSubMode
) {
    constructor(
        uniqueName: String,
        name: String,
        icon: String,
        nativeName: String,
        label: String,
        languageCode: String,
        addon: String,
        isConfigurable: Boolean
    ) : this(
        uniqueName,
        name,
        icon,
        nativeName,
        label,
        languageCode,
        addon,
        isConfigurable,
        InputMethodSubMode()
    )

    constructor(
        uniqueName: String,
        name: String,
        icon: String,
        nativeName: String,
        label: String,
        languageCode: String,
        addon: String,
        isConfigurable: Boolean,
        subMode: String,
        subModeLabel: String,
        subModeIcon: String
    ) : this(
        uniqueName,
        name,
        icon,
        nativeName,
        label,
        languageCode,
        addon,
        isConfigurable,
        InputMethodSubMode(subMode, subModeLabel, subModeIcon)
    )

    constructor(name: String) : this("", name, "", "", "×", "", "", false)

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
