/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId

/**
 * The user's settings of a theme pack's keys renamed as its layout is made one of the user's (PackLayouts.convert,
 * 061-custom-layout §2.3, review M3): what was set under an old name copied under its new one, the old kept (Q8: the
 * pack's theme may still be drawn by a layout of that name). A setting the new name has already is the user's, never
 * replaced (a pack converted again). Each returns the text to store, null where nothing changes, or the text is not
 * one this version reads as it is (a `key_lists` to migrate, a `key_swipes` of another version: left as they are).
 */
object RenamedKeys {

    /**
     * [text] of `key_lists` with each list of an old name of [renames] (KeyId's names, old to new) under its new name
     * too, whole: its items, first and marks (read and written as KeyLists does, the `@2`/`@3` header its own).
     */
    fun keyLists(text: String?, renames: Map<String, String>): String? {
        val lists = KeyLists.parse(text) ?: return null
        var out = lists
        for ((old, new) in renames) {
            val list = lists[KeyId(old)]
            // as it is: `with` compares with a default, and with none drops an `off` (no setting where the key
            // has no list anyway), while the new name's key may have one (a letter's): review R26 F8143
            if (list != null && out[KeyId(new)] == null) out = KeyLists.of(out.lists + (KeyId(new) to list))
        }
        return out.takeIf { it != lists }?.serialize()
    }

    /** [text] of `key_swipes` with each slot set of an old name of [renames] (up and down apart) set under its new name too. */
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
