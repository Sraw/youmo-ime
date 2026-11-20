/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId

/** A pack's key settings copied from old to new names as its layout is converted, the old kept; never over the user's; null where nothing changes or the text is not this version's. */
object RenamedKeys {

    /** Whole lists: items, first and marks. */
    fun keyLists(text: String?, renames: Map<String, String>): String? {
        val lists = KeyLists.parse(text) ?: return null
        var out = lists
        for ((old, new) in renames) {
            val list = lists[KeyId(old)]
            // as it is: `with` compares with a default and drops an `off` with none, while the new key may have one
            if (list != null && out[KeyId(new)] == null) out = KeyLists.of(out.lists + (KeyId(new) to list))
        }
        return out.takeIf { it != lists }?.serialize()
    }

    /** Up and down apart. */
    fun keySwipes(text: String?, renames: Map<String, String>): String? {
        val slots = SwipeSlots.parse(text.orEmpty())
        var out = slots
        for ((old, new) in renames) {
            for (up in listOf(true, false)) {
                if (slots.isSet(old, up) && !out.isSet(new, up)) out = out.with(new, up, slots.resolve(old, up))
            }
        }
        return out.takeIf { it != slots }?.serialize()
    }
}
