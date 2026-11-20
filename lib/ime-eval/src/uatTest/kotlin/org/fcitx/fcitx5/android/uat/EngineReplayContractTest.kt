/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.host.KeySyms
import org.junit.Assert.assertEquals
import org.junit.Test

/** contract: [EngineReplay]'s named keys are fcitx's keysyms as [KeySyms.fromString] reads them, or a case would pass here and fail on the phone. */
class EngineReplayContractTest {

    @Test
    fun theNamedKeysAreFcitxSKeysyms() {
        for ((key, sym) in EngineReplay.NAMED_KEYS) {
            assertEquals(key, KeySyms.fromString(key.removePrefix("FcitxKey_")), sym)
        }
    }
}
