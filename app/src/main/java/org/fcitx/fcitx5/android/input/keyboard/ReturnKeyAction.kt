/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import splitties.views.imageResource

/**
 * Shows the Return key's current action: the icon, and the matching label for TalkBack, which
 * would otherwise keep announcing "Enter" on a key that searches or sends.
 */
fun ImageKeyView.showReturnAction(appearance: ReturnKeyAppearance) {
    img.imageResource = appearance.icon
    contentDescription = appearance.label(context)
}
