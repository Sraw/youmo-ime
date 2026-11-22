/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.scene.pet.PetTrigger

/** The kind of key an action is, or null where the pet does not follow it (a glide, a voice commit, switching, selecting). */
object PetTriggers {

    fun of(action: KeyAction): PetTrigger? = when (action) {
        is KeyAction.HostKeyAction -> PetTrigger.TYPING
        is KeyAction.SymAction -> when (action.sym.sym) {
            FcitxKeyMapping.FcitxKey_BackSpace -> PetTrigger.DELETE
            FcitxKeyMapping.FcitxKey_Return, FcitxKeyMapping.FcitxKey_KP_Enter -> PetTrigger.ENTER
            FcitxKeyMapping.FcitxKey_space -> PetTrigger.SPACE
            else -> PetTrigger.TYPING
        }
        is KeyAction.CommitAction -> if (action.voice) null else PetTrigger.TYPING
        else -> null
    }
}
