/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SpaceLongPressBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    None(R.string.space_behavior_none),
    Enumerate(R.string.space_behavior_enumerate),
    ToggleActivate(R.string.space_behavior_activate),
    ShowPicker(R.string.space_behavior_picker),

    /** the default: hold to talk (without the voice pack imported, asks for it: VoiceFeature) */
    Voice(R.string.voice_input);

    /** Whether holding the space talks; never in a password field, as a password is not to be said aloud. */
    fun talks(inPasswordField: Boolean): Boolean =
        this == Voice && !inPasswordField
}
