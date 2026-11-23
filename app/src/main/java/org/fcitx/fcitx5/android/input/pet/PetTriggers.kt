/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.scene.pet.PetTrigger

/** The kind of key an action is, or null where the pet does not follow it (a glide, a voice commit, switching, selecting, the cursor moved). */
object PetTriggers {

    fun of(action: KeyAction): PetTrigger? = when (action) {
        is KeyAction.HostKeyAction -> PetTrigger.TYPING
        is KeyAction.SymAction -> when (action.sym.sym) {
            FcitxKeyMapping.FcitxKey_BackSpace -> PetTrigger.DELETE
            FcitxKeyMapping.FcitxKey_Return, FcitxKeyMapping.FcitxKey_KP_Enter -> PetTrigger.ENTER
            FcitxKeyMapping.FcitxKey_space -> PetTrigger.SPACE
            // a swipe on space sends them each step: moving the cursor is no typing
            FcitxKeyMapping.FcitxKey_Left, FcitxKeyMapping.FcitxKey_Right, FcitxKeyMapping.FcitxKey_Up, FcitxKeyMapping.FcitxKey_Down,
            FcitxKeyMapping.FcitxKey_Home, FcitxKeyMapping.FcitxKey_End, FcitxKeyMapping.FcitxKey_Page_Up, FcitxKeyMapping.FcitxKey_Page_Down -> null
            else -> PetTrigger.TYPING
        }
        is KeyAction.CommitAction -> if (action.voice) null else PetTrigger.TYPING
        else -> null
    }
}
