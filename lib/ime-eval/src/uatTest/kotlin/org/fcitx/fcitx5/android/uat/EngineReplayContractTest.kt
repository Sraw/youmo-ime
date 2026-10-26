/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.host.KeySyms
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contract: [EngineReplay]'s named keys are fcitx's keysyms by fcitx's names, as the shipped host's
 * [KeySyms.fromString] reads them; that table is held to upstream fcitx5's key.cpp by ime-core's
 * KeySymsGoldenTest, so no download is needed here (F3862). A case replayed with a wrong keysym would
 * pass here and fail on the phone. Which event each key sends is the Kotlin host's `KeyRouter`'s,
 * which [EngineReplay] drives.
 */
class EngineReplayContractTest {

    @Test
    fun theNamedKeysAreFcitxSKeysyms() {
        for ((key, sym) in EngineReplay.NAMED_KEYS) {
            assertEquals(key, KeySyms.fromString(key.removePrefix("FcitxKey_")), sym)
        }
    }
}
