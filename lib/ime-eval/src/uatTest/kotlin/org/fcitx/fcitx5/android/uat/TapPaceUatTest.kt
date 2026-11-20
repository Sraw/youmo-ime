/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a pause shows may depend on how fast the keys before it came: the refine goes on from the slices between keys. */
class TapPaceUatTest {
    private fun typed(gapMillis: Long): List<String> {
        val r = HostReplay(
            engines = UatEngines.fresh(), im = "engine-wubi", field = EngineReplay.Field.TEXT,
            inputMethods = listOf("engine-pinyin", "keyboard-us", "engine-wubi"),
        )
        // a slice and a key as a phone takes them, about: so many slices fit between keys
        r.refineMillis = 1
        r.keyMillis = 2
        r.type(".")
        r.ime("engine-pinyin")
        r.focus("", EngineReplay.Field.EMAIL, app = 1)
        return listOf("message", "send", "world", "home", "thanks", "morning").map {
            r.typeAt(it, gapMillis)
            r.preedit
        }
    }

    @Test
    fun aLongInputTypedFastGivesUpTheRefineThatSlowTypingFinishes() {
        assertEquals("message sen d world home thanks morning", typed(20).last())
        assertEquals("message send world home thanks morning", typed(200).last())
    }
}
