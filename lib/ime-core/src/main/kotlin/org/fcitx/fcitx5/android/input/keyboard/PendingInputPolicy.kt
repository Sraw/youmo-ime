/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * What happens to half-typed input when a key does something other than typing -- commits a
 * symbol, starts quick phrase or unicode input. fcitx is reset afterwards either way; this
 * decides what the user keeps first.
 */
object PendingInputPolicy {

    enum class Resolution {
        /** Take the first candidate, as Space would. */
        SelectFirstCandidate,

        /** Keep the preedit in the editor exactly as typed. */
        FinishComposing,

        /** Nothing to keep. */
        None,
    }

    /**
     * Chinese input methods turn their preedit (pinyin, say) into the first candidate: the raw
     * preedit is rarely what anyone wants in the text. With no preedit the candidates on offer
     * are predictions, which are not taken. Other languages type the text itself as the
     * preedit, so it is kept as is.
     *
     * @param hasPreedit whether there is a preedit, in the editor or on fcitx's input panel
     */
    fun resolve(languageCode: String, hasPreedit: Boolean): Resolution = when {
        !languageCode.startsWith("zh") -> Resolution.FinishComposing
        hasPreedit -> Resolution.SelectFirstCandidate
        else -> Resolution.None
    }
}
