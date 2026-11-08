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

    /**
     * the default: hold to talk, with the voice pack imported; without it holding the space does
     * nothing, as 0.5.x's text build did: no notice, no badge, a slow drag's cursor not taken
     * (the toolbar's microphone is what asks for the pack)
     */
    Voice(R.string.voice_input);

    /**
     * Whether holding the space talks: with the voice pack ready ([voiceReady], PackStore's), and never
     * in a password field, as a password is not to be said aloud.
     */
    fun talks(voiceReady: Boolean, inPasswordField: Boolean): Boolean =
        this == Voice && voiceReady && !inPasswordField
}
