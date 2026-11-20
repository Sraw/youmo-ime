/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

fun symbolPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Symbol,
    data = { PickerData.Symbol },
    density = PickerGridView.Density.Panel,
)

fun emojiPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Emoji,
    data = { PickerData.Emoji },
    density = PickerGridView.Density.Medium,
    popupPreview = false,
    followKeyBorder = false,
    policy = EmojiPickerPolicy(),
    search = true,
)

fun emoticonPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Emoticon,
    data = { PickerData.Emoticon },
    density = PickerGridView.Density.Low,
    popupPreview = false,
    followKeyBorder = false
)
