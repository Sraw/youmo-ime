/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a pause shows may depend on how fast the keys before it came, by decision (dev/review/DECISIONS.md
 * 2026-10-25): the pause's refining goes on from the work slices between keys did, 64 slices at most,
 * as 0.3.x did. Parity sequence r2-7560 (dev/parity-runs/random-2.jsonl), the host's refine slices
 * costing time on the [HostReplay.clock]: its sixth word 「send」 with a slice between taps, 「sen d」 without.
 */
class TapPaceUatTest {
    private fun typed(gapMillis: Long): List<String> {
        val r = HostReplay(
            engines = UatEngines.fresh(), im = "engine-wubi", field = EngineReplay.Field.TEXT,
            inputMethods = listOf("engine-pinyin", "keyboard-us", "engine-t9", "engine-wubi"),
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
